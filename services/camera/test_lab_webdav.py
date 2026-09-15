import hashlib
import http.client
from pathlib import Path
import tempfile
import threading
import unittest
from lab_webdav import make_lab_webdav


class LabReceiverTest(unittest.TestCase):
    def test_streamed_synthetic_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = make_lab_webdav(root, port=0)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                connection = http.client.HTTPConnection(*server.server_address, timeout=5)
                chunks = [b'synthetic', b'-photo-bytes']
                connection.request('PUT', '/import/fake.jpg', body=iter(chunks), encode_chunked=True)
                response = connection.getresponse()
                self.assertEqual(201, response.status)
                response.read()
                connection.close()
                data = b''.join(chunks)
                self.assertEqual(data, (root / (hashlib.sha256(data).hexdigest() + '.jpg')).read_bytes())
            finally:
                server.shutdown()
                server.server_close()
                thread.join()

    def test_refuses_non_temporary_destination(self):
        with self.assertRaises(ValueError):
            make_lab_webdav(Path.home() / 'fake-lab-destination', port=0)
