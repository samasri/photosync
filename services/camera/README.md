# Camera service

A Python standard-library API compares photo contents with an existing archive and
accepts conditional uploads. The phone sets its endpoint/token; only the server sets
the filesystem destination. This service is independent of PhotoPrism.

## Deployment

From the repository root:

1. Copy `.env.example` to `.env`. Set `CAMERA_ARCHIVE_PATH` to an existing archive,
   `CAMERA_TOKEN` to a random secret, and `CAMERA_PUBLIC_URL` to the intended HTTPS
   endpoint (a setup reference; Compose does not register DNS/routes).
2. Create `services/camera/state/`. On macOS, share the archive disk read/write with
   your Docker VM, preserving its existing shares.
3. Ensure the gateway's external network `internal-proxy` exists.
4. Run `docker compose up -d --build`.
5. Configure the gateway's HTTPS route to `photosync-camera:8787`. Enter the public
   URL/token in the app's **Settings → Camera backup**.

No host port is published. The archive mounts at `/photos`, and the private index at
`/state/inventory.sqlite3`. Missing source paths are not created. The authenticated
health check verifies storage availability without scanning.

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
| Upload details | Declared `bytes`, outcome (`created`, `replaced`, `already_present`) or fixed failure reason. |
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
Legacy `/v1/objects/{hash}`, `run_lab.py` and `lab_webdav.py` remain only for prototype
regression; production uses `/v1/files/`.
