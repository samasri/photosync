# Deferred Issues

## Handle Android partial photo-library access

The main app targets Android 14 or newer but currently treats photo access as a
simple granted/denied permission. Android 14 can grant access to only a selected
subset of photos. In that state, album counts, duplicate detection, and upload
choices may be incomplete without the app explaining why.

Follow-up work:

- Declare and handle `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+.
- Distinguish full, partial, and denied access in application state.
- Provide a user-initiated way to change the selected-photo grant.
- Make scans and album screens clearly report when their view is partial.
- Continue supporting `READ_MEDIA_IMAGES` on Android 13+ and
  `READ_EXTERNAL_STORAGE` through Android 12L.

References:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/photoprism/uploader/ui/albums/AlbumsScreen.kt`
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access

## Remove unsafe cleartext credential transport

The main app permits cleartext HTTP globally and sends WebDAV credentials with
HTTP Basic authentication. When the configured endpoint uses HTTP, credentials
and uploaded photo data can be observed or modified by other systems on the
network. The password is also stored as an ordinary DataStore preference.

Follow-up work:

- Require HTTPS by default and remove the global cleartext exception.
- If local HTTP must remain available for development, scope it narrowly and
  show an explicit warning instead of enabling it for every host.
- Prefer a revocable PhotoPrism app password or access token over an account
  password where supported.
- Store secrets using an Android Keystore-backed design and exclude them from
  backups.
- Ensure logs and error messages never include credentials or authorization
  headers.

References:

- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/java/com/photoprism/uploader/data/webdav/WebDavUploader.kt`
- `app/src/main/java/com/photoprism/uploader/data/local/settings/SettingsDataStore.kt`
