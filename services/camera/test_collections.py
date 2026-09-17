"""HTTP checks against synthetic media and independent temporary archive trees."""
import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.parse import quote
from server import make_server


class CollectionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.roots = {key: self.root / key for key in ('camera', 'whatsapp-images', 'whatsapp-videos', 'import')}
        for folder in self.roots.values():
            folder.mkdir()
        self.start()

    def start(self):
        self.server = make_server(self.roots['camera'], 'synthetic', port=0,
            database=self.root / 'camera.db', destinations=[self.roots['import']],
            collections={key: {'root': self.roots[key], 'database': self.root / (key + '.db')}
                         for key in ('whatsapp-images', 'whatsapp-videos')})
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        for inventory in self.server.inventories.values():
            inventory.db.close()

    def tearDown(self):
        self.stop(); self.temp.cleanup()

    def request(self, collection, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address)
        data = {'Authorization': 'Bearer synthetic', 'X-Backup-Collection': collection}
        data.update(headers or {})
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        connection.request(method, path, body=body, headers=data)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def put(self, collection, name, data=b'synthetic bytes', replace=None):
        headers = {'X-Content-SHA256': hashlib.sha256(data).hexdigest()}
        if replace: headers['X-Replace-SHA256'] = replace
        return self.request(collection, 'PUT', '/v1/files/' + quote(name), data, headers)[0]

    def status(self, collection, name, data=b'synthetic bytes'):
        status, refreshed = self.request(collection, 'POST', '/v1/refresh', {})
        self.assertEqual(200, status)
        self.assertEqual(collection, refreshed['collection'])
        status, body = self.request(collection, 'POST', '/v1/check', {
            'generation': refreshed['generation'],
            'items': [{'name': name, 'sha256': hashlib.sha256(data).hexdigest()}]})
        self.assertEqual(200, status)
        return body['items'][0]['status']

    def test_isolation_paths_delivery_restart_and_conflict(self):
        self.assertEqual(201, self.put('camera', 'same.jpg'))
        self.assertTrue((self.roots['import'] / 'same.jpg').is_file())
        self.assertEqual('missing', self.status('whatsapp-images', 'same.jpg'))
        self.assertEqual(201, self.put('whatsapp-images', 'Sent/same.jpg'))
        self.assertEqual('missing', self.status('whatsapp-images', 'same.jpg'))
        self.assertFalse((self.roots['import'] / 'Sent').exists())
        self.assertEqual(201, self.put('whatsapp-videos', 'Sent/same.mp4'))
        self.assertEqual(400, self.put('camera', 'same.mp4'))
        self.assertEqual(400, self.put('whatsapp-images', 'same.mp4'))
        self.assertEqual(400, self.put('whatsapp-videos', 'same.jpg'))
        self.stop(); self.start()
        self.assertEqual('synced', self.status('whatsapp-images', 'Sent/same.jpg'))
        self.assertEqual('synced', self.status('whatsapp-videos', 'Sent/same.mp4'))
        self.assertEqual(409, self.put('whatsapp-images', 'Sent/same.jpg', b'changed'))
        self.assertEqual(201, self.put('whatsapp-images', 'Sent/same.jpg', b'changed', hashlib.sha256(b'synthetic bytes').hexdigest()))
        self.assertEqual(b'changed', (self.roots['whatsapp-images'] / 'Sent/same.jpg').read_bytes())

    def test_reject_unknown_traversal_symlinks_and_unavailable_storage(self):
        self.assertEqual(404, self.put('unknown', 'a.jpg'))
        for name in ('../a.jpg', '/a.jpg', 'Sent/../a.jpg', 'Sent//a.jpg', '.private/a.jpg'):
            self.assertEqual(400, self.put('whatsapp-images', name))
        (self.roots['whatsapp-images'] / 'link').symlink_to(self.roots['import'])
        self.assertEqual(503, self.put('whatsapp-images', 'link/a.jpg'))
        self.assertFalse((self.roots['import'] / 'a.jpg').exists())
        self.roots['whatsapp-videos'].rename(self.root / 'offline')
        self.assertEqual(503, self.request('whatsapp-videos', 'POST', '/v1/refresh', {})[0])
        self.assertEqual(503, self.request('camera', 'GET', '/health')[0])
        self.assertEqual(201, self.put('camera', 'still-available.jpg'))

    def test_video_larger_than_old_image_limit_streams_and_reconciles(self):
        data = b'synthetic-video-block' * (101 * 1024 * 1024 // len(b'synthetic-video-block') + 1)
        self.assertGreater(len(data), 100 * 1024 * 1024)
        self.assertEqual(201, self.put('whatsapp-videos', 'large.mp4', data))
        self.assertEqual('synced', self.status('whatsapp-videos', 'large.mp4', data))
        self.assertEqual(hashlib.sha256(data).hexdigest(), hashlib.sha256((self.roots['whatsapp-videos'] / 'large.mp4').read_bytes()).hexdigest())
