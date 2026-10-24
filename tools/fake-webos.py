"""An LG webOS television stand-in for testing Kanal's SSAP sender without one.

A WebSocket server on port 3000 (plain, as older sets have it) that answers
`register` like a set seeing Kanal for the first time: a PROMPT, then after
--accept-after seconds the key, which it checks on the next connections.
Media: ssap://media.viewer/open (fetches the first bytes of the address),
media.controls/play|pause|stop|seek, com.webos.media/getForegroundAppInfo,
com.webos.applicationManager/getForegroundAppInfo and media.viewer/close.

    python tools/fake-webos.py [--port 3000] [--accept-after 3] [--deny] [--no-seek]

From an emulator, add the television by hand as 10.0.2.2.
"""
import base64, hashlib, json, socket, struct, sys, threading, time, urllib.request


def arg(name, default):
    return type(default)(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default


PORT = arg('--port', 3000)
ACCEPT_AFTER = arg('--accept-after', 3.0)
DENY = '--deny' in sys.argv
NO_SEEK = '--no-seek' in sys.argv
KEY = 'fake-client-key-1234'
MEDIA_APP = 'com.webos.app.mediadiscovery'
state = {'play': None, 'base': 0.0, 'since': None, 'front': 'com.webos.app.home'}


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def position():
    return state['base'] + (time.time() - state['since'] if state['since'] else 0)


def recv_frame(conn):
    head = conn.recv(2, socket.MSG_WAITALL)
    if len(head) < 2: raise ConnectionError
    opcode, n = head[0] & 0x0F, head[1] & 0x7F
    if n == 126: n = struct.unpack('>H', conn.recv(2, socket.MSG_WAITALL))[0]
    elif n == 127: n = struct.unpack('>Q', conn.recv(8, socket.MSG_WAITALL))[0]
    mask = conn.recv(4, socket.MSG_WAITALL) if head[1] & 0x80 else b'\0\0\0\0'
    data = bytearray(conn.recv(n, socket.MSG_WAITALL)) if n else bytearray()
    for i in range(len(data)): data[i] ^= mask[i % 4]
    return opcode, bytes(data)


def send_frame(conn, text, lock):
    data = text.encode()
    head = bytes([0x81]) + (bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack('>H', len(data)))
    with lock: conn.sendall(head + data)


def fetch(url):
    url = url.replace('10.0.2.2', '127.0.0.1')
    with urllib.request.urlopen(urllib.request.Request(url, headers={'Range': 'bytes=0-4095'}), timeout=10) as r:
        return r.status, len(r.read(4096))


def handle(conn, addr):
    lock = threading.Lock()
    try:
        request = b''
        while b'\r\n\r\n' not in request: request += conn.recv(1024)
        headers = dict(l.split(': ', 1) for l in request.decode().split('\r\n')[1:] if ': ' in l)
        if 'Origin' in headers:
            log('refused: Origin', headers['Origin']); conn.close(); return
        accept = base64.b64encode(hashlib.sha1((headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        conn.sendall(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                      'Sec-WebSocket-Accept: %s\r\n\r\n' % accept).encode())
        log('connected', addr)

        def reply(mid, payload, kind='response'):
            send_frame(conn, json.dumps({'type': kind, 'id': mid, 'payload': payload}), lock)

        while True:
            opcode, data = recv_frame(conn)
            if opcode == 8: break
            if opcode != 1: continue
            msg = json.loads(data)
            mid, uri, payload = msg.get('id'), msg.get('uri', ''), msg.get('payload') or {}
            if msg['type'] == 'register':
                signed = payload.get('manifest', {}).get('signatures', [{}])[0].get('signature', '')
                log('register key=%s signature=%d chars' % (payload.get('client-key'), len(signed)))
                if payload.get('client-key') == KEY and '--forget' not in sys.argv:
                    reply(mid, {'client-key': KEY}, 'registered'); continue
                reply(mid, {'pairingType': 'PROMPT', 'returnValue': True})
                time.sleep(ACCEPT_AFTER)
                if DENY:
                    send_frame(conn, json.dumps({'type': 'error', 'id': mid, 'error': '403 User denied access'}), lock)
                else:
                    log('user accepted on the television')
                    reply(mid, {'client-key': KEY}, 'registered')
                continue
            log(uri.replace('ssap://', ''), payload or '')
            if uri == 'ssap://media.viewer/open':
                try:
                    status, n = fetch(payload['target'])
                    log('  fetched', status, n, 'bytes', payload.get('mimeType'))
                except Exception as e:
                    log('  fetch failed', e)
                state.update(play='playing', base=0.0, since=time.time(), front=MEDIA_APP)
                reply(mid, {'returnValue': True, 'id': MEDIA_APP, 'sessionId': 'c2Vzc2lvbg=='})
            elif uri == 'ssap://media.controls/pause':
                state.update(base=position(), since=None, play='paused'); reply(mid, {'returnValue': True})
            elif uri == 'ssap://media.controls/play':
                state.update(since=state['since'] or time.time(), play='playing'); reply(mid, {'returnValue': True})
            elif uri == 'ssap://media.controls/seek':
                if NO_SEEK:
                    send_frame(conn, json.dumps({'type': 'error', 'id': mid, 'error': '404 no such service or method'}), lock)
                else:
                    state['base'] = payload['position'] / 1000
                    if state['since']: state['since'] = time.time()
                    log('  at', round(position()), 's'); reply(mid, {'returnValue': True})
            elif uri in ('ssap://media.controls/stop', 'ssap://media.viewer/close'):
                state.update(play=None, since=None, front='com.webos.app.home'); reply(mid, {'returnValue': True})
            elif uri == 'ssap://com.webos.media/getForegroundAppInfo':
                info = [{'appId': MEDIA_APP, 'playState': state['play'], 'type': 'media', 'mediaId': 'm1'}] if state['play'] else []
                reply(mid, {'returnValue': True, 'foregroundAppInfo': info})
            elif uri == 'ssap://com.webos.applicationManager/getForegroundAppInfo':
                reply(mid, {'returnValue': True, 'appId': state['front']})
            else:
                send_frame(conn, json.dumps({'type': 'error', 'id': mid, 'error': '404 no such service or method'}), lock)
    except (ConnectionError, OSError, ValueError) as e:
        log('closed', addr, e)
    finally:
        conn.close()


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('0.0.0.0', PORT)); srv.listen()
log('fake webOS on', PORT, 'deny' if DENY else 'accepts after %ss' % ACCEPT_AFTER)
while True:
    c, a = srv.accept()
    threading.Thread(target=handle, args=(c, a), daemon=True).start()
