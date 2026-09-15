"""Index existing photos without copying, uploading or modifying them."""
import argparse
import os
from pathlib import Path
import time
from server import Inventory, load_env

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--full', action='store_true', help='Re-read every file for an integrity check')
    args = parser.parse_args()
    load_env(Path(__file__).with_name('.env'))
    root = Path(os.environ['CAMERA_STORAGE_ROOT']).resolve()
    index = Inventory(root, os.environ['CAMERA_INDEX_PATH'], os.environ.get('CAMERA_MOUNT_ROOT'))
    started = time.monotonic()
    try:
        index.refresh(full=args.full)
        count, size = index.db.execute('SELECT count(*),coalesce(sum(size),0) FROM files').fetchone()
        print(f'Indexed {count} photos ({size / 1024**3:.2f} GiB) in {time.monotonic() - started:.1f}s')
    finally:
        index.db.close()
