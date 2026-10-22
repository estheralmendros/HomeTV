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
- `deploy/`: `Dockerfile` (multi-stage linux/amd64: `server-build` uv venv, `runtime`
  python:3.12-slim-bookworm + Debian ffmpeg 5.1, user `hometv` 1000, healthcheck, CMD `hometv`;
  build context = repo root, `Dockerfile.dockerignore` whitelists `server/`), `docker-compose.yml`
  (app in the tailscale sidecar's netns, `HOMETV_HOST=127.0.0.1`, media `:ro`, no ports),
  `tailscale/serve.json`, `.env.example`, `README.md` (setup steps).
- Planned (later PRDs): `web/` (prd2 adds a `web-build` stage to the Dockerfile), `tv/`.

## Commands

- `make install`: uv sync. `make test`: `uv run pytest -m "not perf"`.
- Perf (G3 browse p95 < 100 ms): `make perf` (`cd server && uv run pytest -m perf -s`).
- Deploy: `make docker-build` (linux/amd64 image), `make compose-config` (validates the compose
  file with `.env.example`); run with `docker compose up -d --build` in `deploy/`.
- `make lint`: ruff check + format check. `make format`: auto-fix.
- `make run`: dev server on 127.0.0.1:8000 with data in `.dev-data/`.
- Single test: `cd server && uv run pytest tests/unit/test_parser.py -k row11`.

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
