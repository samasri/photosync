# PhotoSync

## Overview

An Android app for uploading photos to a PhotoPrism server via WebDAV. Browse local albums, select images, and sync them to your self-hosted instance.

Note that the app includes special handling for the "WhatsApp Images" album, parsing dates directly from WhatsApp's filename format (IMG-YYYYMMDD-WA####.ext) rather than relying on file creation dates or EXIF metadata, which are often unreliable for forwarded images. The default server URL is set to `https://photoprism.example.com/import/` in the code, which you'll want to change to your own server address in the settings.

## Setup

```bash
nix develop # Install dependencies with nix

./gradlew :app:assembleDebug # Build debug APK

# Install on device

# For wireless debugging: enable "Wireless debugging" in Developer Options, then pair once with:
# adb pair <IP>:<PORT> <PAIRING_CODE>

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First Use

- In the app, go to settings (top right wrench icon) and enter your PhotoPrism (or any WebDAV) server url & credentials
- Click "Save Settings"
- Then you can go to any photo album and click on the pictures you'd want to sync

## WhatsApp photo swipe and zoom

Open **Photo swipe** from Albums. It shows the oldest unreviewed photo from albums
named **WhatsApp Images**, using WhatsApp filename dates when available.

- Swipe right or tap **Upload** to save a private copy in the upload queue and
  attempt an upload in the background. You can continue reviewing while offline.
- Swipe left or tap **Ignore** to save a decision without uploading or deleting
  the original. **Undo last ignore** reverses the last ignore in this session.
- Pinch to zoom (1–5×) and pan. Double-tap to zoom or reset. Multi-finger gestures
  never count as review swipes; reset zoom before swiping to make a decision.
- Double-tap a grid photo to open the viewer, which supports the same zoom controls.
- Green grid checkmarks appear only after a successful upload. Both views use the
  original upload-history database; queued photos are not marked uploaded.

On first use, photos already present before **January 1, 2026** are marked ignored
once. Later imports with older dates still appear, and individual decisions keep
photos sharing the same date from being skipped. Settings shows the starting date,
next photo date, and progress at the last review, all read only.

## Pending uploads

Both grid selections and right swipes use a durable Room queue and private photo
copies in app storage. Once queued, uploads can succeed even if the source image
is moved/deleted or photo permission is revoked. Successful uploads remove the
private copy. Failures remain visible with their last error and attempt count in
**Settings → Pending uploads**, with a **Retry pending uploads now** button.

WorkManager attempts new uploads when connected and retries pending uploads about
every hour. Android may delay background work for battery/network constraints; see
[Android's periodic-work behavior](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).
There are no server requests when the queue is empty. Retries use current saved
server settings. Stable filenames and serialized retries avoid duplicate submissions
within the app; a crash after the remote PUT may repeat that PUT to the same path.

The queue has its own database (`upload_queue.db`), leaving the original
`photoprism_uploader.db` schema and upload records unchanged. App-private copies
are retained until uploaded; do not clear app storage or uninstall while uploads
are pending. The implementation follows Tally Android's persistent-outbox pattern.

Password visibility defaults to hidden, with a show/hide button in Settings. It
returns to hidden when leaving the app or saving settings. **Password reveal is
intentionally excluded from automated testing and must be checked manually.**

## Update the original app without losing history

```bash
nix develop --command ./gradlew :app:assembleDebug
adb install -r /absolute/path/to/photosync/app/build/outputs/apk/debug/app-debug.apk
```

The APK must have the same signing certificate and application ID as the installed
app (`com.photoprism.uploader`). Never uninstall or clear the original to update it.
Lab upload and review records are not imported into the original app.

## Isolated device tests

The `experiment` variant remains available for future safe testing as **PhotoSync
Lab** (`com.photoprism.uploader.experiment`), with independent settings and data.
It does not need to remain installed on a daily-use phone.

```bash
nix develop --command ./gradlew :app:assembleExperiment :app:assembleExperimentAndroidTest :app:lintDebug
adb install -r /absolute/path/to/photosync/app/build/outputs/apk/experiment/app-experiment.apk
adb install -r /absolute/path/to/photosync/app/build/outputs/apk/androidTest/experiment/app-experiment-androidTest.apk
adb shell am instrument -w com.photoprism.uploader.experiment.test/androidx.test.runner.AndroidJUnitRunner
```

Tests use synthetic photos and an on-device HTTP server. They cover review/undo,
January boundaries, multi-touch safety, queue deduplication, failed uploads,
worker retries after source deletion, cleanup, and green checkmarks. They never
upload test photos to the real PhotoPrism server or reveal a saved password.
