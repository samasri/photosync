"""Run both isolated test receivers. All received bytes go under a fresh tmp folder."""
from pathlib import Path
import tempfile
import threading
from lab_webdav import make_lab_webdav
from server import make_server

root = Path(tempfile.mkdtemp(prefix='photosync-camera-lab-'))
camera = root / 'camera'
camera.mkdir()
webdav = make_lab_webdav(root / 'whatsapp')
threading.Thread(target=webdav.serve_forever, daemon=True).start()
print(f'Synthetic test storage: {root}', flush=True)
print('Camera: localhost:8787; WhatsApp: localhost:8788', flush=True)
make_server(camera, 'synthetic-lab-token').serve_forever()
