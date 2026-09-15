"""Synthetic-only WebDAV PUT receiver. No PhotoPrism integration."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile


def make_lab_webdav(root, port=8788):
    root = Path(root).resolve()
    if not root.is_relative_to(Path(tempfile.gettempdir()).resolve()):
        raise ValueError('Lab receiver must use a temporary directory')
    root.mkdir(parents=True, exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_PUT(self):
            # OkHttp streams queued photos with chunked encoding.
            self.connection.settimeout(30)
            data = bytearray()
            try:
                if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                    while True:
                        size = int(self.rfile.readline(128).split(b';')[0], 16)
                        if size == 0:
                            self.rfile.readline(128)
                            break
                        if size < 0 or len(data) + size > 10 * 1024 * 1024:
                            raise ValueError()
                        chunk = self.rfile.read(size)
                        if len(chunk) != size or self.rfile.read(2) != b'\r\n':
                            raise ValueError()
                        data.extend(chunk)
                else:
                    size = int(self.headers.get('Content-Length', '-1'))
                    if not 0 < size <= 10 * 1024 * 1024:
                        raise ValueError()
                    data.extend(self.rfile.read(size))
                    if len(data) != size:
                        raise ValueError()
                if not data or not self.path.startswith('/import/'):
                    raise ValueError()
                (root / (hashlib.sha256(data).hexdigest() + '.jpg')).write_bytes(data)
                self.send_response(201)
            except (OSError, ValueError):
                self.send_response(400)
            self.send_header('Content-Length', '0')
            self.end_headers()

    return ThreadingHTTPServer(('127.0.0.1', port), Handler)
