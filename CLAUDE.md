# HomeTV

Self-hosted, single-household media server (films + TV shows on a local disk, TMDB metadata,
playback on web and Android TV over Tailscale). The spec is the source of truth:
`specs/HOMETV-1/hometv-app/prd.md` (server), `prd2.md` (web), `prd3.md` (TV). The REST API in
prd.md is a contract: change the PRD first, then the code.

## Layout

- `server/`: Python 3.12 uv project (FastAPI, SQLAlchemy 2 sync, Alembic, guessit).
  - `hometv/config.py`: Settings, `load_settings()` (env `HOMETV_*` > YAML > defaults),
    `prepare_runtime()` (path guards, dirs, HLS wipe, secret key).
  - `hometv/text.py`, `hometv/parser.py`: pure modules (no I/O).
  - `hometv/probe.py`: ffprobe wrapper (injectable `runner`) + pure `normalize()`.
  - `hometv/db/engine.py`: `Database` (read/write engines, `write_session()`),
    `run_migrations()`. `db/models.py`: full data model. `db/migrations/versions/`: Alembic.
  - `hometv/scanner.py`: `Scanner(db, settings, probe_fn=, fs=FsOps(), keyframe_hook=).run()`:
    walk/diff/parse/probe/link/sidecars/soft-delete+purge. `scan_report.py`: `ScanReport`
    (counts/errors, `save()` into `scan_run`).
  - `hometv/metadata/`: `tmdb.py` (`TmdbClient`: limiter, retries, language fallback),
    `matching.py` (pure `choose_match`), `enrich.py` (`Enricher`, `merge_items`, admin
    `match_item`/`resolve_unmatched`), `artwork.py` (`ArtworkCache`, `image()` Image object).
  - `hometv/admin.py`: admin services (`list_unmatched`, `unmatched_count`, `TmdbProxy`).
  - `hometv/profiles.py`: PIN hashing, `TokenService` (profile/admin tokens, injectable
    `clock`), `UnlockLimiter`, pure `plan_pin_change`, profile CRUD (`bootstrap`,
    `create_profile`, `update_profile`, `delete_profile`).
  - `hometv/watch.py`: pure rules (90 %, last part, in-progress, `next_up`) +
    `report_progress` / `mark_watched`. `hometv/catalog.py`: batched list/detail/home/search
    queries returning the pydantic shapes (`media_file_out`, `media_label`).
  - `hometv/scheduler.py`: `Scheduler` (single-flight scan -> enrich; `trigger`, `status`),
    `scan_status(session)`.
  - `hometv/playback/`: `decide.py` (pure decision + `ClientCaps`), `stream.py` (byte ranges),
    `keyframe_readers.py` (pure MKV Cues / MP4 stss readers, ffprobe row parsing),
    `keyframes.py` (`KeyframeService`: cache, ffprobe fallback + rchar watchdog, failure map,
    background indexer = scanner `keyframe_hook`), `segments.py` (pure plan/check/playlist,
    run.csv verification, `split_fmp4`, init helpers), `hls.py` (`HlsSessionManager`),
    `subtitles.py` (`SubtitleService`), `procs.py` (spawn, `IDLE_PREFIX`, kill sequence).
  - `hometv/spa.py`: §12 static `web_dist` serving + SPA fallback (`SpaRoute` catch-all for
    non-`/api` GET/HEAD, appended last by `install_spa`; `/assets/*` immutable, stale asset ->
    plain 404, else `index.html` no-cache; "Web UI not built" placeholder without a dist).
  - `hometv/main.py`: `create_app(settings)` factory + lifespan (sets `app.state.db/settings/
    tmdb/tmdb_proxy/artwork/scheduler/keyframes/hls/subtitles`); `run()` single-worker entry
    (console script `hometv`, the container CMD).
  - `hometv/api/`: `errors.py` (envelope), `deps.py` (auth deps), `schemas.py`,
    `list_params.py` (films/shows query params), `routes_*.py` (system, profiles, catalog,
    films, shows, watch, artwork, admin, media).
  - `tests/`: `unit/` (pure), `service/` (tmp dirs + SQLite, stubs), `api/` (in-process
    httpx), `integration/` (real ffmpeg/ffprobe, marker `integration`), `perf/` (marker
    `perf`), `fixtures/`, `factories.py` (`add_film`/`add_show`/`add_file`/`add_progress`),
    `stubs.py` (`FakeProc`, `Spawner`, `ScaledClock`, `wait_until`), `fmp4_builder.py`
    (synthetic fMP4), `hls_stub.py` (argv-driven stub ffmpeg for API tests).
- `web/`: React 18 + TS (strict, `noUncheckedIndexedAccess`, alias `@/`) + Vite 5 SPA, Node 24
  (`.nvmrc`), TanStack Query 5, React Router 6 data router, hls.js (lazy), CSS Modules over
  `styles/tokens.css`, no UI kit. `tsconfig.json` = references to `tsconfig.app.json` (src +
  tests) and `tsconfig.node.json` (configs + `tests/e2e`).
  - `src/strings.es.ts`: `S`, the only UI string catalog (es-ES; functions for values/plurals).
  - `src/api/`: `client.ts` (`apiFetch`, 401 hook, `X-Admin-Token`), `errors.ts` (`ApiError`,
    `errorMessage`), `types.ts` (prd.md shapes, snake_case), one module per area,
    `queryKeys.ts` (`queryKeys`, `staleTimes`, `invalidateAfter(event)`), `queries.ts`.
  - `src/auth/`: per-tab session (`AuthProvider`, `session.ts`), `BootGate`/`RequireProfile`/
    `RequireAdmin`, in-memory `adminUnlock`, `useAdminAction` (keypad + one retry on
    `admin_locked`), `AdminPinModal`.
  - `src/player/`: pure `capabilities`, `versions` (`selectVersion`/`startPosition`/`nextPart`,
    D10–D13), `playerMachine` (reducer), `tracks`, `nextEpisode`, `progressSync`; I/O hooks
    `usePlaybackSession` (POST/preparing/attach/keep-alive/D9/recovery/exit), `attachSource`
    (direct, hls.js, native iOS HLS), `useProgressReporter` (`PositionTracker` attach
    generations), `subtitleLoader` (Bearer fetch → blob `<track>`); `PlayerRoute` is the stable
    shell (fullscreen root + the single `<video>`, `PlayerShellContext`), `PlayerPage` is keyed by
    `pathname + ?file=` and holds all per-playback state.
  - `src/features/<screen>/` (setup, profiles, home, library, search, film, show, admin, about),
    `src/components/` (shared UI), `src/lib/` (`format`, `languages`, `chunkReload`,
    `RouteErrorBoundary`, `lazyRoute`, hooks), `router.tsx` (lazy `/admin/*` and `/watch/*`).
  - `tests/`: `setup.ts` (MSW `onUnhandledRequest: 'error'`, media/TextTrack stubs, resets),
    `fixtures.ts` (`f.*` factories), `msw/handlers.ts` (`handlers`, `apiError`),
    `msw/server.ts` (`recordRequests()`), `renderWithProviders.tsx`, `player.tsx`, `fakeHls.ts`,
    `flush.ts`, `capsEnvs.ts`, `media.ts`, `e2e/smoke.spec.ts` (optional Playwright).
- `deploy/`: `Dockerfile` (multi-stage linux/amd64: `server-build` uv venv, `web-build`
  `node:24-bookworm-slim` on `$BUILDPLATFORM` (`npm ci && npm run build`), `runtime`
  python:3.12-slim-bookworm + Debian ffmpeg 5.1 + `web/dist` in `/app/web/dist`, user `hometv`
  1000, healthcheck, CMD `hometv`; build context = repo root, `Dockerfile.dockerignore`
  whitelists `server/` and `web/` minus node_modules/dist), `docker-compose.yml`
  (app in the tailscale sidecar's netns, `HOMETV_HOST=127.0.0.1`, media `:ro`, no ports),
  `tailscale/serve.json`, `.env.example`, `README.md` (setup steps).
- Planned (later PRDs): `tv/`.

## Commands

- `make install`: uv sync + `npm ci`. `make test`: `uv run pytest -m "not perf"`, then
  `npm test` in `web/` (`make server-test` / `make web-test` run one side).
- Perf (G3 browse p95 < 100 ms): `make perf` (`cd server && uv run pytest -m perf -s`).
- Deploy: `make docker-build` (linux/amd64 image), `make compose-config` (validates the compose
  file with `.env.example`); run with `docker compose up -d --build` in `deploy/`.
- `make lint`: ruff check + format check + web typecheck. `make format`: auto-fix (server).
- `make run`: dev server on 127.0.0.1:8000 with data in `.dev-data/`, serving `web/dist`.
- Web: `make web-build` (`tsc -b && vite build`; initial JS ≤ 200 kB gzip, admin/player/hls.js
  are lazy chunks), `make web-dev` (Vite on :5173, `/api` proxied to `HOMETV_API_TARGET`),
  `make web-typecheck`, `make web-e2e` (Playwright smoke, Chromium, against `HOMETV_E2E_URL`;
  `npx playwright install chromium` once; `HOMETV_E2E_FILM=Dune` for the demo library).
- Demo server with a generated library and `web/dist`: `specs/HOMETV-1/hometv-app/create-environment.sh`
  (127.0.0.1:8000, admin "Admin" PIN 1234, viewer "Invitado"; Ctrl-C / kill stops it).
- Single test: `cd server && uv run pytest tests/unit/test_parser.py -k row11`;
  `cd web && npx vitest run src/player/versions.test.ts -t "D13"`.

## Conventions

- **Strict TDD**: write the failing test first, confirm it fails for the right reason, make it
  pass, refactor, run the full suite. `make test` and `make lint` must pass.
- **Writes**: every DB write goes through `db.write_session()` (process-wide lock +
  `BEGIN IMMEDIATE`). Keep transactions short. Never hold one across an `await`, a
  subprocess, an HTTP call or a directory walk. Reads use `db.read_session()` / `ReadSession`.
- **Event loop stays free**: DB route handlers are plain `def`. Async code calls DB work through
  `asyncio.to_thread`.
- **Pure modules** (`text`, `parser`, `probe.normalize`, and later `matching`, `decide`,
  `segments`) do no I/O and are table-tested.
- **Subprocesses**: `asyncio.create_subprocess_exec` with argument lists only (never a shell),
  explicit timeouts, pipes drained. Wrap them so tests can inject stubs (see `probe.runner`).
- **Language**: identifiers, API fields, error codes, logs and comments are English.
  Server-produced text shown to viewers is Spanish (es-ES), e.g. `tmdb_attribution` and the
  MediaFile `label` fallback `"Desconocido"`.
- **Errors**: raise `ApiError(status, code, message, details)`. Every 4xx/5xx is
  `{"error": {"code", "message", "details"}}`.
- **Schema changes** need a new Alembic revision. `test_orm_metadata_matches_migration` guards
  drift between models and migrations.
- Never write under a media root. Media is read-only (`test_scanner` checks a chmod'ed tree).
- **Auth**: routes take `CurrentProfile` (Bearer only), `MediaUser` (Bearer or `?token=`;
  only stream/subtitles/artwork), `AdminProfile` (unlock) or `AdminUser` (admin router
  dependency; `X-Admin-Token` header only). API tests use `user`/`admin`/`profile_factory`/
  `clock` from `tests/api/conftest.py` (`Who.headers`, `Who.admin_headers`). A new
  `/api/admin/*` route must be added to `ADMIN_ROUTES` in `test_api_admin.py` (coverage test).
- **Catalog visibility**: a file is hidden when `missing_since` is set; films/episodes need a
  present file, shows/seasons a visible episode (`watch.film_has_present_file`,
  `watch.present_file_exists`). Build responses in batches, never one query per item.
- Service functions used by routes raise `ApiError` with the PRD codes (e.g. `match_item`,
  `TmdbProxy`, `Scheduler.trigger`). TMDB is mocked with respx + `tests/fixtures/tmdb/*.json`;
  inject `sleep=` into `TmdbClient` so retries never wait. Scanner tests use the `mdb`, `media`,
  `stub_probe`, `media_settings` fixtures from `tests/service/conftest.py`.
- **Playback subprocesses** are injectable attributes (`KeyframeService.spawn/read_rchar/
  container_reader`, `HlsSessionManager.spawn/disk_free/segment_times_mode`,
  `SubtitleService.spawn/run`); timings are attributes too (`sample_interval`, `stall_seconds`,
  `wait_seconds`, `poll_seconds`, `playback_wait`...). Tests swap them on `app.state.*`.
- **ffmpeg version differences** (integration suite run on 9.0.2 and on the image's 5.1):
  `-segment_times` is absolute in 5.x but relative to the run's first video PTS in newer ffmpeg
  (`hls.detect_segment_times_mode` probes once); `movie_timescale=1000` is pinned; `-ss` is
  container-aware (`HlsSessionManager.seek_position`) with `-noaccurate_seek`; TS/ADTS inits
  of a run's first segment lack the AudioSpecificConfig (replaced, `init_is_complete`).
- **Real ffmpeg 5.1** (the image's version) can be exercised by streaming `server/` into a
  container built FROM the image plus uv, then `uv sync --frozen && uv run pytest -m
  integration` (use `COPYFILE_DISABLE=1 tar --no-xattrs` on macOS). `segment_times_mode` probes
  `absolute` there. Known 5.1 difference: a restarted run of the B-frame MP4 with `aac_stereo`
  starts its audio ~11 ms below the cross-run AC window.
- **Timing in tests**: never assume ffmpeg (or a stub) is slower than the HLS monitor. Tiny
  clips remux in milliseconds: wait for a settled run (`wait_for_settled_run`) or evict
  explicitly before asserting restarts; the stub ffmpeg has `lockstep` (producer slower than the
  monitor) and `burst` (producer ahead of it) for deterministic throttle tests.
- Integration clips: `tests/integration/conftest.py` `CLIPS` (generated once per session; a
  hand-built PGS `.sup` provides the image-subtitle track and sidecar);
  `make_media_app()` gives a scanned app over the clip library. Keyframe reader fixtures are
  regenerated with `uv run python -m tests.fixtures.keyframes.make_fixtures`.

## Web conventions (`web/`)

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
  `at`, `pauseAt`, `puts/posts/deletes/keepalives`, `answerPlayback`, `stubFullscreen`),
  `recordRequests()` (MSW recorder; bodies after `await settled()`), `vi.mock('hls.js', () =>
  import('<rel>/tests/fakeHls'))` (`FakeHls.last`, `hlsLog`, `emit`, `fatal`), `setCapabilityEnv(
  iphoneEnv())` from `tests/capsEnvs.ts`, `mediaState(video)` / `fakeTextTrack(el)` from
  `tests/media.ts`, `setViewportWidth(360)` from `tests/viewport.ts` (matchMedia stub, desktop
  1280 px by default; layout that differs on phones renders via `useMediaQuery(PHONE_QUERY)`, not
  CSS-only hiding, so it is testable). Fake timers: `useFakePlayerTimers()` (also moves React Query's scheduler to a
  real tick), then `until`/`advance` from `tests/flush.ts`, never `findBy*`/`waitFor`; prefer
  `fireEvent` under fake timers. Never `await` a sync `act()`.
