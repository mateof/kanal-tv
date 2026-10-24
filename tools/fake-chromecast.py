"""A Chromecast stand-in for testing Kanal's Google Cast sender without one.

Speaks CASTV2 on TLS port 8009: connection, heartbeat, the receiver namespace
(GET_STATUS, LAUNCH, STOP) and the Default Media Receiver's media namespace
(LOAD, PLAY, PAUSE, SEEK, GET_STATUS) with a playback clock. On LOAD it fetches
the first bytes of the address, like the real receiver would, and answers
LOAD_FAILED when that fails or when it is a bare MPEG-TS stream.

    openssl req -x509 -newkey rsa:2048 -nodes -keyout cast.key -out cast.crt -days 30 -subj /CN=cast
    python tools/fake-chromecast.py cast.crt cast.key [--port 8009]

From an emulator, add the device by hand as 10.0.2.2:8009. Addresses with
10.0.2.2 in them are fetched from 127.0.0.1.
"""
import json, socket, ssl, struct, sys, threading, time, urllib.request

PORT = int(sys.argv[sys.argv.index('--port') + 1]) if '--port' in sys.argv else 8009
APP = 'CC1AD845'


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def varint(n):
    out = b''
    while n > 0x7F:
        out += bytes([(n & 0x7F) | 0x80]); n >>= 7
    return out + bytes([n])


def encode(src, dst, ns, payload):
    def s(field, text):
        b = text.encode(); return varint(field << 3 | 2) + varint(len(b)) + b
    return b'\x08\x00' + s(2, src) + s(3, dst) + s(4, ns) + b'\x28\x00' + s(6, payload)


def decode(b):
    i, fields = 0, {}
    def vi():
        nonlocal i
        r = sh = 0
        while True:
            c = b[i]; i += 1; r |= (c & 0x7F) << sh; sh += 7
            if not c & 0x80: return r
    while i < len(b):
        key = vi()
        if key & 7 == 0: vi()
        else:
            n = vi(); fields[key >> 3] = b[i:i + n].decode(); i += n
    return fields.get(2, ''), fields.get(3, ''), fields.get(4, ''), fields.get(6, '')


state = {'app': None, 'media': None, 'base': 0.0, 'since': None, 'player': 'IDLE', 'duration': 600.0}


def position():
    p = state['base'] + (time.time() - state['since'] if state['since'] else 0)
    return min(p, state['duration'])


def media_status(rid=0):
    if not state['media']:
        return {'type': 'MEDIA_STATUS', 'requestId': rid, 'status': []}
    return {'type': 'MEDIA_STATUS', 'requestId': rid, 'status': [{
        'mediaSessionId': 1, 'playerState': state['player'], 'currentTime': position(),
        'media': dict(state['media'], duration=None if state['media']['streamType'] == 'LIVE' else state['duration'])}]}


def receiver_status(rid=0):
    apps = [{'appId': APP, 'displayName': 'Default Media Receiver', 'sessionId': 'sess-1',
             'transportId': 'web-1'}] if state['app'] else []
    return {'type': 'RECEIVER_STATUS', 'requestId': rid, 'status': {'applications': apps, 'volume': {'level': 1}}}


def probe(url):
    url = url.replace('10.0.2.2', '127.0.0.1')
    req = urllib.request.Request(url, headers={'Range': 'bytes=0-4095', 'User-Agent': 'fake-cast'})
    with urllib.request.urlopen(req, timeout=10) as r:
        head = r.read(4096)
        return r.status, r.headers.get('Content-Type'), r.headers.get('Access-Control-Allow-Origin'), head


def read_exact(conn, n):
    data = b''
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk: raise ConnectionError('closed')
        data += chunk
    return data


def handle(conn, addr):
    log('connection from', addr)
    lock = threading.Lock()

    def send(dst, ns, obj):
        data = encode('receiver-0' if ns != 'urn:x-cast:com.google.cast.media' else 'web-1', dst, ns, json.dumps(obj))
        with lock:
            conn.sendall(struct.pack('>I', len(data)) + data)

    try:
        while True:
            n = struct.unpack('>I', read_exact(conn, 4))[0]
            body = read_exact(conn, n)
            src, dst, ns, payload = decode(body)
            msg = json.loads(payload)
            t, rid = msg.get('type'), msg.get('requestId', 0)
            if ns.endswith('heartbeat'):
                if t == 'PING': send(src, ns, {'type': 'PONG'})
                continue
            if ns.endswith('connection'):
                log(t, '->', dst); continue
            log(t, ns.rsplit('.', 1)[-1], {k: v for k, v in msg.items() if k not in ('type', 'requestId')} or '')
            if ns.endswith('receiver'):
                if t == 'LAUNCH':
                    state['app'] = APP
                elif t == 'STOP':
                    state.update(app=None, media=None, player='IDLE', since=None)
                send(src, ns, receiver_status(rid))
            elif ns.endswith('media'):
                if t == 'LOAD':
                    media = msg['media']
                    try:
                        status, ctype, cors, first = probe(media['contentId'])
                        log('  fetched', status, ctype, 'CORS=%s' % cors, len(first), 'bytes')
                        if first[:1] == b'\x47' and media.get('contentType') != 'application/x-mpegURL':
                            raise ValueError('MPEG-TS sin HLS: el receptor no lo reproduce')
                    except Exception as e:
                        log('  LOAD_FAILED:', e)
                        send(src, ns, {'type': 'LOAD_FAILED', 'requestId': rid}); continue
                    state.update(media={'contentId': media['contentId'], 'streamType': media.get('streamType'),
                                        'contentType': media.get('contentType')},
                                 base=float(msg.get('currentTime') or 0), since=time.time(), player='PLAYING')
                    send(src, ns, media_status(rid))
                elif t == 'PAUSE':
                    state.update(base=position(), since=None, player='PAUSED'); send(src, ns, media_status(rid))
                elif t == 'PLAY':
                    state.update(since=state['since'] or time.time(), player='PLAYING'); send(src, ns, media_status(rid))
                elif t == 'SEEK':
                    state['base'] = float(msg['currentTime'])
                    if state['since']: state['since'] = time.time()
                    log('  at', round(position()), 's')
                    send(src, ns, media_status(rid))
                elif t == 'STOP':
                    state.update(media=None, player='IDLE', since=None); send(src, ns, media_status(rid))
                else:
                    send(src, ns, media_status(rid))
    except (ConnectionError, OSError, ssl.SSLError) as e:
        log('closed', addr, e)
    finally:
        conn.close()


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(sys.argv[1], sys.argv[2])
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('0.0.0.0', PORT)); srv.listen()
log('fake Chromecast on', PORT)
while True:
    c, a = srv.accept()
    try:
        s = ctx.wrap_socket(c, server_side=True)
    except ssl.SSLError as e:
        log('TLS failed', e); continue
    threading.Thread(target=handle, args=(s, a), daemon=True).start()
