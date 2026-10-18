"""DLNA renderer stand-in with a playback clock: Play, Pause, Seek, GetPositionInfo.

--abs-only   refuse REL_TIME seeks with 710, like renderers that only take ABS_TIME
--no-seek    refuse every seek with 710
"""
import html, http.server, re, sys, time

PORT = 8200
ABS_ONLY = '--abs-only' in sys.argv
NO_SEEK = '--no-seek' in sys.argv
DURATION = 600.0

DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Tele con reloj</friendlyName><UDN>uuid:fake-clock-tv</UDN>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/upnp/control/AVTransport1</controlURL>
<eventSubURL>/e</eventSubURL><SCPDURL>/s.xml</SCPDURL></service></serviceList></device></root>"""

FAULT = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>%d</errorCode>
<errorDescription>%s</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

st = {'transport': 'NO_MEDIA_PRESENT', 'base': 0.0, 'since': None}


def pos():
    p = st['base'] + (time.time() - st['since'] if st['since'] else 0)
    return min(p, DURATION)


def fmt(s):
    s = int(s)
    return '%d:%02d:%02d' % (s // 3600, s // 60 % 60, s % 60)


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def ok(action, inner=''):
    return ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
            f'<s:Body><u:{action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">{inner}'
            f'</u:{action}Response></s:Body></s:Envelope>')


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body):
        d = body.encode()
        self.send_response(code)
        self.send_header('Content-Type', 'text/xml; charset="utf-8"')
        self.send_header('Content-Length', str(len(d)))
        self.end_headers()
        self.wfile.write(d)

    def do_GET(self):
        self.send(200 if self.path == '/description.xml' else 404, DESCRIPTION if self.path == '/description.xml' else '')

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode('utf-8', 'replace')
        action = self.headers.get('SOAPAction', '').strip('"').split('#')[-1]
        if action == 'SetAVTransportURI':
            uri = html.unescape(re.search(r'<CurrentURI>(.*?)</CurrentURI>', body, re.S).group(1))
            log('SET', uri)
            st.update(transport='STOPPED', base=0.0, since=None)
            self.send(200, ok(action))
        elif action == 'Play':
            log('PLAY at', fmt(pos()))
            if st['since'] is None:
                st['since'] = time.time()
            st['transport'] = 'PLAYING'
            self.send(200, ok(action))
        elif action == 'Pause':
            st['base'] = pos(); st['since'] = None; st['transport'] = 'PAUSED_PLAYBACK'
            log('PAUSE at', fmt(st['base']))
            self.send(200, ok(action))
        elif action == 'Seek':
            unit = re.search(r'<Unit>(.*?)</Unit>', body).group(1)
            target = re.search(r'<Target>(.*?)</Target>', body).group(1)
            if NO_SEEK or (ABS_ONLY and unit != 'ABS_TIME'):
                log('SEEK refused', unit, target)
                self.send(500, FAULT % (710, 'Seek mode not supported'))
                return
            h, m, s = target.split(':')
            st['base'] = int(h) * 3600 + int(m) * 60 + float(s)
            if st['since'] is not None:
                st['since'] = time.time()
            log('SEEK', unit, target)
            self.send(200, ok(action))
        elif action == 'GetPositionInfo':
            self.send(200, ok(action, f'<Track>1</Track><TrackDuration>{fmt(DURATION)}</TrackDuration>'
                                      f'<RelTime>{fmt(pos())}</RelTime><AbsTime>{fmt(pos())}</AbsTime>'))
        elif action == 'GetTransportInfo':
            self.send(200, ok(action, f"<CurrentTransportState>{st['transport']}</CurrentTransportState>"
                                      '<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>'))
        else:
            log(action)
            if action == 'Stop':
                st.update(transport='STOPPED', base=0.0, since=None)
            self.send(200, ok(action))


log('renderer on 127.0.0.1:%d abs_only=%s no_seek=%s' % (PORT, ABS_ONLY, NO_SEEK))
http.server.ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
