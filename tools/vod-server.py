"""Range-capable file server that behaves like a one-connection IPTV account.

--slots N         concurrent streams allowed (default 1)
--release-ms MS   a closed stream keeps its slot this long (provider noticing late)
"""
import argparse, os, re, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ap = argparse.ArgumentParser()
ap.add_argument("root")
ap.add_argument("--port", type=int, default=8098)
ap.add_argument("--slots", type=int, default=1)
ap.add_argument("--release-ms", type=int, default=0)
args = ap.parse_args()

lock = threading.Lock()
active = 0
released = []  # times at which lingering slots free up


def busy():
    now = time.time()
    released[:] = [t for t in released if t > now]
    return active + len(released)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        print(time.strftime("%H:%M:%S"), self.client_address[0], fmt % a, flush=True)

    def do_GET(self):
        global active
        path = os.path.join(args.root, self.path.split("?")[0].lstrip("/"))
        if self.path.endswith(".m3u") or self.path.endswith(".xml"):
            data = open(path, "rb").read() if os.path.exists(path) else b""
            self.send_response(200 if data else 404)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if not os.path.isfile(path):
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        with lock:
            if busy() >= args.slots:
                print("   -> 403 max connections (busy=%d) Range=%s" % (busy(), self.headers.get("Range")), flush=True)
                body = b"Max connections reached"
                self.send_response(403); self.send_header("Content-Length", str(len(body))); self.end_headers()
                self.wfile.write(body)
                return
            active += 1
        try:
            size = os.path.getsize(path)
            start, end = 0, size - 1
            rng = self.headers.get("Range")
            m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
            if m:
                if m.group(1): start = int(m.group(1))
                if m.group(2): end = int(m.group(2))
                self.send_response(206)
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            else:
                self.send_response(200)
            print("   -> stream Range=%s" % rng, flush=True)
            ctype = "video/mp4" if path.endswith(".mp4") else "video/x-matroska"
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk: break
                    self.wfile.write(chunk)
                    left -= len(chunk)
                    time.sleep(0.01)  # ~6 MB/s, so a stream stays open for a while
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        finally:
            with lock:
                active -= 1
                if args.release_ms:
                    released.append(time.time() + args.release_ms / 1000)

    do_HEAD = do_GET


ThreadingHTTPServer(("127.0.0.1", args.port), H).serve_forever()
