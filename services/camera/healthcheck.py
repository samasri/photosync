"""Read-only authenticated health check; never hashes or uploads photos."""
import os
import urllib.request

request = urllib.request.Request(
    'http://127.0.0.1:' + os.environ.get('CAMERA_PORT', '8787') + '/health',
    headers={'Authorization': 'Bearer ' + os.environ['CAMERA_TOKEN']})
with urllib.request.urlopen(request, timeout=3) as response:
    if response.status != 200:
        raise SystemExit(1)
