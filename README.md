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
