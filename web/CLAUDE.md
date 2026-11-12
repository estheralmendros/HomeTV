# HomeTV web client (`web/`)

Web notes, read on demand. Project overview, commands and shared conventions: the root `CLAUDE.md`.

## Layout

- `src/strings.es.ts`: `S`, the only UI string catalog (es-ES; functions for values and plurals).
- `src/api/`: `client.ts` (`apiFetch`, 401 hook, `X-Admin-Token`), `errors.ts` (`ApiError`, `errorMessage`),
  `types.ts` (REST shapes, snake_case), one module per area, `paging.ts` (`nextPageParam`), `queryKeys.ts`
  (`queryKeys`, `mutationKeys`, `staleTimes`, `invalidateAfter(event)`: which keys each event invalidates is defined
  only there). `['me']` holds `Me` = the profile + `subtitle_style`.
- `src/auth/`: per-tab session (`AuthProvider`, `session.ts`), route guards (`BootGate`/`RequireProfile`/
  `RequireAdmin`), in-memory admin unlock, `useAdminAction` (keypad + one retry on `admin_locked`). `AuthProvider`
  seeds `['me']` with `DEFAULT_SUBTITLE_STYLE` and tells `trackChoiceSession` the active profile. `AppProviders` =
  Query, Toast, Auth, `RatingPromptHost`, AdminPin (+ optional `subscribeLocation`, from `lib/routerLocation.ts`).
- `src/player/` (lazy chunk):
  - Pure: `capabilities`, `versions`, `playerMachine` (the reducer), `tracks`, `trackChoice` (the matching rule of
    `specs/subtitles-improvements/prd.md` FR-3..FR-5a; its test runs `shared/track-choice-cases.json`),
    `subtitleStyle` (the `::cue` font size is `calc(5vmin * factor)`, never a `%`: Firefox resolves a `::cue`
    percentage against 16 px).
  - `trackChoiceSession`: tab-lifetime picks + the per-title, per-profile write queue; `subtitleStyleDraft`.
  - I/O hooks: `usePlaybackSession` (POST/preparing/attach/keep-alive/recovery/exit), `attachSource` (direct, hls.js,
    native iOS HLS), `useProgressReporter`, `useTrackChoiceSaver`, `useSubtitleStyleSave`, `usePlayerSubtitles`,
    `useMediaSession`, `subtitleLoader` (Bearer fetch -> blob `<track>`).
  - `PlayerRoute` is the stable shell (fullscreen root + the single `<video>`, `PlayerShellContext`, the
    `<style data-subtitle-style>` `::cue` rule). `PlayerPage` is keyed by `pathname + ?file=` and holds all
    per-playback state, including the end-of-playback inline rating form. `SubtitleStylePanel` saves on unmount.
- `src/features/<screen>/`: one folder per screen (setup, profiles, home, library, search, film, show, person,
  watchlist, ratings, activity, admin, about).
  - `ratings/`: `RatingPromptHost` (one pending prompt; navigating away closes it and dismisses in the background,
    except to `/profiles`|`/setup`), `RatingForm`/`RatingDialog`, `useRatingWrite` (optimistic `my_rating`, rollback,
    toasts by the "form gone" rule), `RatingsBlock`.
  - `watchlist/`: `WatchlistPage`, `useWatchlistParams` (filters in the URL, replace navigation),
    `useWatchlistToggle` (optimistic, pending survives a remount).
  - `admin/`: `AdminLayout` (tabs; the one scan poll), `ScanPage`, `LibrariesPage` (pure `libraryDraft` reducer;
    plan -> server-built confirmation -> PUT), `FolderBrowser`.
- `src/components/`: shared UI (`PosterCard`/`SummaryCard`/`WideCard`, `PosterGrid`, `LoadMoreFooter`, `Artwork`,
  `StarRating` = `StarDisplay` + `StarInput` slider, `CastRow`, `WatchlistToggle`, `NavBar` + `MobileTabBar`).
  `styles/tokens.test.ts` checks the `--color-rating` contrast; `NavBar.styles.test.ts` checks the CSS source.
- `src/lib/`: `format` (es-ES dates and numbers, `formatActivityTime`), pure `stars`, `routerLocation`,
  `historyIndex`, `languages`, `chunkReload`, `RouteErrorBoundary`, `lazyRoute`, hooks (`useMediaQuery`,
  `useElementHeight`, `useOverflows`). `router.tsx`: `/admin/*` and `/watch/*` are lazy.
- `tests/`: `setup.ts` (MSW `onUnhandledRequest: 'error'`, media/TextTrack stubs, resets), `fixtures.ts` (pure `f.*`
  factories), `msw/handlers.ts` (a default for every endpoint; saved track choices are served by `withSavedChoice`),
  `msw/server.ts` (`recordRequests()`), `renderWithProviders.tsx`, `player.tsx`, `fakeHls.ts`, `flush.ts`,
  `capsEnvs.ts`, `media.ts`, `viewport.ts`, `e2e/smoke.spec.ts` (optional Playwright, incl. phone/tablet/desktop
  layout checks).

## Web conventions

- **Strings**: every visible text, `aria-label`, `alt`, `title`, `placeholder`, toast and
  `document.title` comes from `S` in `src/strings.es.ts` (`strings.test.ts` scans `src/**/*.tsx`).
  Component tests assert the literal Spanish texts. Server diagnostics (`message`, `detail`) are
  never the main text ("Detalles técnicos" or raw admin log lines only).
- **Data**: TanStack Query only; keys from `queryKeys`, invalidation via
  `runInvalidation(qc, event)` / `invalidateAfter`. `['admin', …]` queries are enabled only while
  `useAdminToken() !== null`; every admin op goes through `useAdminAction().run(op, purpose)`.
- **Player**: the reducer is pure (time and positions come in event payloads); effects live in
  `usePlaybackSession` (declare `useProgressReporter` before it: the final report must precede
  the release). Never touch `video.src`/hls.js outside `attachSource`; positions for requests
  and reports are `tracker.position`, never `video.currentTime`.
- **Tests** (`npm test` must pass with no network, no stderr): `renderWithProviders(ui, opts)` /
  `renderApp({route, …})` (seeded session/`['me']`/`['info']`; `profile: null`/`info: null` run
  the boot requests), `renderPlayer(url)` from `tests/player.tsx` (`startDirect`, `startHls`,
  `at`, `pauseAt`, `puts/posts/deletes/keepalives`, `answerPlayback`, `stubFullscreen`, `gateTrackChoicePuts()`
  (holds each track-choice PUT until `release()`)),
  `recordRequests()` (MSW recorder; bodies after `await settled()`), `vi.mock('hls.js', () =>
  import('<rel>/tests/fakeHls'))` (`FakeHls.last`, `hlsLog`, `emit`, `fatal`), `setCapabilityEnv(
  iphoneEnv())` from `tests/capsEnvs.ts`, `mediaState(video)` / `fakeTextTrack(el)` from
  `tests/media.ts`, `setViewportWidth(360)` from `tests/viewport.ts` (matchMedia stub, desktop
  1280 px by default; layout that differs on phones renders via `useMediaQuery(PHONE_QUERY)`
  (< 600 px), `COMPACT_NAV_QUERY` (< 720 px: NavBar vs bottom tab bar) or `SEARCH_ICON_QUERY`
  (< 960 px: "Buscar" icon instead of the NavBar field, the /search page input),
  not CSS-only hiding, so it is testable). Back navigation (`useGoBack`) reads the history index
  through the `historyIndex` seam (`src/lib/historyIndex.ts`, React Router's `history.state.idx`;
  the test harness counts memory-router entries). Components that only need a derived media-clock
  value use `useMediaSelector(video, select)`; only `Controls`/`SeekBar` subscribe to
  `useMediaClock`. `SeekBar` commits a drag or held range key once, on release. Fake timers: `useFakePlayerTimers()` (also moves React Query's scheduler to a
  real tick), then `until`/`advance` from `tests/flush.ts`, never `findBy*`/`waitFor`; prefer
  `fireEvent` under fake timers. Never `await` a sync `act()`.
