# PhotoPrism Uploader - Codebase Guide

A guide for senior engineers new to Kotlin/Android.

---

## Quick Kotlin Primer (for TS/Go/Java/C devs)

| Kotlin | Equivalent | Notes |
|--------|------------|-------|

| `val x = 5` | `const x = 5` (TS) | Immutable |
| `var x = 5` | `let x = 5` (TS) | Mutable |
| `fun foo(): String` | `func foo() string` (Go) | Function declaration |
| `data class Foo(val x: Int)` | `record Foo(int x)` (Java 16+) | Auto-generates equals/hashCode/toString |
| `foo?.bar` | `foo?.bar` (TS) | Null-safe access |
| `foo ?: default` | `foo ?? default` (TS) | Elvis operator (null coalescing) |
| `listOf(1,2,3)` | `[]int{1,2,3}` (Go) | Immutable list |
| `mutableListOf()` | `make([]int, 0)` (Go) | Mutable list |
| `it` | Implicit lambda param | Like `_` in some languages |
| `suspend fun` | `async fn` | Coroutine (async function) |
| `object Foo {}` | Singleton | Single instance, like Go package-level vars |
| `companion object` | `static` (Java) | Static members inside a class |
| `by lazy { }` | Lazy initialization | Computed once on first access |

### Coroutines (Kotlin's async/await)

```kotlin
// Like async/await in TS or goroutines in Go
viewModelScope.launch {           // Start coroutine (like "go func()")
    val result = someAsyncCall()  // Suspends here, doesn't block thread
    updateUi(result)
}

// Flow = reactive stream (like RxJS Observable or Go channel)
val flow: Flow<Int> = flowOf(1, 2, 3)
flow.collect { value -> println(value) }  // Subscribe to emissions
```

---

## Project Structure

```tree
app/src/main/java/com/photoprism/uploader/
├── PhotoPrismApp.kt          # Application entry point (holds DI container)
├── MainActivity.kt           # Single Activity host (Android's "main window")
├── di/
│   └── AppModule.kt          # Dependency injection container (manual, no framework)
├── data/                     # Data layer (external systems)
│   ├── local/
│   │   ├── db/               # Room (SQLite ORM)
│   │   └── settings/         # DataStore (key-value persistence)
│   ├── mediastore/           # Android MediaStore queries (photo library)
│   └── webdav/               # HTTP upload logic
├── domain/                   # Business logic layer
│   ├── model/                # Data classes (DTOs)
│   └── sync/                 # Upload orchestration
└── ui/                       # Presentation layer (Jetpack Compose)
    ├── albums/               # Screen A: album list
    ├── grid/                 # Screen B: image grid with selection
    ├── settings/             # Settings screen
    ├── sync/                 # Sync progress dialog
    ├── navigation/           # Navigation graph
    └── theme/                # Material Design theme
```

---

## Android Concepts You Need to Know

### 1. Activity & Compose

**Activity** = A "screen" or window. This app has ONE activity (`MainActivity`).

**Jetpack Compose** = Declarative UI framework (like React/SwiftUI).

```kotlin
// MainActivity.kt
setContent {                    // Set the UI tree
    PhotoPrismUploaderTheme {   // Apply theme (like CSS provider)
        AppNavigation(...)      // Root composable
    }
}
```

Composables are functions annotated with `@Composable`. They describe UI:

```kotlin
@Composable
fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(model = album.coverUri, ...)  // Loads image async
        Text(album.name)
    }
}
```

### 2. ViewModel

ViewModels survive configuration changes (screen rotation). They hold UI state:

```kotlin
class AlbumsViewModel(...) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumsUiState())  // Private mutable
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()  // Public read-only

    fun loadAlbums() {
        viewModelScope.launch {  // Coroutine tied to ViewModel lifecycle
            _uiState.value = _uiState.value.copy(isLoading = true)
            val albums = repository.loadAlbums()
            _uiState.value = _uiState.value.copy(albums = albums, isLoading = false)
        }
    }
}
```

In Compose, you observe state:

```kotlin
val uiState by viewModel.uiState.collectAsState()  // Re-renders on change
```

### 3. MediaStore

Android's content provider for media files. You DON'T access files directly - you query a database:

```kotlin
// Query all images grouped by album
contentResolver.query(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,  // "Table" to query
    arrayOf(_ID, BUCKET_ID, BUCKET_DISPLAY_NAME),  // Columns (SELECT)
    null,                                           // WHERE clause
    null,                                           // WHERE args
    "DATE_ADDED DESC"                               // ORDER BY
)?.use { cursor ->
    while (cursor.moveToNext()) {
        val id = cursor.getLong(idColumn)
        // Build content URI: content://media/external/images/media/12345
        val uri = ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id)
    }
}
```

**Key columns:**

- `_ID` - unique identifier
- `BUCKET_ID` / `BUCKET_DISPLAY_NAME` - album grouping
- `DISPLAY_NAME` - filename
- `SIZE` - file size in bytes
- `DATE_ADDED` - timestamp

### 4. Content URIs

Instead of file paths (`/storage/emulated/0/DCIM/...`), Android uses URIs:

```path
content://media/external/images/media/12345
```

To read file contents:

```kotlin
contentResolver.openInputStream(uri)?.use { stream ->
    // Read bytes from stream
}
```

### 5. Room (SQLite ORM)

```kotlin
// Entity = table
@Entity(tableName = "uploaded_items")
data class UploadedItemEntity(
    @PrimaryKey val key: String,
    val mediaStoreId: Long,
    ...
)

// DAO = Data Access Object (queries)
@Dao
interface UploadedItemsDao {
    @Query("SELECT * FROM uploaded_items WHERE key = :key")
    suspend fun getByKey(key: String): UploadedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: UploadedItemEntity)
}

// Database = ties it together
@Database(entities = [UploadedItemEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadedItemsDao(): UploadedItemsDao
}
```

### 6. DataStore (Key-Value Storage)

Like SharedPreferences but with Flow support:

```kotlin
private val Context.dataStore by preferencesDataStore(name = "settings")

// Read (returns Flow)
val settings: Flow<ServerSettings> = context.dataStore.data.map { prefs ->
    ServerSettings(
        baseUrl = prefs[BASE_URL_KEY] ?: "default"
    )
}

// Write
context.dataStore.edit { prefs ->
    prefs[BASE_URL_KEY] = "http://..."
}
```

### 7. Permissions

Declared in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

Requested at runtime:

```kotlin
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) loadAlbums()
}
launcher.launch(Manifest.permission.READ_MEDIA_IMAGES)
```

---

## Data Flow

```diagram
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Screen    │────▶│  ViewModel  │────▶│ Repository  │
│  (Compose)  │◀────│  (StateFlow)│◀────│ (Data ops)  │
└─────────────┘     └─────────────┘     └─────────────┘
     UI                State              Data Source
   observes           manages             fetches from
   state              async ops           MediaStore/Room/Network
```

---

## Key Files & Their Purpose

| File | Purpose |
|------|---------|

| `AppModule.kt` | Creates all dependencies (poor man's DI) |
| `MediaStoreAlbumRepository.kt` | Queries albums from MediaStore |
| `MediaStoreImageRepository.kt` | Queries images for a specific album |
| `UploadedItemsDao.kt` | Room queries for tracking uploads |
| `SettingsDataStore.kt` | Persists server URL/credentials |
| `WebDavUploader.kt` | OkHttp PUT request to WebDAV |
| `FileNameResolver.kt` | Generates collision-safe filenames |
| `SyncOrchestrator.kt` | Coordinates upload batch, emits progress |
| `AlbumsScreen.kt` | UI: list of albums |
| `AlbumGridScreen.kt` | UI: image grid with selection |
| `AppNavigation.kt` | Navigation routes between screens |

---

## Common Patterns in This Codebase

### 1. UI State Pattern

```kotlin
data class AlbumsUiState(
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
```

Single state object, immutable, updated via `.copy()`.

### 2. Factory Pattern for ViewModels

```kotlin
class Factory(private val repo: Repo) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AlbumsViewModel(repo) as T
    }
}
```

Required because ViewModels have special lifecycle - can't just `new` them.

### 3. Sealed Classes for Results

```kotlin
sealed class UploadResult {
    data object Success : UploadResult()
    data class Failure(val error: String) : UploadResult()
}

// Usage
when (result) {
    is UploadResult.Success -> { ... }
    is UploadResult.Failure -> { showError(result.error) }
}
```

Like discriminated unions in TS or sum types in Rust.

---

## Build & Run

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Output location
app/build/outputs/apk/debug/app-debug.apk

# Install via wireless ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Gotchas

1. **Cleartext HTTP** - Disabled by default on Android 9+. Enabled via `network_security_config.xml`.

2. **Content URIs expire** - Don't persist `content://` URIs long-term. Store MediaStore `_ID` instead.

3. **Main thread blocking** - Never do I/O on main thread. Use `withContext(Dispatchers.IO)`.

4. **Compose recomposition** - Composables re-run when state changes. Keep them pure (no side effects in body).

5. **ViewModel scope** - `viewModelScope.launch` auto-cancels when ViewModel is cleared.
