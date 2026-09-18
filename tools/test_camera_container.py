"""Exercise the built Camera image with generated PNGs and synthetic bind mounts.

Run after `docker compose build camera`: python3 tools/test_camera_container.py
Only its uniquely named test container and temporary files are removed.
"""
import hashlib
import http.client
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import uuid
import zlib


def docker(*args):
    return subprocess.check_output(['docker', *args], stderr=subprocess.STDOUT).decode().strip()


def png(color):
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 1, 1, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(b'\x00' + bytes(color))) + chunk(b'IEND', b''))


def main():
    artifacts = Path(__file__).resolve().parents[1] / 'test-artifacts'
    artifacts.mkdir(exist_ok=True)
    name = 'photosync-camera-e2e-' + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix='camera-destinations-', dir=artifacts) as temp:
        root = Path(temp)
        archive, imports, state = [root / part for part in ('archive', 'import', 'state')]
        for folder in (archive, imports, state):
            folder.mkdir()
        images, videos = root / 'whatsapp-images', root / 'whatsapp-videos'
        images.mkdir(); videos.mkdir()
        started = False
        try:
            docker('run', '-d', '--name', name, '--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL',
                   '--security-opt', 'no-new-privileges:true', '-p', '127.0.0.1::8787',
                   '--mount', f'type=bind,src={archive},dst=/photos',
                   '--mount', f'type=bind,src={imports},dst=/imports',
                   '--mount', f'type=bind,src={state},dst=/state',
                   '--mount', f'type=bind,src={images},dst=/whatsapp-images',
                   '--mount', f'type=bind,src={videos},dst=/whatsapp-videos',
                   '-e', 'WHATSAPP_IMAGES_STORAGE_ROOT=/whatsapp-images',
                   '-e', 'WHATSAPP_IMAGES_MOUNT_ROOT=/whatsapp-images',
                   '-e', 'WHATSAPP_IMAGES_INDEX_PATH=/state/whatsapp-images.sqlite3',
                   '-e', 'WHATSAPP_VIDEOS_STORAGE_ROOT=/whatsapp-videos',
                   '-e', 'WHATSAPP_VIDEOS_MOUNT_ROOT=/whatsapp-videos',
                   '-e', 'WHATSAPP_VIDEOS_INDEX_PATH=/state/whatsapp-videos.sqlite3',
                   '-e', 'CAMERA_STORAGE_ROOT=/photos', '-e', 'CAMERA_MOUNT_ROOT=/photos',
                   '-e', 'CAMERA_INDEX_PATH=/state/inventory.sqlite3', '-e', 'PHOTOPRISM_IMPORT_ROOT=/imports', '-e', 'PHOTOPRISM_RECEIPTS_PATH=/state/import.sqlite3',
                   '-e', 'CAMERA_HOST=0.0.0.0', '-e', 'CAMERA_TOKEN=synthetic-e2e-token', 'photosync-camera')
            started = True
            def request(method, path, body=None, headers=None):
                port = int(docker('port', name, '8787/tcp').rsplit(':', 1)[1])
                connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
                connection.request(method, path, body, {'Authorization': 'Bearer synthetic-e2e-token', **(headers or {})})
                response = connection.getresponse()
                result = response.status, json.loads(response.read())
                connection.close()
                return result
            def ready():
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    try:
                        if request('GET', '/health')[0] == 200:
                            return
                    except (OSError, http.client.HTTPException):
                        pass
                    time.sleep(.2)
                raise AssertionError('Synthetic container did not become healthy')
            def put(filename, data, previous=None):
                headers = {'X-Content-SHA256': hashlib.sha256(data).hexdigest()}
                if previous:
                    headers['X-Replace-SHA256'] = hashlib.sha256(previous).hexdigest()
                return request('PUT', '/v1/files/' + filename, data, headers)[0]
            def status(filename, data):
                code, refreshed = request('POST', '/v1/refresh', '{}')
                assert code == 200
                code, checked = request('POST', '/v1/check', json.dumps({'generation': refreshed['generation'],
                    'items': [{'name': filename, 'sha256': hashlib.sha256(data).hexdigest()}]}))
                assert code == 200
                return checked['items'][0]['status']
            ready()
            for collection, folder, filename, data in (
                ('whatsapp-images', images, 'Sent/synthetic.png', png((1, 2, 3))),
                ('whatsapp-videos', videos, 'Sent/synthetic.mp4', b'synthetic video bytes')):
                headers = {'X-Backup-Collection': collection}
                code, refreshed = request('POST', '/v1/refresh', '{}', headers)
                assert code == 200 and refreshed['collection'] == collection
                assert request('PUT', '/v1/files/' + filename, data,
                    {**headers, 'X-Content-SHA256': hashlib.sha256(data).hexdigest()})[0] == 201
                assert (folder / filename).read_bytes() == data
                assert not (archive / filename).exists() and not (imports / filename).exists()
                assert request('PUT', '/v1/files/../escape.png', data,
                    {**headers, 'X-Content-SHA256': hashlib.sha256(data).hexdigest()})[0] == 400
            first, second = png((20, 80, 140)), png((140, 80, 20))
            assert status('synthetic.png', first) == 'missing'
            assert put('synthetic.png', first) == 201
            assert (archive / 'synthetic.png').read_bytes() == first
            assert not (imports / 'synthetic.png').exists()
            assert status('synthetic.png', first) == 'synced'
            assert put('synthetic.png', second) == 409
            assert put('synthetic.png', second, first) == 201
            assert (archive / 'synthetic.png').read_bytes() == second
            def deliver(filename, data):
                return request('PUT', '/v1/import/' + filename, data,
                    {'X-Content-SHA256': hashlib.sha256(data).hexdigest()})[0]
            assert deliver('delivery.png', first) == 201
            assert (imports / 'delivery.png').read_bytes() == first
            assert not (archive / 'delivery.png').exists()
            assert (imports / 'delivery.png').stat().st_mode & 0o777 == 0o644
            assert deliver('delivery.png', second) == 409
            (imports / 'delivery.png').unlink()
            docker('restart', name)
            ready()
            assert deliver('delivery.png', first) == 201
            assert not (imports / 'delivery.png').exists()
            assert deliver('delivery.png', second) == 201
            assert (imports / 'delivery.png').read_bytes() == second
            assert deliver('synthetic.mp4', b'synthetic video') == 201
            for folder in (archive, imports):
                assert not list(folder.glob('.incoming-*'))
            logs = docker('logs', name)
            assert 'photoprism' in logs and 'backup' in logs
            for private in ('synthetic-e2e-token', 'synthetic.png', 'delivery.png', str(root)):
                assert private not in logs
            print('PASS: independent archive/import workflows, nested collections, conditional replacement,')
            print('video delivery, consumed imports, persistent retry receipts, cleanup and safe logs.')
        finally:
            if started:
                docker('rm', '-f', name)


if __name__ == '__main__':
    main()
