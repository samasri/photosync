"""Camera backup: incremental inventory, content comparison, conditional flat uploads."""
import ctypes
import errno
import hashlib
import hmac
import json
import logging
import uuid
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit

LOG = logging.getLogger('photosync.camera')
LOG.addHandler(logging.NullHandler())


def event(name, **fields):
    # Callers provide fixed labels and numeric diagnostics only, never request data
    # or exception messages (which can contain credentials and filesystem paths).
    LOG.info(json.dumps({'time': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                         'event': name, **fields}, separators=(',', ':')))


def error_fields(error):
    fields = {'error_type': type(error).__name__}
    if isinstance(error, OSError):
        fields['errno'] = error.errno
    if isinstance(error, sqlite3.Error):
        fields['sqlite_code'] = getattr(error, 'sqlite_errorcode', None)
    return fields


HASH = re.compile(r"[0-9a-f]{64}")
MAX_BYTES = 100 * 1024 * 1024
IMAGE_SUFFIXES = {'.jpg', '.jpeg', '.png', '.heic', '.heif', '.webp', '.gif', '.dng', '.avif', '.bmp'}


def load_env(path):
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith('#'):
                key, value = line.split('=', 1)
                os.environ.setdefault(key.strip(), value.strip().strip('\"\''))


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def publish_new(source, target):
    """Publish a complete file while the caller holds the inventory writer lock."""
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'linux':
        rename = libc.renameat2
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        result = rename(-100, os.fsencode(source), -100, os.fsencode(target), 1)  # RENAME_NOREPLACE
    elif sys.platform == 'darwin':
        rename = libc.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(source), os.fsencode(target), 4)  # RENAME_EXCL
    else:
        os.link(source, target)
        os.unlink(source)
        return
    if result != 0:
        error = ctypes.get_errno()
        if error in (errno.ENOTSUP, errno.ENOSYS):
            # ExFAT through macOS virtiofs supports ordinary atomic rename but
            # neither hard links nor RENAME_NOREPLACE. Our writer lock protects
            # service uploads; unrelated tools must not write here concurrently.
            if os.path.lexists(target):
                raise FileExistsError(errno.EEXIST, os.strerror(errno.EEXIST), os.fspath(target))
            os.rename(source, target)
            return
        raise OSError(error, os.strerror(error), os.fspath(target))


def copy_paths(value):
    paths = json.loads(value)
    if not isinstance(paths, list) or any(not isinstance(p, str) or not p or not Path(p).is_absolute() for p in paths):
        raise ValueError('CAMERA_COPY_PATHS must be a JSON array of absolute directory paths')
    return paths


class Deliveries:
    """Persistent delivery receipts: import consumers may remove completed copies."""
    def __init__(self, inventory, paths):
        self.inventory = inventory
        self.roots = [Path(p).resolve() for p in paths]
        roots = [inventory.root, *self.roots]
        if len(set(roots)) != len(roots) or any(a in b.parents or b in a.parents for i, a in enumerate(roots) for b in roots[i + 1:]):
            raise ValueError('Storage directories must be distinct and non-nested')
        if any(not p.is_dir() for p in self.roots):
            raise ValueError('Copy destinations must already exist')
        self.identities = [(p.stat().st_dev, p.stat().st_ino) for p in self.roots]
        self.db = inventory.db
        self.db.execute('CREATE TABLE IF NOT EXISTS deliveries (name TEXT, hash TEXT, destination TEXT, previous_hash TEXT, done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(name,hash,destination))')
        self.db.commit()

    def available(self, index):
        stat = self.roots[index].stat()
        if (stat.st_dev, stat.st_ino) != self.identities[index]:
            raise OSError('Copy destination changed')

    def begin(self, name, sha, previous, reset=False):
        # Commit intent before publishing the primary. A crash cannot hide an
        # unfinished delivery behind a successful primary inventory comparison.
        if reset:
            self.db.execute('DELETE FROM deliveries WHERE name=? AND hash=?', (name, sha))
        self.db.executemany('INSERT OR IGNORE INTO deliveries (name,hash,destination,previous_hash) VALUES (?,?,?,?)',
                            ((name, sha, str(root), previous) for root in self.roots))
        self.db.commit()

    def pending(self, name, sha):
        return any(self.db.execute('SELECT 1 FROM deliveries WHERE name=? AND hash=? AND destination=? AND done=0',
                                  (name, sha, str(root))).fetchone() for root in self.roots)

    def deliver(self, name, sha, diagnostics):
        diagnostics['copy_destinations'] = len(self.roots)
        diagnostics['copies_completed'] = 0
        for index, root in enumerate(self.roots):
            row = self.db.execute('SELECT previous_hash,done FROM deliveries WHERE name=? AND hash=? AND destination=?',
                                  (name, sha, str(root))).fetchone()
            if row[1]:
                diagnostics['copies_completed'] += 1
                continue
            temporary = None
            try:
                self.available(index)
                target = root / name
                if target.is_symlink() or (target.exists() and not target.is_file()):
                    raise FileExistsError(errno.EEXIST, 'Copy destination conflict')
                current = digest_file(target) if target.exists() else None
                if current != sha:
                    # A confirmed replacement permits only the previous primary
                    # bytes here, never an unrelated file with the same name.
                    if current is not None and current != row[0]:
                        raise FileExistsError(errno.EEXIST, 'Copy destination conflict')
                    with tempfile.NamedTemporaryFile(dir=root, prefix='.incoming-', delete=False) as out:
                        temporary = Path(out.name)
                        with (self.inventory.root / name).open('rb') as source:
                            shutil.copyfileobj(source, out, 1024 * 1024)
                        # Importers often run as a different container user.
                        os.fchmod(out.fileno(), 0o644)
                        out.flush()
                        os.fsync(out.fileno())
                    if digest_file(temporary) != sha:
                        raise OSError(errno.EIO, 'Copy verification failed')
                    self.available(index)
                    if current is None:
                        publish_new(temporary, target)
                    else:
                        os.replace(temporary, target)
                    temporary = None
                directory = os.open(root, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
                self.db.execute('UPDATE deliveries SET done=1 WHERE name=? AND hash=? AND destination=?', (name, sha, str(root)))
                self.db.commit()
                diagnostics['copies_completed'] += 1
            except (OSError, sqlite3.Error):
                self.db.rollback()
                diagnostics['copy_destination'] = index + 1
                diagnostics['reason'] = 'copy_delivery_failed'
                raise
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)


class Inventory:
    def __init__(self, root, database, mount=None):
        self.root = root
        self.mount = Path(mount).resolve() if mount else None
        if self.mount and (not self.mount.is_mount() or not root.is_relative_to(self.mount)):
            raise ValueError("Backup mount is unavailable")
        self.identity = (root.stat().st_dev, root.stat().st_ino)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(database, check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, size INTEGER, modified INTEGER, changed INTEGER, hash TEXT)')
        self.db.execute('CREATE INDEX IF NOT EXISTS content_hash ON files(hash)')
        self.generation = 0

    def available(self):
        if self.mount and not self.mount.is_mount():
            raise OSError("Backup disk is unmounted")
        stat = self.root.stat()
        if (stat.st_dev, stat.st_ino) != self.identity:
            raise OSError('Backup disk changed')

    def refresh(self, full=False):
        with self.lock:
            started = time.monotonic()
            self.available()
            hashed = reused = hashed_bytes = 0
            previous = {row[0]: row[1:] for row in self.db.execute('SELECT path,size,modified,changed,hash FROM files')}
            seen = set()
            try:
                # Do not traverse symlinks or silently swallow directory access errors.
                def failed(error):
                    raise error
                for directory, dirs, files in os.walk(self.root, followlinks=False, onerror=failed):
                    dirs[:] = [name for name in dirs if not name.startswith('.') and not (Path(directory) / name).is_symlink()]
                    for name in files:
                        path = Path(directory) / name
                        if name.startswith('.') or path.suffix.lower() not in IMAGE_SUFFIXES or path.is_symlink():
                            continue
                        relative = path.relative_to(self.root).as_posix()
                        stat = path.stat()
                        signature = (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
                        old = previous.get(relative)
                        if not full and old and old[:3] == signature:
                            sha = old[3]
                            reused += 1
                        else:
                            sha = digest_file(path)
                            hashed += 1
                            hashed_bytes += stat.st_size
                            after = path.stat()
                            if signature != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                                raise OSError('File changed during indexing')
                        seen.add(relative)
                        self.db.execute('INSERT OR REPLACE INTO files VALUES (?,?,?,?,?)', (relative, *signature, sha))
                self.db.executemany('DELETE FROM files WHERE path=?', ((key,) for key in previous.keys() - seen))
                self.available()
                self.db.commit()
                self.generation += 1
            except Exception as error:
                self.db.rollback()
                event('index_failed', duration_ms=round((time.monotonic() - started) * 1000), **error_fields(error))
                raise
            event('index_refreshed', generation=self.generation, full=full, photos=len(seen),
                  hashed=hashed, reused=reused, hashed_bytes=hashed_bytes,
                  removed=len(previous.keys() - seen), duration_ms=round((time.monotonic() - started) * 1000))
            return self.generation

    def compare(self, items):
        with self.lock:
            self.available()
            results = []
            for item in items:
                name, sha = item['name'], item['sha256']
                if not valid_name(name) or not HASH.fullmatch(sha):
                    raise ValueError('Invalid image')
                same = self.db.execute('SELECT path FROM files WHERE hash=? LIMIT 1', (sha,)).fetchone()
                target = self.root / name
                existing = self.db.execute('SELECT hash FROM files WHERE path=?', (name,)).fetchone()
                if same:
                    status = 'missing' if getattr(self, 'deliveries', None) and self.deliveries.pending(name, sha) else 'synced'
                elif target.exists() or target.is_symlink():
                    status = 'conflict'
                else:
                    status = 'missing'
                results.append({'sha256': sha, 'name': name, 'status': status,
                                'serverHash': existing[0] if existing else None})
            return results


def valid_name(name):
    return (isinstance(name, str) and 0 < len(name.encode('utf-8')) <= 240
            and name not in {'.', '..'} and not name.startswith('.')
            and '/' not in name and '\\' not in name and not any(ord(c) < 32 for c in name))


def make_server(root, token, host='127.0.0.1', port=8787, database=None, mount=None, destinations=None):
    root = Path(root).resolve()
    if not token:
        raise ValueError('CAMERA_TOKEN is required')
    if not root.is_dir():
        raise ValueError('CAMERA_STORAGE_ROOT must be an existing directory')
    inventory = Inventory(root, database or root / '.photosync-index.sqlite3', mount)
    try:
        inventory.deliveries = Deliveries(inventory, destinations or [])
    except Exception:
        inventory.db.close()
        raise

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass  # Base class logs contain raw paths and untrusted request text.

        def handle_one_request(self):
            started = time.monotonic()
            self.request_id = uuid.uuid4().hex[:16]
            self.diagnostics = {}
            self.response_code = None
            try:
                super().handle_one_request()
            except Exception as error:
                self.diagnostics.update(error_fields(error))
                self.diagnostics['reason'] = 'request_failed'
                if self.response_code is None:
                    try:
                        self.respond(500)
                    except OSError:
                        pass
                self.close_connection = True
            finally:
                path = getattr(self, 'path', '')
                route = path if path in ('/health', '/v1/refresh', '/v1/check') else (
                    '/v1/files/:name' if path.startswith('/v1/files/') else
                    '/v1/objects/:hash' if path.startswith('/v1/objects/') else 'unknown')
                method = getattr(self, 'command', '')
                method = method if method in ('GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'PATCH') else 'unknown'
                # Healthy probes are deliberately quiet; failures always get a record.
                if self.response_code is not None and not (route == '/health' and self.response_code == 200 and not self.diagnostics):
                    event('request', request_id=self.request_id, method=method, route=route,
                          status=self.response_code, duration_ms=round((time.monotonic() - started) * 1000),
                          **self.diagnostics)

        def send_response(self, code, message=None):
            self.response_code = code
            super().send_response(code, message)
            self.send_header('X-Request-ID', self.request_id)

        def failure(self, code, reason, error=None, body=None):
            self.diagnostics['reason'] = reason
            if error is not None:
                self.diagnostics.update(error_fields(error))
            return self.respond(code, body)

        def respond(self, code, body=None):
            data = json.dumps(body or {}).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            if self.command != 'HEAD':
                self.wfile.write(data)

        def authorized(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.failure(401, 'unauthorized')
                return False
            try:
                inventory.available()
            except OSError as error:
                self.failure(503, 'storage_unavailable', error)
                return False
            self.connection.settimeout(60)
            return True

        def read_json(self):
            size = int(self.headers.get('Content-Length', '-1'))
            if self.headers.get('Transfer-Encoding') or not 0 < size <= 512 * 1024:
                raise ValueError('Invalid request size')
            return json.loads(self.rfile.read(size))

        def do_GET(self):
            if not self.authorized():
                return
            try:
                if self.path == '/health':
                    for index in range(len(inventory.deliveries.roots)):
                        inventory.deliveries.available(index)
                self.respond(200 if self.path == '/health' else 404)
            except OSError as error:
                self.failure(503, 'copy_storage_unavailable', error)

        def do_POST(self):
            if not self.authorized():
                return
            try:
                body = self.read_json()
                if self.path == '/v1/refresh':
                    self.respond(200, {'generation': inventory.refresh()})
                elif self.path == '/v1/check':
                    items = body['items']
                    if not isinstance(items, list) or len(items) > 500:
                        raise ValueError('Invalid batch')
                    with inventory.lock:
                        if body.get('generation') != inventory.generation or inventory.generation == 0:
                            return self.failure(409, 'stale_generation', body={'error': 'Refresh inventory first'})
                        results = inventory.compare(items)
                        self.diagnostics.update(batch_size=len(results), generation=inventory.generation,
                            **{status: sum(row['status'] == status for row in results) for status in ('synced', 'missing', 'conflict')})
                        self.respond(200, {'items': results})
                else:
                    self.respond(404)
            except (ValueError, KeyError, TypeError) as error:
                self.failure(400, 'invalid_request', error)
            except (OSError, sqlite3.Error) as error:
                self.failure(503, self.diagnostics.get('reason', 'storage_or_io_failure'), error)

        # Retain the synthetic prototype protocol for its isolation/regression tests.
        def object_target(self):
            parts = self.path.split('/')
            if len(parts) == 4 and parts[1:3] == ['v1', 'objects'] and HASH.fullmatch(parts[3]):
                return root / parts[3]
            return None

        def do_HEAD(self):
            if not self.authorized():
                return
            target = self.object_target()
            try:
                valid = target and target.is_file() and not target.is_symlink() and digest_file(target) == target.name
                self.respond(200 if valid else 404)
            except OSError as error:
                self.failure(503, 'storage_unavailable', error)

        def do_PUT(self):
            if not self.authorized():
                return
            legacy = self.object_target()
            name = unquote(urlsplit(self.path).path.removeprefix('/v1/files/'))
            sha = legacy.name if legacy else self.headers.get('X-Content-SHA256', '')
            if self.path.startswith('/v1/objects/') and legacy is None:
                return self.respond(404)
            if not legacy and (not self.path.startswith('/v1/files/') or not valid_name(name)
                               or Path(name).suffix.lower() not in IMAGE_SUFFIXES):
                return self.respond(400)
            if not HASH.fullmatch(sha):
                return self.respond(400)
            target = legacy or root / name
            temporary = None
            try:
                size = int(self.headers.get('Content-Length', '-1'))
                if self.headers.get('Transfer-Encoding') or not 0 < size <= MAX_BYTES:
                    return self.failure(413, 'invalid_upload_size')
                self.diagnostics['bytes'] = size
                with tempfile.NamedTemporaryFile(dir=root, prefix='.incoming-', delete=False) as out:
                    temporary = Path(out.name)
                    remaining, digest = size, hashlib.sha256()
                    while remaining:
                        data = self.rfile.read(min(remaining, 1024 * 1024))
                        if not data:
                            return self.failure(400, 'incomplete_upload')
                        out.write(data)
                        digest.update(data)
                        remaining -= len(data)
                    out.flush()
                    os.fsync(out.fileno())
                if digest.hexdigest() != sha:
                    return self.failure(422, 'content_hash_mismatch')
                with inventory.lock:
                    inventory.available()
                    if target.is_symlink() or (target.exists() and not target.is_file()):
                        return self.failure(409, 'destination_conflict')
                    current = digest_file(target) if target.exists() else None
                    if current == sha:
                        if not legacy:
                            inventory.deliveries.begin(name, sha, None)
                            inventory.deliveries.deliver(name, sha, self.diagnostics)
                            inventory.generation += 1
                        self.diagnostics['outcome'] = 'already_present'
                        return self.respond(201, {'sha256': sha})
                    if not legacy and current is not None and self.headers.get('X-Replace-SHA256') != current:
                        return self.failure(409, 'replacement_hash_required_or_changed', body={'error': 'Same filename, different contents', 'serverHash': current})
                    if not legacy and current is None and self.headers.get('X-Replace-SHA256'):
                        return self.failure(409, 'replacement_target_missing', body={'error': 'Server copy changed; check again'})
                    if not legacy:
                        inventory.deliveries.begin(name, sha, current, reset=True)
                    if current is None:
                        # Serialize uploads, preserving an existing destination.
                        try:
                            publish_new(temporary, target)
                        except FileExistsError:
                            return self.failure(409, 'destination_conflict')
                    else:
                        os.replace(temporary, target)
                    self.diagnostics['outcome'] = 'created' if current is None else 'replaced'
                    temporary = None  # A successful rename consumed the temporary path.
                    directory = os.open(root, os.O_RDONLY)
                    try:
                        os.fsync(directory)
                    finally:
                        os.close(directory)
                    if not legacy:
                        stat = target.stat()
                        inventory.db.execute('INSERT OR REPLACE INTO files VALUES (?,?,?,?,?)',
                            (name, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, sha))
                        inventory.db.commit()
                        inventory.generation += 1
                        inventory.deliveries.deliver(name, sha, self.diagnostics)
                self.respond(201, {'sha256': sha})
            except ValueError as error:
                self.failure(400, 'invalid_upload', error)
            except (OSError, sqlite3.Error) as error:
                self.failure(503, self.diagnostics.get('reason', 'storage_or_io_failure'), error)
            finally:
                if temporary is not None:
                    try:
                        temporary.unlink(missing_ok=True)
                    except OSError as error:
                        self.diagnostics.update(cleanup_failed=True, **error_fields(error))

    server = ThreadingHTTPServer((host, port), Handler)
    server.inventory = inventory
    return server


if __name__ == '__main__':
    load_env(Path(__file__).with_name('.env'))
    root = os.environ.get('CAMERA_STORAGE_ROOT')
    if not root:
        raise SystemExit('Set CAMERA_STORAGE_ROOT and CAMERA_TOKEN in services/camera/.env')
    server = make_server(root, os.environ.get('CAMERA_TOKEN'),
                         os.environ.get('CAMERA_HOST', '127.0.0.1'),
                         int(os.environ.get('CAMERA_PORT', '8787')),
                         os.environ.get('CAMERA_INDEX_PATH'), os.environ.get('CAMERA_MOUNT_ROOT'),
                         copy_paths(os.environ.get('CAMERA_COPY_PATHS', '[]')))
    logging.basicConfig(level=logging.INFO, format='%(message)s', stream=sys.stdout)
    event('server_ready', port=server.server_address[1])
    server.serve_forever()
