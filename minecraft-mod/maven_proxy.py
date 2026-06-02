import http.server
import urllib.request
import urllib.error
import ssl
import sys
from pathlib import Path
from threading import Thread

CACHE_DIR = Path(__file__).parent / "maven_cache"
UPSTREAM = "https://maven.neoforged.net"
PORT = 18888


class MavenProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        cache_path = CACHE_DIR / self.path.lstrip("/")
        if cache_path.exists():
            print(f"[CACHE] {self.path}", flush=True)
            self._serve_file(cache_path)
            return

        upstream_url = UPSTREAM + self.path
        print(f"[FETCH] {upstream_url}", flush=True)
        try:
            req = urllib.request.Request(upstream_url)
            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, context=ctx, timeout=120) as resp:
                data = resp.read()
                content_type = resp.headers.get("Content-Type", "application/octet-stream")
                cache_path.parent.mkdir(parents=True, exist_ok=True)
                cache_path.write_bytes(data)
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        except urllib.error.HTTPError as e:
            print(f"[HTTP {e.code}] {self.path}", flush=True)
            self.send_response(e.code)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
        except Exception as e:
            print(f"[ERROR] {self.path}: {e}", flush=True)
            self.send_response(502)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()

    def do_HEAD(self):
        cache_path = CACHE_DIR / self.path.lstrip("/")
        if cache_path.exists():
            data = cache_path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return
        upstream_url = UPSTREAM + self.path
        try:
            req = urllib.request.Request(upstream_url)
            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, context=ctx, timeout=120) as resp:
                self.send_response(200)
                self.send_header("Content-Type", resp.headers.get("Content-Type", "application/octet-stream"))
                self.send_header("Content-Length", resp.headers.get("Content-Length", "0"))
                self.end_headers()
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            self.end_headers()
        except Exception as e:
            self.send_response(502)
            self.end_headers()

    def _serve_file(self, path):
        try:
            data = path.read_bytes()
            ext = path.suffix.lower()
            ct = {
                ".pom": "text/xml",
                ".xml": "text/xml",
                ".jar": "application/java-archive",
                ".module": "text/xml",
                ".sha1": "text/plain",
                ".sha256": "text/plain",
                ".sha512": "text/plain",
                ".md5": "text/plain",
                ".properties": "text/plain",
            }.get(ext, "application/octet-stream")
            self.send_response(200)
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            print(f"[ERROR serving cached file] {path}: {e}", flush=True)
            self.send_response(500)
            self.end_headers()

    def log_message(self, format, *args):
        pass


class ThreadedHTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadedHTTPServer(("127.0.0.1", PORT), MavenProxyHandler)
    print(f"Maven proxy running on http://127.0.0.1:{PORT}", flush=True)
    print(f"Upstream: {UPSTREAM}", flush=True)
    print(f"Cache dir: {CACHE_DIR}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...", flush=True)
        server.shutdown()
