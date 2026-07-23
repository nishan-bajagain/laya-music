# Laya Music – App Memory Log

## Deep Scan Summary (2026-07-20)

### Architecture
- **Pattern**: MVVM + Clean Architecture (Repositories ↔ ViewModels ↔ Compose UI)
- **Navigation**: Custom `NavBackStack` + `NavDisplay` from `androidx.navigation3` (not the standard `NavHost`)
- **Auth mechanism**: Cookie-based YouTube session stored in DataStore (no Firebase / OAuth tokens)
- **Session storage**: `DatastoreRepository.cookies` (DataStore Preferences key `COOKIES`)
- **Entry point**: `MainActivity` → `NavigationRoot` → `NavDisplay`

### File Map (key files)
| File | Role |
|------|------|
| `MainActivity.kt` | Activity, splash screen, intent handling |
| `NavigationRoot.kt` | Root Composable, all screen routing, backstack management |
| `Screen.kt` | `NavKey` data objects + `ScreenUiConfig` (bottom bar / mini player visibility) |
| `AuthScreen.kt` | WebView-based YouTube login |
| `AuthViewModel.kt` | Handles cookie capture + DataSync ID extraction |
| `DatastoreRepository.kt` | Single source of truth for all persisted preferences, cookies, account info |
| `YoutubeAuthHelper.kt` | Builds YouTube API headers with SAPISID hash auth |
| `WelcomeScreen.kt` | First-launch onboarding screen |
| `ProfileScreen.kt` | Shows account info, logout button |
| `SharedViewModel.kt` | Cross-screen state (playlist refresh trigger, deleted playlist IDs) |

---

## Bug / Issue Fixed: Authentication Gate

### Issue
The app bypassed authentication on launch. After the Welcome screen, it navigated directly to `HomeScreenKey` regardless of whether the user was logged in. Unauthenticated users could access Home, Search, Library, Downloads, Profile, and Settings freely.

### Root Cause
In `NavigationRoot`, the initial backstack was always `HomeScreenKey` (unless `showWelcome=true`):
```kotlin
// BEFORE (broken)
val backStack = rememberNavBackStack(if (showWelcome) WelcomeScreenKey else HomeScreenKey)
```
There was no check of cookie/session state at startup.

---

## Changes Made

### 1. `MainActivity.kt`
**What changed**: Added a second `runBlocking` call to check cookies (i.e., auth state) at startup — alongside the existing `hasSeenWelcome()` check. Both blocking reads happen before `setContent`, so the splash screen covers them (no UI flicker).

**Before**:
```kotlin
val showWelcome = runBlocking { !DatastoreRepository(this@MainActivity).hasSeenWelcome() }
NavigationRoot(showWelcome = showWelcome)
```

**After**:
```kotlin
val datastoreRepo = DatastoreRepository(this@MainActivity)
val showWelcome = runBlocking { !datastoreRepo.hasSeenWelcome() }
val isAuthenticated = runBlocking { datastoreRepo.cookies.first().isNotEmpty() }
NavigationRoot(showWelcome = showWelcome, isAuthenticated = isAuthenticated)
```

---

### 2. `NavigationRoot.kt`
**What changed**: Three separate updates.

#### 2a. Initial screen logic (auth gate)
```kotlin
// NEW parameter added
fun NavigationRoot(showWelcome: Boolean = false, isAuthenticated: Boolean = false)

// NEW initial key logic
val initialKey: NavKey = when {
    showWelcome     -> WelcomeScreenKey
    !isAuthenticated -> AuthScreenKey   // ← KEY CHANGE: blocked from Home
    else            -> HomeScreenKey
}
val backStack = rememberNavBackStack(initialKey)
```

**Launch scenarios**:
| Condition | Initial screen |
|-----------|----------------|
| Fresh install | `WelcomeScreen` |
| Returning user, valid session (cookies non-empty) | `HomeScreen` |
| Returning user, no session | `AuthScreen` |

#### 2b. WelcomeScreen "Get Started" now goes to AuthScreen
```kotlin
// BEFORE
backStack.add(HomeScreenKey)

// AFTER
backStack.add(AuthScreenKey)   // must login after welcome
```

#### 2c. AuthScreen entry — added `showBackButton` + `onLoginSuccess`
```kotlin
is AuthScreenKey -> NavEntry(key) {
    val isRoot = backStack.first() == key
    AuthScreen(
        onBack = backStack::safePop,
        showBackButton = !isRoot,          // hide back arrow when root
        sharedViewModel = sharedViewModel,
        onLoginSuccess = {
            // Clear entire backstack → land on Home
            repeat(backStack.size) { backStack.removeLastOrNull() }
            backStack.add(HomeScreenKey)
        },
        application = app
    )
}
```

#### 2d. Logout wipes backstack → AuthScreen
```kotlin
is ProfileScreenKey -> NavEntry(key) {
    ProfileScreen(
        onBack = backStack::safePop,
        onLoggedOut = {
            sharedViewModel.requestPlaylistRefresh()
            // Wipe stack, land on AuthScreen
            repeat(backStack.size) { backStack.removeLastOrNull() }
            backStack.add(AuthScreenKey)
            // ProfileScreen also calls onBack() after this;
            // safePop() refuses when AuthScreenKey is the only entry — safe.
        },
        ...
    )
}
```

---

### 3. `AuthScreen.kt`
**What changed**: Added two new parameters:

| Parameter | Type | Default | Purpose |
|-----------|------|---------|---------|
| `showBackButton` | `Boolean` | `true` | Hides the `←` arrow when `AuthScreen` is the root screen |
| `onLoginSuccess` | `(() -> Unit)?` | `null` | Override for post-login navigation; falls back to `onBack()` if `null` |

**Back button**:
```kotlin
navigationIcon = {
    if (showBackButton) { BackButton(onBack = onBack) }
}
```

**Login success event**:
```kotlin
AuthViewModel.ScreenEvent.Out.LoginCompleted -> {
    Toast.makeText(context, R.string.login_success, Toast.LENGTH_SHORT).show()
    sharedViewModel.requestPlaylistRefresh()
    if (onLoginSuccess != null) onLoginSuccess() else onBack()
}
```

---

## Security Properties Achieved

| Requirement | Status |
|-------------|--------|
| Fresh install: Welcome → Login (Home inaccessible) | ✅ |
| Logged-out user: Login is the only reachable screen | ✅ |
| Successful login: Home, all features accessible | ✅ |
| App restart with valid session: direct to Home | ✅ |
| No Home flicker before auth check | ✅ (blocking reads before setContent) |
| Auth enforced at root navigator level | ✅ (initialKey logic in NavigationRoot) |
| Logout clears session and returns to Login | ✅ (DatastoreRepository.logOut() + backstack wipe) |
| Back navigation cannot reach Home when unauthenticated | ✅ (safePop refuses pop when Auth is root) |
| AuthScreen back button hidden when it is the root | ✅ (showBackButton = !isRoot) |

---

## Known Limitations / Future Work
- **Token refresh**: YouTube Music cookie sessions don't have a server-side refresh mechanism in this client — session expiry means the user must log in again manually. No automatic refresh is implemented (none was present before either).
- **Share/View intents while unauthenticated**: If a YouTube link is shared to the app while not logged in, the song info fetch still runs (PlayerManager), but the mini player is hidden on AuthScreen. The song will play in background only — acceptable edge case.
- **Deep link protection**: Handled indirectly — since Home is never rendered while unauthenticated, deep links that would open Home/Playlist/etc. are harmless (PlayerManager still plays the song but the UI stays on AuthScreen).

---

---

## Bug Fix: Downloads Card Stretched Full Screen Height

### Issue
`PlaylistCard` had `modifier.fillMaxSize()` on both the `Card` and inner `Column`. In the LoggedOut state on HomeScreen, the card was placed inside an unbounded `Column` with no height constraint — `fillMaxSize()` expanded it to consume all remaining vertical space, producing a tall dark box with just the download icon and text floating inside.

### Root Cause
`PlaylistCard.kt` lines:
```kotlin
Card(modifier = modifier.fillMaxSize(), ...) {
    Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
```
These `fillMaxSize()` calls expand to fill ALL available space — fine in a constrained grid cell, catastrophic in an unbounded column.

### Fix
Changed both `fillMaxSize()` → `fillMaxWidth()` in `PlaylistCard.kt`. Card now wraps its content height naturally. Removed now-unused `fillMaxSize` import.

### Files Changed
- `app/src/main/java/ca/ilianokokoro/umihi/music/ui/components/playlist/PlaylistCard.kt`

---

## Feature Removal: Dynamic Album-Art Color Tint in Player

### What Was Removed
`PlayerScreen.kt` had a `LaunchedEffect` that:
1. Loaded the current song thumbnail via Coil on an IO dispatcher
2. Extracted the dominant colour using AndroidX Palette
3. Animated the result into `animatedAlbumTint`
4. Applied it as a second `.background(animatedAlbumTint)` layer on top of `surfaceContainerLow`

### Why Removed
Per user request — the dynamic colour shifting was not wanted; the plain `surfaceContainerLow` background is the correct static appearance.

### Files Changed
- `app/src/main/java/ca/ilianokokoro/umihi/music/ui/screens/player/PlayerScreen.kt`
  - Removed imports: `animateColorAsState`, `Color`, `LocalContext`, `BitmapImage`, `SingletonImageLoader`, `ImageRequest`, `SuccessResult`, `allowHardware`, `Dispatchers`, `withContext`
  - Removed: `val context`, `var extractedAlbumColor`, `val animatedAlbumTint`, full `LaunchedEffect` colour-extraction block
  - Removed: `.background(animatedAlbumTint)` from portrait `Column` and landscape `Row`

---

---

## Bug Fix: Downloaded Songs Show Blank Title / Artist

### Issue
Songs in the Downloads list rendered with artwork visible but completely empty title and artist text.

### Root Cause
Both `SongDownloadWorker` and `PlaylistDownloadWorker` fetch the full song info from the API (`fullSong`) during the download, but only used `fullSong.thumbnailHref` to download the thumbnail image. The DB update at the end of each worker was:
```kotlin
val updatedSong = song.copy(
    thumbnailPath = thumbnailPath?.path,
    audioFilePath = audioPath,
)
```
This preserves whatever was in the locally stored `song`. The problem: `LocalSongDataSource.setStreamUrl()` may create a skeleton `Song(youtubeId, streamUrl)` record when a song is first played (if it doesn't exist yet). When `downloadSongStandalone` later calls `createIfNotExists(song)` with the full Song object, the **IGNORE** conflict strategy means it does NOT overwrite the skeleton — the skeleton record wins. The worker then loads this skeleton, downloads the files, but writes back a Song that still has blank title/artist.

### Fix
Both workers now merge ALL metadata fields from `fullSong` into `updatedSong` before the DB write:
```kotlin
val updatedSong = song.copy(
    title         = fullSong.title.takeIf { it.isNotBlank() } ?: song.title,
    artist        = fullSong.artist.takeIf { it.isNotBlank() } ?: song.artist,
    duration      = fullSong.duration.takeIf { it.isNotBlank() } ?: song.duration,
    thumbnailHref = fullSong.thumbnailHref.takeIf { it.isNotBlank() } ?: song.thumbnailHref,
    isExplicit    = fullSong.isExplicit,
    thumbnailPath = thumbnailPath?.path,
    audioFilePath = audioPath,
)
```
`fullSong` is the authoritative source from the network; `song` fields are kept only as fallback if the network returned blank values.

### Files Changed
- `app/src/main/java/ca/ilianokokoro/umihi/music/core/workers/SongDownloadWorker.kt`
- `app/src/main/java/ca/ilianokokoro/umihi/music/core/workers/PlaylistDownloadWorker.kt`

---

## No Errors During Implementation
- No compilation errors expected — all changes are additive (new parameters with defaults, logic changes, no API breakage).
- `safePop()` guard (`size > 1`) already existed and naturally prevents crashing when AuthScreen is the sole backstack entry.
