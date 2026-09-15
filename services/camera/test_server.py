import concurrent.futures
import hashlib
import http.client
from pathlib import Path
import tempfile
import threading
import unittest
from server import make_server


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.server = make_server(self.root, 'test-token', port=0, database=':memory:')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.data = b'synthetic image bytes'
        self.sha = hashlib.sha256(self.data).hexdigest()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, data=None, sha=None, token='test-token'):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        connection.request(method, '/v1/objects/' + (sha or self.sha), body=data,
                           headers={'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        result = response.status
        response.read()
        connection.close()
        return result

    def test_reconcile_retry_and_lost_server_file(self):
        self.assertEqual(404, self.request('HEAD'))
        self.assertEqual(201, self.request('PUT', self.data))
        self.assertEqual(200, self.request('HEAD'))
        self.assertEqual(201, self.request('PUT', self.data))
        self.assertEqual([self.sha], [p.name for p in self.root.iterdir()])
        (self.root / self.sha).unlink()
        self.assertEqual(404, self.request('HEAD'))
        self.assertEqual(201, self.request('PUT', self.data))
        self.assertEqual(self.data, (self.root / self.sha).read_bytes())

    def test_reject_bad_hash_auth_and_paths(self):
        self.assertEqual(422, self.request('PUT', b'wrong bytes'))
        self.assertEqual(401, self.request('PUT', self.data, token='wrong'))
        self.assertEqual(404, self.request('PUT', self.data, sha='../../escape'))
        self.assertEqual([], list(self.root.iterdir()))

    def test_concurrent_identical_uploads(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            codes = list(pool.map(lambda _: self.request('PUT', self.data), range(8)))
        self.assertEqual([201] * 8, codes)
        self.assertEqual([self.sha], [p.name for p in self.root.iterdir()])
        self.assertEqual(self.data, (self.root / self.sha).read_bytes())

    def test_corruption_repaired(self):
        (self.root / self.sha).write_bytes(b'corrupt')
        self.assertEqual(404, self.request('HEAD'))
        self.assertEqual(201, self.request('PUT', self.data))
        self.assertEqual(200, self.request('HEAD'))

    def test_missing_root_fails_closed(self):
        with self.assertRaises(ValueError):
            make_server(self.root / 'missing-mount', 'token')
        with self.assertRaises(ValueError):
            make_server(self.root, '')


if __name__ == '__main__':
    unittest.main()
