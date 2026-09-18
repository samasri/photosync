"""Independent import deliveries use synthetic bytes and temporary directories."""
import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from server import make_server


class ImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / 'archive'; self.archive.mkdir()
        self.imports = self.root / 'import'; self.imports.mkdir()
        self.start()

    def start(self):
        self.server = make_server(self.archive, 'synthetic', port=0,
            database=self.root / 'archive.db', import_root=self.imports,
            import_database=self.root / 'receipts.db')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.server.inventory.db.close(); self.server.import_inventory.db.close()

    def tearDown(self):
        self.stop(); self.temp.cleanup()

    def request(self, path, data=b'synthetic image', headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address)
        values = {'Authorization': 'Bearer synthetic', 'X-Content-SHA256': hashlib.sha256(data).hexdigest()}
        values.update(headers or {})
        connection.request('PUT', path, body=data, headers=values)
        response = connection.getresponse(); code = response.status; response.read(); connection.close()
        return code

    def test_workflow_isolation_and_consumed_receipt_survives_restart(self):
        self.assertEqual(201, self.request('/v1/files/archive.jpg'))
        self.assertFalse((self.imports / 'archive.jpg').exists())
        self.assertEqual(201, self.request('/v1/import/delivery.jpg'))
        self.assertFalse((self.archive / 'delivery.jpg').exists())
        self.assertEqual(b'synthetic image', (self.imports / 'delivery.jpg').read_bytes())
        (self.imports / 'delivery.jpg').unlink()
        self.stop(); self.start()
        self.assertEqual(201, self.request('/v1/import/delivery.jpg'))
        self.assertFalse((self.imports / 'delivery.jpg').exists())
        self.assertEqual(201, self.request('/v1/import/delivery.jpg', b'new version'))
        self.assertEqual(b'new version', (self.imports / 'delivery.jpg').read_bytes())

    def test_conflict_does_not_overwrite_and_receipt_only_after_success(self):
        (self.imports / 'conflict.jpg').write_bytes(b'existing')
        self.assertEqual(409, self.request('/v1/import/conflict.jpg'))
        self.assertEqual(b'existing', (self.imports / 'conflict.jpg').read_bytes())
        self.assertEqual(409, self.request('/v1/import/conflict.jpg', headers={'X-Replace-SHA256': hashlib.sha256(b'existing').hexdigest()}))
        self.assertEqual(0, self.server.import_inventory.db.execute('SELECT COUNT(*) FROM import_receipts').fetchone()[0])
        (self.imports / 'conflict.jpg').unlink()
        self.assertEqual(201, self.request('/v1/import/conflict.jpg'))

    def test_invalid_path_hash_auth_and_symlink(self):
        for path in ('../outside.jpg', 'nested/photo.jpg', '%2e%2e%2foutside.jpg', '.hidden.jpg', 'script.exe'):
            self.assertEqual(400, self.request('/v1/import/' + path))
        self.assertEqual(401, self.request('/v1/import/a.jpg', headers={'Authorization': 'Bearer wrong'}))
        self.assertEqual(422, self.request('/v1/import/a.jpg', headers={'X-Content-SHA256': '0' * 64}))
        (self.imports / 'link.jpg').symlink_to(self.archive / 'absent.jpg')
        self.assertEqual(409, self.request('/v1/import/link.jpg'))
        self.assertFalse(list(self.imports.glob('.incoming-*')))

    def test_unavailable_import_does_not_block_archive(self):
        self.imports.rename(self.root / 'offline')
        self.assertEqual(503, self.request('/v1/import/a.jpg'))
        self.assertEqual(201, self.request('/v1/files/a.jpg'))

    def test_storage_failure_retry_and_safe_logs(self):
        with self.assertLogs('photosync.camera', level='INFO') as logs:
            with patch('server.publish_new', side_effect=OSError('private path and filename')):
                self.assertEqual(503, self.request('/v1/import/private.jpg'))
            self.assertEqual(201, self.request('/v1/import/private.jpg'))
        output = '\n'.join(logs.output)
        self.assertNotIn('private.jpg', output)
        self.assertNotIn('private path', output)
        self.assertIn('"workflow":"photoprism"', output)
        self.assertFalse(list(self.imports.glob('.incoming-*')))

    def test_video_stream_and_existing_identical_file(self):
        data = b'synthetic video' * (101 * 1024 * 1024 // 15 + 1)
        self.assertEqual(201, self.request('/v1/import/video.mp4', data))
        self.assertEqual(hashlib.sha256(data).hexdigest(), hashlib.sha256((self.imports / 'video.mp4').read_bytes()).hexdigest())
        self.assertEqual(201, self.request('/v1/import/video.mp4', data))
        self.assertEqual(0o644, (self.imports / 'video.mp4').stat().st_mode & 0o777)

    def test_roots_must_be_separate(self):
        for destination in (self.archive, self.root, self.root / 'absent'):
            with self.assertRaises((ValueError, OSError)):
                make_server(self.archive, 'synthetic', port=0, database=':memory:', import_root=destination, import_database=':memory:')
