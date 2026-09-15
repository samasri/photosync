import hashlib
import errno
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import quote
from server import make_server, digest_file, publish_new


def sha(data):
    return hashlib.sha256(data).hexdigest()


class InventoryTest(unittest.TestCase):
    def test_diagnostics_explain_conflicts_and_never_log_private_data(self):
        name = 'private-photo-name.jpg'
        (self.root / name).write_bytes(b'server-private-bytes')
        with self.assertLogs('photosync.camera', level='INFO') as captured:
            self.assertEqual('conflict', self.check(name, b'phone-private-bytes')['status'])
            self.assertEqual(409, self.put(name, b'phone-private-bytes'))
            code, _ = self.request('PUT', '/v1/files/' + name, b'bad', {'X-Content-SHA256': sha(b'good')})
            self.assertEqual(422, code)
            with patch.object(self.server.inventory, 'available', side_effect=OSError(errno.EIO, 'private-path-and-secret')):
                self.assertEqual(503, self.post('/v1/refresh', {})[0])
            deadline = time.monotonic() + 2
            while len([r for r in captured.records if json.loads(r.getMessage())['event'] == 'request']) < 5 and time.monotonic() < deadline:
                time.sleep(.01)
        events = [json.loads(r.getMessage()) for r in captured.records]
        requests = [e for e in events if e['event'] == 'request']
        self.assertEqual(5, len(requests))
        self.assertEqual(1, next(e for e in requests if e['route'] == '/v1/check')['conflict'])
        self.assertEqual({'replacement_hash_required_or_changed', 'content_hash_mismatch', 'storage_unavailable'},
                         {e['reason'] for e in requests if 'reason' in e})
        self.assertEqual(errno.EIO, requests[-1]['errno'])
        self.assertTrue(all('duration_ms' in e and len(e['request_id']) == 16 for e in requests))
        refresh = next(e for e in events if e['event'] == 'index_refreshed')
        self.assertEqual((1, 0), (refresh['hashed'], refresh['reused']))
        serialized = json.dumps(events)
        for private in (name, 'test-token', 'phone-private-bytes', 'private-path-and-secret', str(self.root), sha(b'good')):
            self.assertNotIn(private, serialized)

    def test_health_success_is_quiet_and_auth_failure_has_request_id(self):
        with self.assertLogs('photosync.camera', level='INFO') as captured:
            self.assertEqual(200, self.request('GET', '/health', None)[0])
            connection = http.client.HTTPConnection(*self.server.server_address, timeout=10)
            connection.request('GET', '/health', headers={'Authorization': 'Bearer private-bad-token'})
            response = connection.getresponse()
            self.assertEqual(401, response.status)
            request_id = response.getheader('X-Request-ID')
            response.read()
            connection.close()
            deadline = time.monotonic() + 2
            while not captured.records and time.monotonic() < deadline:
                time.sleep(.01)
        self.assertEqual(1, len(captured.records))
        record = json.loads(captured.records[0].getMessage())
        self.assertEqual(request_id, record['request_id'])
        self.assertEqual('unauthorized', record['reason'])
        self.assertNotIn('private-bad-token', captured.records[0].getMessage())

    def test_atomic_publish_preserves_existing_file(self):
        source, target = self.root / '.incoming-test', self.root / 'racing.jpg'
        source.write_bytes(b'phone')
        target.write_bytes(b'other-writer')
        with self.assertRaises(FileExistsError):
            publish_new(source, target)
        self.assertEqual(b'other-writer', target.read_bytes())
        self.assertEqual(b'phone', source.read_bytes())
        target.unlink()
        publish_new(source, target)
        self.assertEqual(b'phone', target.read_bytes())

    def test_unsupported_exclusive_rename_uses_locked_atomic_fallback(self):
        source, target = self.root / '.incoming-test', self.root / 'existing.jpg'
        source.write_bytes(b'phone')
        target.write_bytes(b'server')
        with patch('server.ctypes.CDLL') as library, patch('server.ctypes.get_errno', return_value=errno.ENOTSUP):
            library.return_value.renameat2.return_value = -1
            library.return_value.renamex_np.return_value = -1
            with self.assertRaises(FileExistsError):
                publish_new(source, target)
            self.assertEqual(b'server', target.read_bytes())
            target.unlink()
            publish_new(source, target)
            self.assertEqual(b'phone', target.read_bytes())
            self.assertFalse(source.exists())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.server = make_server(self.root, 'test-token', port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.server.inventory.db.close()
        self.temp.cleanup()

    def request(self, method, path, body, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=10)
        connection.request(method, path, body=body, headers={'Authorization': 'Bearer test-token', **(headers or {})})
        response = connection.getresponse()
        code, result = response.status, json.loads(response.read())
        connection.close()
        return code, result

    def post(self, path, body):
        return self.request('POST', path, json.dumps(body))

    def check(self, name, data):
        code, refresh = self.post('/v1/refresh', {})
        self.assertEqual(200, code)
        code, result = self.post('/v1/check', {'generation': refresh['generation'], 'items': [{'name': name, 'sha256': sha(data)}]})
        self.assertEqual(200, code)
        return result['items'][0]

    def put(self, name, data, replace=None):
        headers = {'X-Content-SHA256': sha(data)}
        if replace: headers['X-Replace-SHA256'] = replace
        return self.request('PUT', '/v1/files/' + quote(name, safe=''), data, headers)[0]

    def test_index_existing_archive_and_incremental_refresh(self):
        (self.root / 'old').mkdir()
        (self.root / 'old' / 'existing.jpg').write_bytes(b'existing')
        with patch('server.digest_file', wraps=digest_file) as hashed:
            self.assertEqual('synced', self.check('different.jpg', b'existing')['status'])
            self.assertEqual(1, hashed.call_count)
            self.assertEqual('synced', self.check('different.jpg', b'existing')['status'])
            self.assertEqual(1, hashed.call_count)
            (self.root / 'old' / 'existing.jpg').write_bytes(b'changed')
            self.assertEqual('missing', self.check('different.jpg', b'existing')['status'])
            self.assertEqual(2, hashed.call_count)
        (self.root / 'old' / 'existing.jpg').unlink()
        self.assertEqual('missing', self.check('different.jpg', b'changed')['status'])

    def test_flat_names_and_conditional_conflict_replacement(self):
        name = 'IMG space + unicode é.jpg'
        self.assertEqual(201, self.put(name, b'original'))
        self.assertEqual(b'original', (self.root / name).read_bytes())
        self.assertEqual('synced', self.check(name, b'original')['status'])
        conflict = self.check(name, b'new')
        self.assertEqual('conflict', conflict['status'])
        self.assertEqual(409, self.put(name, b'new'))
        self.assertEqual(409, self.put(name, b'new', sha(b'wrong')))
        self.assertEqual(b'original', (self.root / name).read_bytes())
        self.assertEqual(201, self.put(name, b'new', conflict['serverHash']))
        self.assertEqual(b'new', (self.root / name).read_bytes())
        self.assertEqual(201, self.put(name, b'new'))

    def test_changed_server_copy_requires_new_confirmation(self):
        self.put('same.jpg', b'first')
        conflict = self.check('same.jpg', b'phone')
        (self.root / 'same.jpg').write_bytes(b'second')
        self.assertEqual(409, self.put('same.jpg', b'phone', conflict['serverHash']))
        self.assertEqual(b'second', (self.root / 'same.jpg').read_bytes())

    def test_check_is_read_only_and_batches_are_bounded(self):
        self.assertEqual('missing', self.check('new.jpg', b'new')['status'])
        self.assertFalse((self.root / 'new.jpg').exists())
        code, result = self.post('/v1/refresh', {})
        items = [{'name': f'{i}.jpg', 'sha256': sha(b'test')} for i in range(500)]
        code, result = self.post('/v1/check', {'generation': result['generation'], 'items': items})
        self.assertEqual(200, code)
        self.assertEqual(500, len(result['items']))
        code, _ = self.post('/v1/check', {'items': items + items})
        self.assertEqual(400, code)

    def test_path_traversal_symlink_and_corrupt_body_are_rejected(self):
        self.assertEqual(400, self.put('../escape.jpg', b'data'))
        (self.root / 'target.jpg').write_bytes(b'safe')
        (self.root / 'link.jpg').symlink_to(self.root / 'target.jpg')
        self.assertEqual(409, self.put('link.jpg', b'danger', sha(b'safe')))
        code, _ = self.request('PUT', '/v1/files/bad.jpg', b'bad', {'X-Content-SHA256': sha(b'good')})
        self.assertEqual(422, code)
        self.assertFalse((self.root / 'bad.jpg').exists())
        self.assertEqual(b'safe', (self.root / 'target.jpg').read_bytes())

    def test_disk_identity_and_stale_generation(self):
        code, result = self.post('/v1/refresh', {})
        generation = result['generation']
        self.put('new.jpg', b'new')
        code, _ = self.post('/v1/check', {'generation': generation, 'items': []})
        self.assertEqual(409, code)
        self.server.inventory.identity = (-1, -1)
        self.assertEqual(503, self.post('/v1/refresh', {})[0])
        self.assertEqual(503, self.put('another.jpg', b'new'))

    def test_persistent_index_reuses_fingerprints(self):
        (self.root / 'existing.jpg').write_bytes(b'existing')
        self.check('existing.jpg', b'existing')
        from server import Inventory
        second = Inventory(self.root, self.root / '.photosync-index.sqlite3')
        try:
            with patch('server.digest_file', side_effect=AssertionError('unchanged file rehashed')):
                second.refresh()
            self.assertEqual('synced', second.compare([{'name': 'existing.jpg', 'sha256': sha(b'existing')}])[0]['status'])
        finally:
            second.db.close()
