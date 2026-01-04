# PhotoSync

## Overview

An Android app for uploading photos to a PhotoPrism server via WebDAV. Browse local albums, select images, and sync them to your self-hosted instance.

## Setup

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
