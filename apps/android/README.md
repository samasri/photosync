# Android maintenance

Build from repo root; this directory is still Gradle module `:app`. Use JDK 17,
Android SDK 35 and Build Tools 35.0.0, or `nix develop`. Source packages follow
screen/data responsibilities; `di/AppModule.kt` and `ui/navigation/AppNavigation.kt`
provide the wiring.

## State boundaries

WhatsApp owns its DataStore settings, Room history, durable queue and swipe state.
Camera owns `camera-backup` preferences, `camera-index.db` and the
`camera-backup-periodic` WorkManager job. Keep them independent: WhatsApp checkmarks
reflect local upload history; Camera reflects the last complete server comparison.

Camera checks on screen resume, manually and periodically. Only periodic checks
with automatic mode enabled upload afterward. Automatic mode defaults off.
Network errors retain prior results; changing servers invalidates comparison status.

Both Camera hashing and uploading must use `MediaStore.setRequireOriginal` with
runtime `ACCESS_MEDIA_LOCATION` and photo-read permission. Ordinary MediaStore
streams can redact EXIF and cause false conflicts against identical archive files.
Never fall back to redacted bytes; denial stops Camera operations.

Fingerprints cache MediaStore identity/version, size, dates and generation. The
`fingerprint-version` preference invalidates hashes, statuses and conflict decisions
when byte interpretation changes. Credentials scope comparison status separately.
See [Android media documentation](https://developer.android.com/training/data-storage/shared/media).

## WhatsApp grid

The WhatsApp tab queries matching MediaStore buckets directly. It reads lightweight
metadata once, then parses dates, sorts and groups on a background dispatcher. The
grid exposes 90 photos at a time and prefetches the next batch near the end; Coil
loads thumbnails only for composed tiles. Selection and uploads use the full metadata
index, so **Select All** includes photos beyond the visible batches. The date scrubber
maps the full index to grid positions (including day headers), exposes metadata
through the target, then waits for layout before scrolling. It never decodes all
the photos between the old and new positions.

## Updates and tests

Before updating, back up the installed APK and private state. For a debuggable app,
force-stop it, save `adb exec-out run-as com.photoprism.uploader tar -cf - .` to an
ignored private archive, and verify databases, preferences and DataStore files are
present. Use `adb shell pm path com.photoprism.uploader` to locate and pull its APK.
Install with the same signing key/application ID and `adb install -r`; never uninstall
the main app as an update step. Verify state preservation before changing settings.

The `experiment` APK installs separately as `com.photoprism.uploader.cameralab`.
It uses private synthetic files, removes media permissions, and limits Camera servers
to loopback. Never point test receivers at a personal archive.

```sh
nix develop --command ./gradlew :app:assembleDebug :app:assembleExperiment :app:assembleExperimentAndroidTest :app:lintDebug
adb install -r apps/android/build/outputs/apk/experiment/app-experiment.apk
adb install -r apps/android/build/outputs/apk/androidTest/experiment/app-experiment-androidTest.apk
adb shell am instrument -w com.photoprism.uploader.cameralab.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall com.photoprism.uploader.cameralab.test
adb uninstall com.photoprism.uploader.cameralab
```

Use the experiment app for upload tests; its fixtures and loopback receivers keep
tests independent of a device’s photo library and backup servers.

## Reproduce the product screenshots

Use stock photography and synthetic records in the isolated app; keep photographer
credits in the main README. Generated geometric fixtures remain suitable for regular
tests. `ProductScreenshotsTest` is skipped unless explicitly enabled.

After building/installing the experiment APKs as above, run from repo root:

```sh
python3 tools/screenshots.py prepare
python3 tools/screenshots.py seed
python3 tools/screenshots.py serve
```

Leave that synthetic server running. In a second terminal:

```sh
adb shell am instrument -w -e screenshots true -e class com.photoprism.uploader.lab.ProductScreenshotsTest com.photoprism.uploader.cameralab.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/screenshots.py pull
adb reverse --remove tcp:8791
adb uninstall com.photoprism.uploader.cameralab.test
adb uninstall com.photoprism.uploader.cameralab
```

Stop the synthetic server with Ctrl-C. The helper downloads Picsum IDs 10–21, stores
credits in ignored `test-artifacts/product-screenshots/stock/credits.json`, and seeds
12 images per album. The real Camera API serves the seven older photos from an isolated
archive, giving five pending. WhatsApp history marks the six older photos as synced; two newer photos are selected
without uploading. Captures include the app content only, excluding system UI.
Review images and update the README credit mapping before publishing new captures.
