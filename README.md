# PhotoSync

Browse your Android photo library and back up chosen photos to your own servers.
**Albums** is browse-only; **WhatsApp** offers selection and swipe review;
**Camera** compares your camera roll with an existing archive.
Each backup workflow has independent settings, credentials and state.

## Features

<details>
<summary><strong>Browse your pictures</strong></summary>

Explore the phone's photo albums with cover images and counts. Open an album,
then tap a photo to view it and pinch or double-tap to zoom. Albums is browse-only;
use the WhatsApp or Camera tab to upload.

<img src="docs/screenshots/albums.png" width="360" alt="Albums tab showing WhatsApp and Camera albums" />

<details>
<summary>Photo credits</summary>

Photos from [Unsplash](https://unsplash.com) via [Lorem Picsum](https://picsum.photos),
under the [Unsplash License](https://unsplash.com/license).

- Camera cover: [Paul Jarvis](https://unsplash.com/photos/6J--NXulQCs).
- WhatsApp cover: [Paul Jarvis](https://unsplash.com/photos/gkT4FfgHO5o).

</details>

</details>

<details>
<summary><strong>Selectively back up WhatsApp images</strong></summary>

Tap photos to select them, then choose **Sync now**. Green checkmarks show upload
history; selected photos have a separate highlight. Double-tap to view and zoom.
Photos load progressively as you scroll. In larger libraries, drag the right-edge
scrollbar to jump through photos by month and year.

**Photo swipe** lets you review pictures individually: swipe right to queue an
upload, left to ignore without deleting, and undo your last ignore. Progress persists.
On first review, existing photos dated before January 1, 2026 are skipped; older
photos imported later still appear and the grid allows manual selection at any time.

Queued photos are saved privately and retried when connected, roughly hourly subject
to Android scheduling. **Settings → WhatsApp backup** shows pending uploads and retry
controls. Avoid clearing app storage while uploads are pending.

<img src="docs/screenshots/whatsapp.png" width="360" alt="WhatsApp tab with older photos synced, two newer photos selected for upload, and the newest unselected" />

<details>
<summary>Photo credits</summary>

Photos from [Unsplash](https://unsplash.com) via [Lorem Picsum](https://picsum.photos),
under the [Unsplash License](https://unsplash.com/license).

Left to right, top to bottom:

1. [Paul Jarvis](https://unsplash.com/photos/6J--NXulQCs)
2. [Paul Jarvis](https://unsplash.com/photos/Cm7oKel-X2Q)
3. [Paul Jarvis](https://unsplash.com/photos/I_9ILwtsl_k)
4. [Paul Jarvis](https://unsplash.com/photos/3MtiSMdnoCo)
5. [Paul Jarvis](https://unsplash.com/photos/IQ1kOQTJrOQ)
6. [Paul Jarvis](https://unsplash.com/photos/NYDo21ssGao)
7. [Paul Jarvis](https://unsplash.com/photos/gkT4FfgHO5o)
8. [Paul Jarvis](https://unsplash.com/photos/Ven2CV8IJ5A)
9. [Paul Jarvis](https://unsplash.com/photos/Ps2n0rShqaM)
10. [Paul Jarvis](https://unsplash.com/photos/P7Lh0usGcuk)
11. [Aleks Dorohovich](https://unsplash.com/photos/nJdwUHmaY8A)
12. [Alejandro Escamilla](https://unsplash.com/photos/jVb0mSn0LbE)

</details>

</details>

<details>
<summary><strong>Back up your Camera roll</strong></summary>

See images in `DCIM/Camera/`, synced checkmarks, and pending filenames. Upload
individually or choose **Upload all**. Videos are not included.

- Automatic upload is **off by default**. Opening Camera and **Check now** only compare.
- Scheduled checks default to hourly; they upload only when automatic mode is enabled.
- Original bytes and filenames are preserved in a flat server folder. Original-photo
  metadata permission is required for accurate comparisons.
- Identical contents anywhere in the archive count as backed up. Same-name files
  with different contents require **Keep** or confirmed **Replace**; bulk/automatic
  uploads skip conflicts. Replacement overwrites the old server copy.
- Unreachable servers retain the last known status. Phone deletion never deletes a backup.

<img src="docs/screenshots/camera.png" width="360" alt="Camera tab with All photos selected, seven synced photos and five pending uploads" />

<details>
<summary>Photo credits</summary>

Photos from [Unsplash](https://unsplash.com) via [Lorem Picsum](https://picsum.photos),
under the [Unsplash License](https://unsplash.com/license).

Left to right, top to bottom:

1. [Paul Jarvis](https://unsplash.com/photos/6J--NXulQCs)
2. [Paul Jarvis](https://unsplash.com/photos/Cm7oKel-X2Q)
3. [Paul Jarvis](https://unsplash.com/photos/I_9ILwtsl_k)
4. [Paul Jarvis](https://unsplash.com/photos/3MtiSMdnoCo)
5. [Paul Jarvis](https://unsplash.com/photos/IQ1kOQTJrOQ)
6. [Paul Jarvis](https://unsplash.com/photos/NYDo21ssGao)
7. [Paul Jarvis](https://unsplash.com/photos/gkT4FfgHO5o)
8. [Paul Jarvis](https://unsplash.com/photos/Ven2CV8IJ5A)
9. [Paul Jarvis](https://unsplash.com/photos/Ps2n0rShqaM)
10. [Paul Jarvis](https://unsplash.com/photos/P7Lh0usGcuk)
11. [Aleks Dorohovich](https://unsplash.com/photos/nJdwUHmaY8A)
12. [Alejandro Escamilla](https://unsplash.com/photos/jVb0mSn0LbE)

</details>

</details>

## Build and get started

Use JDK 17, Android SDK 35 and Build Tools 35.0.0, or the included Nix environment:

```sh
nix develop --command ./gradlew :app:assembleDebug :app:lintDebug
adb install -r apps/android/build/outputs/apk/debug/app-debug.apk
```

Back up an existing installation before updating. Keep its signing key and app ID,
and use `install -r` to preserve state.

In the **Settings** tab, configure **WhatsApp backup** with a PhotoPrism/WebDAV upload URL
and login. For **Camera backup**, [deploy the Camera service](services/camera/README.md#deployment)
using the root `.env.example` and Compose file, then enter its HTTPS URL and token.

## Development

Android lives in `apps/android/` (Gradle module `:app`), and the Python Camera API
in `services/camera/`. Root Compose runs the API behind an HTTPS gateway.

- [Android maintenance and tests](apps/android/README.md)
- [Camera deployment, logs and protocol](services/camera/README.md)
- [Known limitations](issues.md)
