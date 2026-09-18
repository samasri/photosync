# PhotoSync service

Two independent workflows share one authenticated server:

- **Backup** compares phone media with persistent archive folders and uploads missing files.
- **PhotoPrism** delivers explicitly selected media to an import directory. The app records
  successful deliveries because PhotoPrism may move files out of that directory.

An archive upload never writes to the import directory, and an import delivery never
writes to an archive. No PhotoPrism API is needed; delivery does not confirm ingestion.

## Deployment

From the repository root:

1. Copy `.env.example` to `.env`. Configure four existing host directories:
   `CAMERA_ARCHIVE_PATH`, `WHATSAPP_IMAGES_ARCHIVE_PATH`,
   `WHATSAPP_VIDEOS_ARCHIVE_PATH`, and `PHOTOPRISM_IMPORT_PATH`.
   Set `CAMERA_TOKEN` to a random secret. `CAMERA_PUBLIC_URL` is a setup reference;
   Compose does not register DNS or gateway routes.
2. Create `services/camera/state/`. Share the archive and import directories read/write
   with the Docker VM, if applicable. Directories must be distinct and non-nested.
3. Ensure the gateway's external network `internal-proxy` exists.
4. Run `docker compose up -d --build`.
5. Route HTTPS to `photosync-camera:8787`. Enter the base URL and token in the app's
   **Settings → Backup** and **Settings → PhotoPrism**. The settings remain independent.

Compose publishes no host port. Missing bind sources are not created. Private SQLite
state lives under `/state`; back it up along with app state. Health checks verify all
configured directories without scanning media. One unavailable destination does not
redirect writes or prevent requests to another available destination.

When upgrading from Camera multi-destination delivery, rename `CAMERA_IMPORT_PATH`
to `PHOTOPRISM_IMPORT_PATH` and remove `CAMERA_COPY_PATHS`. Archive indexes and old
receipt tables remain intact; old Camera delivery receipts are no longer consulted.
No existing media is moved or delivered automatically by this migration.

For direct Python deployment, use Python 3.9+, this directory's `.env.example`, and
`python3 services/camera/server.py`. Configure `PHOTOPRISM_IMPORT_ROOT` and a separate
`PHOTOPRISM_RECEIPTS_PATH`. Optional mount roots must be mounted ancestors of their
archive. Production HTTPS belongs at the gateway.

If DNS runs inside the Docker VM, preserve host DNS settings before restarting it.
Temporarily use an external resolver, verify recovery, then restore the original
settings and flush caches. No VM restart is needed for an ordinary service update.

## Protocol and comparison

All endpoints require `Authorization: Bearer <token>`.

| Endpoint | Contract |
| --- | --- |
| `GET /health` | Authentication and destination availability. |
| `POST /v1/refresh` | Refresh archive index; return generation and collection. |
| `POST /v1/check` | Compare up to 500 name/SHA-256 pairs at that generation; return synced, missing, or conflict and server hash. |
| `PUT /v1/files/{relative-path}` | Archive upload; require Content-Length and `X-Content-SHA256`. |
| `PUT /v1/import/{filename}` | Independent delivery; require Content-Length and `X-Content-SHA256`. Images and videos accepted, flat filenames only. |

Backup requests select `camera`, `whatsapp-images`, or `whatsapp-videos` with
`X-Backup-Collection`. Omission selects Camera for older clients. Unknown or
unconfigured collections return 404. Each archive has its own index, generation and
writer lock. Import requests always select the import directory, regardless of this header.

The first archive scan hashes contents. Later scans reuse hashes when size, modification
time and change time match. Camera recognizes identical contents anywhere in its
archive and uploads flat filenames. WhatsApp requires matching contents at the same
relative path, preserving folders such as `Sent`. Phone deletions never delete server files.
Hidden files and symlinks are excluded; traversal and symlink upload ancestors are rejected.

Different bytes at an existing archive path require `X-Replace-SHA256` matching the
current bytes. The app asks for confirmation; bulk/automatic uploads skip conflicts.
Replacement overwrites the prior version. Keep decisions remain local to the phone.

Cached checks are not full integrity scans. Stop the service and run
`python3 services/camera/index_archive.py --full` with the direct environment pointing
at the same Camera archive/index to reread every image. The index contains filenames.
Root/device replacement is detected.

### Import delivery and retries

The app retains its existing PhotoPrism upload-history database and durable queue.
Green checks mean successful delivery, not current import-folder presence. Legacy
PhotoPrism/WebDAV history remains valid; archive checkmarks are never converted to
delivery history. Users select Camera media explicitly, even if it was archived before.

The server streams into a hidden temporary file, verifies the hash, fsyncs, publishes
with mode `0644`, then commits a receipt keyed by filename and hash. A retry after a
completed delivery returns 201 even if PhotoPrism consumed the file. Receipts live in
`photoprism-receipts.sqlite3`, separately from archive inventories. They suppress
transport duplicates; the app remains the source of its displayed upload history.

An existing same-name file with different bytes returns 409 and is never overwritten
by import requests, even with a replacement header. Resolve the destination conflict
and retry the app queue. Identical existing bytes are accepted. A crash between
publication and receipt persistence can cause redelivery if the importer consumed
that file: delivery is at least once, not exactly once. There is no server retry worker.

Archive images accept up to 100 MiB; archive videos and import deliveries accept up
to 4 GiB. Android allows a 60-minute request with a 10-minute write timeout. Server
sockets time out after 60 seconds without progress. Configure proxies for the intended
size and duration. An interrupted retry starts the file again; uploads are not resumable.

Writer locks serialize service writes. On filesystems without exclusive rename/hard
links, a checked ordinary rename is used. External writers must not concurrently
replace archive files or directories. Import consumers may remove completed files.

## Diagnostics

```sh
docker compose logs --since 1h camera
docker compose logs -f camera
docker compose ps
```

JSON logs include request ID, route template, status, duration, workflow, archive
collection, check totals, byte count, upload outcome and fixed failure reason.
`X-Request-ID` correlates responses. Outcomes include `created`, `replaced`,
`already_present`, and `already_delivered`. Index events report hashes computed/reused,
bytes hashed and elapsed time. Healthy probes are quiet. Docker rotates three 10 MB logs.

Logs omit credentials, headers, client addresses, filenames, hashes, raw URLs,
request bodies and exception messages. Errors expose exception class and OS/SQLite
codes only. Logs are diagnostics, not a durable upload audit.

- **401:** check the token.
- **409:** stale archive generation or a filename/replacement conflict.
- **422:** bytes do not match the supplied hash.
- **503:** unavailable storage, full disk, filesystem or database failure. Check the
  fixed reason and OS/SQLite code; errno 28 indicates no free space.
- **Many archive conflicts:** check original-media permission before replacing files;
  Android's EXIF redaction can produce different bytes.

## Tests and Android boundaries

Run `python3 -m unittest discover -s services/camera -q`. After building the image,
run `python3 tools/test_camera_container.py`. Tests use synthetic bytes, temporary
archives/import directories and loopback. Container coverage checks isolation,
conditional archive replacement, consumed imports, retry receipts, video delivery and logs.

See [Android maintenance](../../apps/android/README.md) for device tests. Backup uses
cached comparisons on configured intervals; **Check now** forces a comparison and never
uploads. Each collection has its own automatic-upload toggle, off by default.
Only Android MediaStore-visible files are included; hidden/unindexed folders are not
a full filesystem rsync replacement.

Legacy `/v1/objects/{hash}`, `run_lab.py` and `lab_webdav.py` support prototype regression
only. Production uses `/v1/files/` and `/v1/import/`.
