# HomeTV Android (`android/`)

One Gradle project (`rootProject.name = hometv-android`), read on demand. Project overview (toolchain and library
versions), commands and shared conventions: the root `CLAUDE.md`. All Kotlin packages stay `com.hometv.tv.*`
whatever the module.

Module graph (no module depends on an app): `:core` <- `:playback` <- `:shared` <- `:tv`; `:testing` depends on
`:shared` and is a test dependency of `:playback`, `:shared` and `:tv`. Only main -> main edges must be acyclic.

## Modules

### `core/` (pure Kotlin/JVM, package `com.hometv.tv.core`)

- `api/`: `HomeTvApi` (Retrofit), `ApiModels` (DTOs, kept in step with the server schemas), `ApiException`/`apiCall`,
  `ApiClients` (API / stream / validation OkHttp), `AuthInterceptor` (Bearer for the server host only).
- `data/`: `SessionStore` (StateFlow; conditional writes apply only while that profile is still stored),
  `SessionGuard` (single-flight token renewal; `withRenewal(Origin.Foreground|Background)` retries only while the
  sending profile is still stored), repositories (catalog, profiles, playback, watch, watchlist, ratings, track
  choices), `LatestWinsWriter<K, V>` (the latest-wins write queue: per-key worker in `appScope`, one write in flight, a
  wish whose profile is no longer stored is dropped, never throws), `AppEvents`, `NextEpisodeResolver`.
  `WatchlistRepository` and `RatingsRepository` keep their own per-title in-flight rules; `RatingsRepository.changes`
  emits every successful write.
- `playback/` (pure rules): `TrackChoiceRules` (the matching rule of `specs/subtitles-improvements/prd.md`
  FR-3..FR-5a; its test reads `shared/track-choice-cases.json` through the `hometv.trackChoiceCases` system property),
  `SubtitleStyles.kt` (style enums, `SubtitleLook`, `SubtitleStyleEditor`), `ClientCapsBuilder`, `VersionSelector`,
  `VersionNames` (a version is shown by its file name without extension, the web's `versionName` rule; null when blank),
  `TieredVersions` (Exo-first version choice), `EngineSelector` (EXO unless libVLC is available and forced, or the file
  has a codec outside the Exo caps or already failed on Exo; never chosen by container), `TrackRules`,
  `PlaybackErrorMapper` (incl. the fall back to VLC), `PlaybackPlanner`, `SeekAccumulator`, `ProgressReporter`, the
  `PlayerController` interface (no Media3 types in `core`).
- `util/`: `ServerUrl`, `Format` (es-ES numbers and dates), `Stars`, `relativeTime`, `LanguageNames`.
- `src/testFixtures`: `FakeHomeTvApi`, `TestServer` (MockWebServer routing), `F` media files.

### `playback/` (Android library, `com.hometv.tv.playback`)

The engines and nothing UI-specific beyond the video surface:

- Media3: `PlayerFactory`, `MediaItemBuilder`, `ExoPlayerController` (the ExoPlayer is private; views get a
  `CuePlacementPlayer` that unsets WebVTT `line:auto` so the bottom padding (Posición) applies; listeners go through a
  `java.lang.reflect.Proxy`, so `Player$Listener` is kept in `consumer-rules.pro`), `ExoSubtitleStyle` (pure caption
  spec, never the device caption settings), `ExoTrackMapper`, `SubtitleFileCache`.
- libVLC: `LibVlcProvider` (lazy, option-keyed instance on the main thread; a first-creation failure makes VLC
  unavailable for good), `VlcPlayerPort`/`LibVlcPort` (the only pass-through to `MediaPlayer`/`Media`), pure
  `VlcOptions`/`VlcUrls`/`VlcTrackMapper`/`VlcEventMapper`, `VlcPlayerController` (the subtitle look is baked into the
  LibVLC options, so a look change rebuilds the port and reopens at the same position; opens only once a layout is
  attached; stale events are dropped by generation). **libVLC types live only in this module** (`LibVlcPort`,
  `AndroidPlayback`, `VideoSurface`).
- `AndroidPlayback.create(app, token, log, displayHeightPx)`: the one construction of the controllers and
  `VlcAvailability`; `CodecSource`/`AndroidCodecInventory`; `VideoSurface` (the Exo/VLC video composables);
  media session for both engines (`ControllerSessionPlayer` + `PlayerMediaSession`).
- `consumer-rules.pro`: the libVLC (`org.videolan.libvlc.**`, JNI) and `Player$Listener` R8 keeps, applied to every app.
  `res/raw/third_party_notices.txt`: the libVLC LGPL notice (`legal/ThirdPartyNoticesTest` checks it against the
  pinned version).

### `shared/` (Android library, `com.hometv.tv.shared`; UI-free app logic both apps use)

- `data/`: `AccountState` (profile, info, `subtitleStyle`; a late `/api/me` applies only to the profile still signed
  in), `SubtitleStyleStore`, `TrackChoiceMemory` (this device's picks, process lifetime), `RatingPrompts` (one pending
  prompt; cleared on a real server/profile change), `SignOut`, `EnginePrefs` (per device, its own DataStore file,
  untouched by sign-out), `DataStoreSessionStore`, `HomeCache`, `HomeRefresh`/`DetailRefresh`, `PlayerLaunches`.
- Every `*ViewModel` (home, browse, search, watchlist, activity, person, profiles, setup, settings, detail, player) and
  their state: `PlayerViewModel` (the session state machine: engine choice and VLC fallback, remembered track
  choices, the subtitle style dialog), `RatingEditor`, `HlsSessionOwner`, `DetailRatings`, `DetailWatchlistToggle`
  (optimistic writes, rollback, a refetch while writing keeps the shown value). They carry focus hints
  (`GridFocus`, `FocusTarget`) as plain data; no tv-material or focus API. `SettingsPolicy` (`TV` today: Cambiar
  servidor and Forzar VLC for admins only, server change behind the admin PIN; `PERSONAL` reserved for a phone).
- `ui/text/` (`UiText`, `UiFormat`, `StringResolver`), `ui/navigation/` contracts (`Route`, `NavRequest`,
  `PlayRequest`, `Library`), `ui/common/` (`ScreenError`, `PosterItem`), `ui/theme/HomeTvColors`, `AvatarUi`,
  `Tailscale` intent helper.
- `di/SharedContainer`: `open class` manual DI (store, `clients()` rebuilt per server URL, repositories,
  `appScope`, `account`, player controllers per engine, `vlcAvailability`, `enginePrefs`, `notices`, `zone` seam,
  `settingsPolicy`, one factory per ViewModel) and `AppConfig`. `SharedContainer.androidParts(context, appScope,
  versionName, debug, displayHeightPx)` builds the Android-only constructor arguments (DataStore files
  `datastore/session.preferences_pb` + `datastore/device.preferences_pb`, loaded in parallel; user agent
  `HomeTV-Android/<version>`; controllers from `AndroidPlayback.create`).
- `consumer-rules.pro`: kotlinx.serialization and Retrofit R8 keeps, applied to every app.
- Resources: the strings and drawables referenced by `:shared` code.

### `testing/` (Android library, `com.hometv.tv.testing`)

Test helpers in main, used as `testImplementation` (and `androidTestImplementation` by the apps): `TestRepos(appScope =)`
(the real repositories; pass a scope on the test dispatcher for virtual time), `MainDispatcherExtension`,
`FakePlayerController`, `ScriptedApi` (one `on*` seam per endpoint, 404 / empty / 204 by default; suspend in a seam
to gate a request), `FakeCodecs`, `LongClick` (`performLongClickCompat`), `playback/FakeVlcPort`, `CatalogStrings`,
`WindowsSkips` (`WINDOWS_DATASTORE_RENAME`) and `NoHardcodedUiText.assertClean(root)`. **No Robolectric here** (it
reaches `androidTest`): each module declares `testImplementation(libs.robolectric)` itself, and `UiTestPlatform`
(one Robolectric and one emulator implementation, same name, one per source set) stays per app.

`CatalogStrings` reads the real `strings.xml` catalogs on the JVM. Gradle passes two system properties in each module's
test config: `hometv.catalogs` (absolute `strings.xml` paths joined by `File.pathSeparator`, library first, a later
catalog overrides an earlier name) and `hometv.rClasses` (comma-separated `R` class names, whose ids map to names).

### `tv/` (the Android TV application, `com.hometv.tv`)

- `MainActivity`: offers every key to `KeyDispatch` before Compose focus; screens register a `KeyEventInterceptor`.
- `di/AppContainer`: `class AppContainer(...) : SharedContainer(...)` plus `create(context)`, which calls
  `androidParts` with `BuildConfig` values and the display height.
- `ui/`: `HomeTvRoot` (NavHost + the one root `ratings/RatingPromptHost`), `navigation/AppNavigator` (uses the TV
  `NavTab`), theme (`HomeTvTheme`, `HomeTvDimens`), `components/` (`TopNav`, `PosterCard`, `ActionButton`,
  `ConfirmDialog`, `TextDialog`, `Artwork`, `FocusRestorer.kt`, `OkKeyGuard` (drops the OK events of a press that began
  elsewhere: the release of a long press never clicks on the screen/dialog it opened; keyed per screen on the NavHost,
  chained on `EpisodeActionsDialog`), `OkLongPress` (`rememberOkLongPress`: the long press of the Continue cards and
  episode rows, never tv-material's `onLongClick`: a 450 ms timer or the first repeat, so a TV remote over HDMI-CEC,
  which never auto-repeats and is released by Android ~550 ms into a hold, long-presses too; the release's click on the
  same element is swallowed), `SafeAreaBringIntoViewSpec` (scroll viewports run to the screen edge, the 27 dp safe area
  is padding inside them and this spec stops focus scrolling there)), and one package per screen with the Compose
  screens (`home` (Back -> exit dialog -> sign out), `settings` (PIN dialog, licences), `browse` (`PosterGridPane`,
  shared with Mi lista), `watchlist`, `activity`, `person`, `detail`, `ratings`, `player` (`PlayerScreen`, overlay,
  `TrackSelectionDialog`, `SubtitleStyleDialog`)).
- Resources: only what `:tv` code alone references; the APK keeps only `es` resources (`localeFilters`).
- Release signing reads `android/keystore.properties` (gitignored).

### Resources (rule for every module)

A string or drawable referenced by `:shared` code lives in `:shared`; one referenced only by an app stays in that app.
An app may override a shared string with the same name (resource merging); the catalog test rejects such overrides in
`:tv`, so a moved string never changes the TV's text. `android.nonTransitiveRClass=true`: code in `:tv` that reads a
shared string imports `com.hometv.tv.shared.R`, aliased `SharedR` in files that also use `com.hometv.tv.R`.

## Tests

- `src/test` (JUnit 5): a class's test sits in the module that owns it (ViewModels and `data/` in `:shared`, engines in
  `:playback`, pure rules in `:core`). Player tests drive `FakePlayerController` (`PlayerTestBase`); VLC tests use
  `FakeVlcPort` (no JVM test loads libVLC native code). DataStore tests that replace an existing file are
  `@DisabledOnOs(OS.WINDOWS)` (`WindowsSkips`). `EnginePrefsTest` and `SettingsViewModelTest` stay in `:tv` because they
  use `testContainer(...)`.
- `tv/src/sharedTest`: Compose focus, navigation and layout-fit tests on Robolectric through `UiTestBase` (`press`,
  `back`, `assertFocused`, `assertFullyVisible`/`assertInsideSafeArea`) and `testContainer(...)` (builds the TV
  `AppContainer` over a `ScriptedApi`). Dialog nodes live in the dialog window: check them against the dialog's tag, not
  the screen. Also compiled into `androidTest`.
- Guards: `NoHardcodedUiTextTest` (thin, in `:shared` and `:tv`, each on its own `src/main/kotlin`),
  `StringsCatalogTest` (`:shared` on its own file; `:tv` on the merged catalog), `CoreHasNoUiTextTest`,
  `ThemeLegibilityTest` (`:tv`).

## TV conventions

- **No UI text in `core`**: it returns message keys (`MessageKey`, `ServerValidation`, unsupported
  reasons) and structured values (`Runtime`, `EpisodeCode`, `SeasonTitle`, `EpisodeTitle`,
  `LanguageName`, `TrackLabel`, `PreparingEstimate`); `CoreHasNoUiTextTest` guards it.
  ViewModels emit `UiText` (args: strings, core structured values or nested `UiText`), resolved in
  Compose through `ui/text/UiFormat`. Composables read text only via `stringResource` /
  `pluralStringResource`; `NoHardcodedUiText` scans `Text(`, `text =`, `contentDescription =`…
  and ViewModel literals in every module with UI code.
- **Colors**: `HomeTvTheme` provides `LocalContentColor = HomeTvColors.OnBackground` (the screens paint their own
  background, there is no root `Surface`), so a `Text` without `color` is light. Content drawn on a light fill
  (`HomeTvColors.Primary`) sets `HomeTvColors.OnPrimary` explicitly. `ThemeLegibilityTest` checks contrast.
- **Catalog**: Spanish only, `values/strings.xml`, no `values-<lang>/`. Templates use `%1$s` only
  (never `%d`/`%f`, `StringsCatalogTest`); numbers are pre-formatted by `Format` (es-ES). Plurals:
  `pluralStringResource(R.plurals.x, n, Format.count(n))`.
- **Tests**: JUnit 5 + coroutines-test with virtual time (no real sleeps); core fakes come from
  `testFixtures`. Focus/navigation tests live in `tv/src/sharedTest` and run on the JVM with
  Robolectric (`UiTestBase.press(Key…)`, `back()`, `assertFocused(tag)`); keep them there so they
  stay runnable without an emulator. The `:tv` and `:shared` JVM suites run with Robolectric **native graphics**
  (`robolectric.graphicsMode=NATIVE` in their `build.gradle.kts`): real text metrics, so layout and focus
  bugs that only show on a device fail here too. Drive the player through `FakePlayerController`; never touch
  ExoPlayer outside `ExoPlayerController`, nor libVLC's `MediaPlayer`/`Media` outside `LibVlcPort`: libVLC types live
  only in `:playback`. JVM tests use `FakeVlcPort` (libVLC's native code can't load on Robolectric).
