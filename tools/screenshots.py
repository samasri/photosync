"""Stock-photo fixtures for opt-in ProductScreenshotsTest; never touches the main app."""
import argparse
import concurrent.futures
import io
import json
import logging
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request

REPO = Path(__file__).resolve().parents[1]
WORK = REPO / 'test-artifacts' / 'product-screenshots'
PACKAGE = 'com.photoprism.uploader.cameralab'
IDS = range(10, 22)


def prepare():
    stock = WORK / 'stock'
    stock.mkdir(parents=True, exist_ok=True)

    def fetch(number):
        def read(url):
            with urllib.request.urlopen(url, timeout=45) as response:
                return response.read()
        info = json.loads(read(f'https://picsum.photos/id/{number}/info'))
        (stock / f'{number}.jpg').write_bytes(read(f'https://picsum.photos/id/{number}/720/960'))
        return {'id': number, 'author': info['author'], 'source': info['url']}

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        credits = list(pool.map(fetch, IDS))
    (stock / 'credits.json').write_text(json.dumps(credits, indent=2))
    print('Stock photos and photographer credits downloaded.')


def seed():
    # Only this fixed isolated package receives fixtures. No MediaStore writes.
    subprocess.run(['adb', 'shell', 'am', 'force-stop', PACKAGE], check=True)
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w') as archive:
        marker = tarfile.TarInfo('files/synthetic-library/.seeded')
        marker.size = 1
        archive.addfile(marker, io.BytesIO(b'1'))
        for index, number in enumerate(IDS, 1):
            for bucket, name in [('camera', f'CAMERA-{index:02}.jpg'),
                                 ('whatsapp', f'IMG-202609{12 if index <= 6 else 1:02}-WA{index:04}.jpg')]:
                info = archive.gettarinfo(str(WORK / 'stock' / f'{number}.jpg'),
                            arcname=f'files/synthetic-library/{bucket}/{name}')
                # Newest fixtures first; the server contains only the older seven.
                info.mtime = 1789257600 - index * 86400
                with (WORK / 'stock' / f'{number}.jpg').open('rb') as source:
                    archive.addfile(info, source)
    subprocess.run(['adb', 'shell', 'run-as', PACKAGE, 'rm', '-rf', 'files/synthetic-library'], check=True)
    subprocess.run(['adb', 'exec-in', 'run-as', PACKAGE, 'tar', '-xf', '-'], input=data.getvalue(), check=True)
    subprocess.run(['adb', 'reverse', 'tcp:8791', 'tcp:8791'], check=True)
    print('Seeded isolated app with 12 photos per album.')


def serve():
    sys.path.insert(0, str(REPO / 'services' / 'camera'))
    from server import make_server
    archive = WORK / 'server-archive'
    archive.mkdir(parents=True, exist_ok=True)
    # Dedicated synthetic archive; resetting it guarantees exactly five pending.
    for path in archive.iterdir():
        if path.is_file():
            path.unlink()
    for index, number in enumerate(list(IDS)[5:], 6):
        shutil.copyfile(WORK / 'stock' / f'{number}.jpg', archive / f'CAMERA-{index:02}.jpg')
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    server = make_server(archive, 'synthetic-screenshots', port=8791, database=WORK / 'inventory.sqlite3')
    print('Synthetic Camera server listening on loopback port 8791.', flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        server.inventory.db.close()


def pull():
    destination = REPO / 'docs' / 'screenshots'
    destination.mkdir(parents=True, exist_ok=True)
    for name in ('albums', 'whatsapp', 'camera', 'backup'):
        data = subprocess.check_output(['adb', 'exec-out', 'run-as', PACKAGE, 'cat', f'files/screenshots/{name}.png'])
        if not data.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError('Screenshot is not a PNG')
        (destination / f'{name}.png').write_bytes(data)
    print('Saved app-only screenshots; review them before committing.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'seed', 'serve', 'pull'))
    globals()[parser.parse_args().action]()
