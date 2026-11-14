# HomeTV Android TV app (`tv/`)

TV notes, read on demand. Project overview (toolchain and library versions), commands and shared conventions: the
root `CLAUDE.md`.

## Layout

`core/` (pure Kotlin/JVM, package `com.hometv.tv.core`):

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

`app/` (`com.hometv.tv`):

- `MainActivity`: offers every key to `KeyDispatch` before Compose focus; screens register a `KeyEventInterceptor`.
- `di/AppContainer`: manual DI (store, `clients()` rebuilt per server URL, repositories, `appScope`, `account`, player
  controllers per engine, `vlcAvailability`, `enginePrefs`, `notices`, `zone` seam, one factory per ViewModel).
- `data/`: `AccountState` (profile, info, `subtitleStyle`; a late `/api/me` applies only to the profile still signed
  in), `SubtitleStyleStore`, `TrackChoiceMemory` (this device's picks, process lifetime), `RatingPrompts` (one pending
  prompt; cleared on a real server/profile change), `SignOut`, `EnginePrefs` (per device, its own DataStore file,
  untouched by sign-out), `DataStoreSessionStore`, `HomeCache`, `HomeRefresh`/`DetailRefresh`, `PlayerLaunches`.
- `playback/`:
  - Media3: `PlayerFactory`, `MediaItemBuilder`, `ExoPlayerController` (the ExoPlayer is private; views get a
    `CuePlacementPlayer` that unsets WebVTT `line:auto` so the bottom padding (Posición) applies; listeners go
    through a `java.lang.reflect.Proxy`, so `Player$Listener` is kept in `proguard-rules.pro`), `ExoSubtitleStyle`
    (pure caption spec, never the device caption settings), `ExoTrackMapper`, `SubtitleFileCache`.
  - libVLC (its types live only here, in `AppContainer.create` and `PlayerScreen`'s VLC surface): `LibVlcProvider`
    (lazy, option-keyed instance on the main thread; a first-creation failure makes VLC unavailable for good),
    `VlcPlayerPort`/`LibVlcPort` (the only pass-through to `MediaPlayer`/`Media`), pure `VlcOptions`/`VlcUrls`/
    `VlcTrackMapper`/`VlcEventMapper`, `VlcPlayerController` (the subtitle look is baked into the LibVLC options, so a
    look change rebuilds the port and reopens at the same position; opens only once a layout is attached; stale
    events are dropped by generation).
  - Media session for both engines: `ControllerSessionPlayer` + `PlayerMediaSession`.
- `ui/`: `HomeTvRoot` (NavHost + the one root `ratings/RatingPromptHost`), `navigation/` (`Route`, `NavRequest`,
  `AppNavigator`), `text/` (`UiText`, `UiFormat`, `StringResolver`), `components/` (`TopNav`, `PosterCard`,
  `ActionButton`, `ConfirmDialog`, `TextDialog`, `Artwork`, `FocusRestorer.kt`, `OkKeyGuard` (drops the OK events of a
  press that began elsewhere: the release of a long press never clicks on the screen/dialog it opened; keyed per screen on
  the NavHost, chained on `EpisodeActionsDialog`), `OkLongPress` (`rememberOkLongPress`: the long press of the Continue
  cards and episode rows, never tv-material's `onLongClick`: a 450 ms timer or the first repeat, so a TV remote over
  HDMI-CEC, which never auto-repeats and is released by Android ~550 ms into a hold, long-presses too; the release's
  click on the same element is swallowed), `SafeAreaBringIntoViewSpec` (scroll viewports run to the screen
  edge, the 27 dp safe area is padding inside them and this spec stops focus scrolling there)), `common/ScreenError`, theme
  (`HomeTvColors`, `HomeTvDimens`), one package per screen:
  - `home` (Back -> exit dialog -> sign out), `settings` (admin force-VLC toggle, licences), `browse`
    (`PosterGridPane`, shared with Mi lista), `watchlist`, `activity`, `person`.
  - `detail`: Film/Show detail with the shared `DetailRatings` and `DetailWatchlistToggle` (optimistic writes, rollback,
    a refetch while writing keeps the shown value).
  - `ratings`: `RatingEditor` (dialog state machine), `RatingDialog`, `StarRow`.
  - `player`: `PlayerViewModel` (the session state machine: engine choice and VLC fallback, remembered track choices,
    the subtitle style dialog), `PlayerScreen` (`ExoSurface`/`VlcSurface`, overlay, `TrackSelectionDialog`,
    `SubtitleStyleDialog`).
- `res/values/strings.xml` is the only catalog; the APK keeps only `es` resources (`localeFilters`).

Tests:

- `app/src/test`: ViewModel/glue tests (JUnit 5). `TestRepos(appScope =)` gives the real repositories (pass a scope on
  the test dispatcher for virtual time); player tests drive `FakePlayerController` (`PlayerTestBase`); VLC tests use
  `FakeVlcPort` (no JVM test loads libVLC native code). DataStore tests that replace an existing file are
  `@DisabledOnOs(OS.WINDOWS)`. `legal/ThirdPartyNoticesTest` checks the notices against the pinned libvlc version.
- `app/src/sharedTest`: Compose focus, navigation and layout-fit tests on Robolectric through `UiTestBase` (`press`,
  `back`, `assertFocused`, `assertFullyVisible`/`assertInsideSafeArea`) and `testContainer(...)` + `ScriptedApi` (one
  `on*` seam per endpoint, 404 / empty / 204 by default; suspend in a seam to gate a request). Dialog nodes live in the
  dialog window: check them against the dialog's tag, not the screen. Also compiled into `androidTest`.

## TV conventions

- **No UI text in `core`**: it returns message keys (`MessageKey`, `ServerValidation`, unsupported
  reasons) and structured values (`Runtime`, `EpisodeCode`, `SeasonTitle`, `EpisodeTitle`,
  `LanguageName`, `TrackLabel`, `PreparingEstimate`); `CoreHasNoUiTextTest` guards it.
  ViewModels emit `UiText` (args: strings, core structured values or nested `UiText`), resolved in
  Compose through `ui/text/UiFormat`. Composables read text only via `stringResource` /
  `pluralStringResource`; `NoHardcodedUiTextTest` scans `Text(`, `text =`, `contentDescription =`…
  and ViewModel literals.
- **Colors**: `HomeTvTheme` provides `LocalContentColor = HomeTvColors.OnBackground` (the screens paint their own
  background, there is no root `Surface`), so a `Text` without `color` is light. Content drawn on a light fill
  (`HomeTvColors.Primary`) sets `HomeTvColors.OnPrimary` explicitly. `ThemeLegibilityTest` checks contrast.
- **Catalog**: Spanish only, `values/strings.xml`, no `values-<lang>/`. Templates use `%1$s` only
  (never `%d`/`%f`, `StringsCatalogTest`); numbers are pre-formatted by `Format` (es-ES). Plurals:
  `pluralStringResource(R.plurals.x, n, Format.count(n))`.
- **Tests**: JUnit 5 + coroutines-test with virtual time (no real sleeps); core fakes come from
  `testFixtures`. Focus/navigation tests live in `src/sharedTest` and run on the JVM with
  Robolectric (`UiTestBase.press(Key…)`, `back()`, `assertFocused(tag)`); keep them there so they
  stay runnable without an emulator. The app JVM suite runs with Robolectric **native graphics**
  (`robolectric.graphicsMode=NATIVE` in `app/build.gradle.kts`): real text metrics, so layout and focus
  bugs that only show on a device fail here too. Drive the player through `FakePlayerController`; never touch
  ExoPlayer outside `ExoPlayerController`, nor libVLC's `MediaPlayer`/`Media` outside `LibVlcPort`: libVLC types live
  only in `app/…/playback/` (`LibVlcPort`, the `LibVlcProvider` factory), `AppContainer.create` and `PlayerScreen.VlcSurface`.
  JVM tests use `FakeVlcPort` (libVLC's native code can't load on Robolectric).
