# Camera service

A Python standard-library API compares photo contents with an existing archive and
accepts conditional uploads. The phone sets its endpoint/token; only the server sets
the filesystem destinations. The archive is the primary copy; optional additional
directories can feed an importer such as PhotoPrism. No PhotoPrism API is needed.

## Deployment

From the repository root:

1. Copy `.env.example` to `.env`. Set `CAMERA_ARCHIVE_PATH` to an existing archive,
   `CAMERA_IMPORT_PATH` to the existing PhotoPrism import directory,
   `CAMERA_COPY_PATHS=["/photoprism-import"]`, `CAMERA_TOKEN` to a random secret,
   and `CAMERA_PUBLIC_URL` to the intended HTTPS
   endpoint (a setup reference; Compose does not register DNS/routes).
2. Create `services/camera/state/`. On macOS, share the archive disk read/write with
   your Docker VM, preserving its existing shares.
3. Ensure the gateway's external network `internal-proxy` exists.
4. Run `docker compose up -d --build`.
5. Configure the gateway's HTTPS route to `photosync-camera:8787`. Enter the public
   URL/token in the app's **Settings → Camera backup**.

No host port is published. The archive mounts at `/photos`, and the private index at
`/state/inventory.sqlite3`. Missing source paths are not created. The authenticated
health check verifies primary and copy-directory availability without scanning.
`CAMERA_COPY_PATHS` is a JSON array of **container paths**; add matching bind mounts
to Compose for more destinations. The primary archive remains configured separately.
Use `[]` to disable additional copies. For direct Python deployment, use host paths.
Directories must exist, be distinct and not contain one another.

If network DNS runs inside the VM, record host DNS settings and temporarily switch
to an external resolver before restarting it. Verify DNS and other services recover,
restore original DNS and flush caches. On macOS, router-provided DNS is restored with
`networksetup -setdnsservers <network-service> Empty`; flush with
`sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder`.

For direct development, use Python 3.9+, copy this directory's `.env.example` to `.env`,
and configure an existing storage root, local index path, token and optional mount
root (a mounted ancestor). Run `python3 services/camera/server.py`. Production uses
root Compose instead; HTTPS belongs at the gateway.

## Diagnostics

```sh
docker compose logs --since 1h camera
docker compose logs -f camera
docker compose ps
```

JSON lines go to stdout. Docker rotates at 10 MB per file, keeping three files per
container. These are local diagnostics, not a durable audit trail. Healthy probes
are quiet; failed probes and requests are logged.

| Event / fields | Meaning |
| --- | --- |
| `server_ready` | Process started listening. |
| `request` | Generated `request_id`, method, route template, HTTP status and total `duration_ms` including lock waits. ID also returned as `X-Request-ID`. |
| Check totals | `batch_size`, `generation`, `synced`, `missing`, `conflict`; sum batches for a complete phone comparison. |
| Upload details | Declared `bytes`, primary outcome (`created`, `replaced`, `already_present`), `copy_destinations`, `copies_completed` or fixed failure reason. |
| Copy failure | `copy_delivery_failed`, one-based `copy_destination` position in the configured array, and OS/SQLite error code. No directory paths are logged. |
| `index_refreshed` | Photo count, hashes computed/reused, bytes hashed, removed entries, generation and duration. |
| `index_failed` | Scan failure and duration; transaction rolls back. |
| Error details | Fixed `reason`, exception class, OS `errno` or SQLite code where available. |

Tokens, headers, client addresses, names, content hashes, bodies, raw URLs and
exception messages are omitted. Unknown routes log as `unknown`. IDs identify
requests, not photos.

- **401 / unauthorized:** tokens do not match.
- **409 / stale_generation:** another refresh/upload changed inventory; check again.
  Upload 409s instead explain a destination or replacement conflict.
- **422 / content_hash_mismatch:** uploaded bytes differ from the client's hash.
- **503 / storage_unavailable:** missing/replaced root or disk. Other 503s include
  filesystem/SQLite codes; OS errno 28, for example, means no free space.
- **503 / copy_delivery_failed:** the primary may already be saved, but an additional
  destination failed. Restore access/space or resolve its filename conflict, then
  check and retry from the app. An unrelated same-name file is never intentionally
  overwritten. `copy_storage_unavailable` on health checks identifies unavailable
  additional storage.
- **Many conflicts:** check original-photo permission/cache migration before replacing
  files. Redacted EXIF previously caused false conflicts. Logs show totals; exact
  differences require a private comparison of original bytes.

## Comparison and write guarantees

First scans hash image contents with SHA-256. Later scans reuse hashes when size,
modification time and change time match. Subdirectories are indexed recursively;
identical contents anywhere count as synced. Uploads retain original names directly
in the archive root. Phone deletion never deletes server files. Videos, hidden files
and symlinks are excluded.

| Endpoint | Contract |
| --- | --- |
| `GET /health` | Authentication and storage availability only. |
| `POST /v1/refresh` | Refresh index; return `generation`. |
| `POST /v1/check` | Accept generation and up to 500 items with `name`/`sha256`; return status (`synced`, `missing`, `conflict`) and `serverHash`. |
| `PUT /v1/files/{encoded-name}` | Require `X-Content-SHA256`, known Content-Length, at most 100 MiB. Stream, verify, fsync and publish complete bytes. |

All endpoints require `Authorization: Bearer <token>`. Replacement additionally
requires `X-Replace-SHA256` matching current server bytes and overwrites the prior
version. The app asks for confirmation; bulk/automatic uploads skip conflicts.
Keep decisions remain local to the phone.

### Multiple destinations and retries

Uploads return **201 only after every configured copy has been delivered**. Copies
use hidden temporary files, checksum verification, fsync and atomic publication.
Additional copies use mode `0644` so a separate importer user can read them.
There is no atomic transaction across disks: a 503 can leave the primary and some
copies complete. SQLite records pending deliveries before primary publication and
receipts after each copy. A pending delivery makes the phone comparison `missing`
even when the primary exists, so **Check now**, then **Upload all**, can retry it.
Completed destinations are skipped on retry, including after a restart. There is no
independent server retry worker.

Import tools may move/delete delivered files. Receipts remember delivery; the service
does not keep repopulating an import folder or verify that PhotoPrism processed it.
A crash between copy publication and receipt persistence can cause redelivery if an
importer already consumed the file. This is at-least-once delivery, not exactly-once.
Back up the private SQLite index because it now holds delivery receipts too.

A confirmed replacement updates additional copies only if their bytes match the
previous primary version (or already match the new version). Other contents cause
503 and require resolving the destination conflict before retrying. Successful
primary writes are retained on copy failure. Additional destinations apply to
received uploads; configuring a new one does **not** backfill the existing archive.
Removing a destination from configuration stops requiring its pending deliveries.

Cached checks are not full integrity scans. Stop the service and run
`python3 services/camera/index_archive.py --full` with the direct environment pointing
to the same archive/index to reread every image. The index contains private filenames.
The running service detects root/device replacement.

The inventory lock serializes service writes. Filesystems without exclusive rename
or hard links (some ExFAT/virtiofs mounts) use ordinary atomic rename after checking
the destination. Files appear fully written, but unrelated concurrent writers can
still be overwritten. Do not run other archive writers during uploads; conditional
replacement also relies on a process-local lock.

## Tests

Run `python3 -m unittest discover -s services/camera -v`. Tests use temporary synthetic
archives and loopback servers, including diagnostic/privacy assertions. See
[Android tests](../../apps/android/README.md#updates-and-tests) for client coverage.
For an actual container end-to-end check, run `docker compose build camera`, then
`python3 tools/test_camera_container.py` from the repository root. It generates PNGs
and mounts only temporary synthetic archive/import/state directories. It verifies
HTTP comparison/upload, both copies, replacement, restart/retry, consumed imports,
and log privacy, then removes its test container and directories.

Legacy `/v1/objects/{hash}`, `run_lab.py` and `lab_webdav.py` remain only for prototype
regression; production uses `/v1/files/`.
