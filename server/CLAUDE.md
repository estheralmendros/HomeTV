# HomeTV server (`server/`)

Server notes, read on demand. Project overview, commands and shared conventions: the root `CLAUDE.md`.

## Layout

Python 3.12 uv project (FastAPI, SQLAlchemy 2 sync, Alembic, guessit). Package `hometv/`:

- `config.py`: `Settings`, `load_settings()` (env `HOMETV_*` > YAML > defaults; fills `library_sources`),
  `prepare_runtime()` (env-root path guards, data dirs, secret key), `check_library_folders()`, `prepare_hls_dir()`
  (mkdir + wipe). `libraries.films|shows` / `HOMETV_FILMS_PATHS|SHOWS_PATHS` only seed the stored folders once;
  `libraries.browse_root` (`HOMETV_MEDIA_BROWSE_ROOT`, default `/media`) is the folder browser base.
  `scan.watch_enabled` / `scan.watch_quiet_seconds` (default on / 30 s, `ge=5`); `make_settings` turns watching off.
- Pure modules (no I/O): `text.py` (incl. `undecodable(name)`), `parser.py`, `subtitle_style.py` (the style `Literal`
  aliases, `DEFAULT_STYLE`, `STYLE_FIELDS` = field -> `profile.subtitle_<field>` column; a test checks it equals
  `SubtitleStyle.model_fields`; the DB CHECKs are built from the aliases in `models.py`).
- `probe.py`: ffprobe wrapper (injectable `runner`) + pure `normalize()`.
- `db/engine.py`: `Database` (read/write engines, `write_session()`), `run_migrations()`. `db/models.py`: the data
  model. Per-profile title rows (`WatchlistEntry`, `Rating`, `RatingDismissal`, `TrackChoice`) target a film or a show,
  with one partial unique index per target. `db/migrations/versions/`: Alembic; columns are added to `profile` with a
  plain `ALTER TABLE profile ADD COLUMN`, never a batch recreate of `profile`.
- `libraries.py`: library folders owned by the DB (seeded once: env/YAML > present `media_file` roots > empty;
  `library.folders_seeded_at`). Pure planning helpers (`normalize_folder`, `validate_change`, `split_removals`,
  `browse_listing`; fs via `FolderFs`) + `LibraryFolders` (`app.state.libraries`: `current()` immutable snapshot,
  `plan`, `save(confirm_relinks=)`, `browse`).
- `scanner.py`: `Scanner(...).run()`: walk/diff/parse/probe/link/sidecars/soft-delete+purge. A same-library root change
  is a path-only re-root; a move/relayout relinks only with `relinks=True` (else the row is untouched and the report
  gets a `relink_pending:` line). `scope=` (watch runs) walks only the scope dirs; root guards: an empty root listing,
  or a whole-root scope without videos, never soft-deletes (`root_empty_skipped_removal`). `scan_report.py`:
  `ScanReport` (saved into `scan_run`; in-memory `unreachable_roots`, handed to the watcher).
- `changes/` (change-triggered scoped scans): `scope.py` (pure `ScanScope`, `within` is the one path-prefix helper,
  `merge_scopes` capped at `MAX_PENDING_SCOPES` = 50), `mounts.py` (pure mountinfo parsing, fs-type denylist),
  `backend.py` (`WatchBackend` protocol, `WatchEvent`), `inotify.py` (`InotifyBackend` over ctypes, Linux only),
  `settle.py` (pure quiet-period + size-check settler), `watcher.py` (`ChangeWatcher` on `app.state.watcher`: inert
  when disabled or not on Linux, single-flight `sync()`, `status()`; injectable seams for backend, mountinfo, platform,
  clock and stat; `stop()` logs task failures, never re-raises).
- `metadata/`: `tmdb.py` (`TmdbClient`: limiter, retries, language fallback, `person_details`), `matching.py` (pure
  `choose_match`), `enrich.py` (`Enricher`; `merge_items` moves every per-profile row through `_merge_per_profile` with
  one keep rule per table; admin `match_item`/`resolve_unmatched`), `artwork.py` (`ArtworkCache`, lazy sizes).
- `admin.py` (unmatched list, `TmdbProxy`), `profiles.py` (PIN hashing, `TokenService` with injectable `clock`,
  `UnlockLimiter`, profile CRUD).
- `watch.py`: pure watched rules (90 %, last part, in-progress, `next_up`, `last_episode`/`is_title_finished`) +
  `report_progress` / `mark_watched`. They return a `rating_prompt` only when the request finishes the title (film, or
  the show's last present episode) and the profile has neither a rating nor a dismissal.
- `catalog.py`: batched list/detail/home/search/person queries returning the pydantic shapes (`not_found`,
  `show_visible()`, `require_visible`, `in_watchlist`, rating helpers, track-choice reads). `catalog` never imports
  `track_choices`.
- Per-profile writes: `watchlist.py`, `ratings.py`, `track_choices.py` (also `set_subtitle_style`). Visible titles only
  (else 404), wrapped in `fk_to_unauthorized()` (profile deleted after auth -> 401). The server never matches a track
  choice against tracks: the clients do.
- `people.py`: `PersonService` (`app.state.people`): single-flight TMDB fetch per person, a never-fetched person waits
  up to `wait_seconds`, a stale one answers at once + background refresh, a failed fetch leaves the DB untouched.
- `scheduler.py`: `Scheduler`: single-flight scan -> enrich; `trigger(t, before=)` (`before` runs in a thread under the
  lock), `request_scoped(scopes)` -> `watch` runs (a full run clears the queue), `scan_status`.
- `playback/`: `decide.py` (pure decision + `ClientCaps`), `stream.py` (byte ranges), `keyframe_readers.py` (pure MKV
  Cues / MP4 stss readers), `keyframes.py` (`KeyframeService`, background indexer = scanner `keyframe_hook`),
  `segments.py` (pure plan/playlist, `split_fmp4`), `hls.py` (`HlsSessionManager`: check + register atomic under
  `_create_lock`; `open_segment()` opens the file before eviction, the caller closes it), `subtitles.py`, `procs.py`
  (spawn, kill sequence).
- `spa.py`: `web_dist` serving + SPA fallback (catch-all appended last; `/assets/*` immutable, a stale asset is a plain
  404, else `index.html` no-cache; a placeholder page without a dist).
- `main.py`: `create_app(settings, *, watch_backend_factory=None)` + lifespan (sets `app.state.*`). Startup order:
  `prepare_runtime` -> migrations -> `libraries.seed_once` -> `check_library_folders` -> `prepare_hls_dir` -> services
  -> `Scheduler` -> `ChangeWatcher` -> `scheduler.start()` -> `watcher.start()` (never awaits the arming walk).
  Shutdown stops the watcher before the scheduler and `people.stop()` before `tmdb.aclose()`. `run()` is the
  single-worker entry (console script `hometv`, the container CMD).
- `api/`: `errors.py` (envelope, `unauthorized()`, `fk_to_unauthorized()`), `deps.py` (auth deps), `schemas.py`
  (`ItemKind` is defined once here), `list_params.py` (`PageNumber`, strict `WatchedParam`), one `routes_*.py` per area.

Tests (`tests/`): `unit/` (pure), `service/` (tmp dirs + SQLite, stubs), `api/` (in-process httpx), `integration/`
(real ffmpeg/ffprobe, marker `integration`; `test_inotify_backend.py` is also marked `inotify` and skipped off Linux),
`perf/` (marker `perf`), `fixtures/`. Helpers: `factories.py` (`add_*` row builders), `stubs.py` (`FakeProc`, `Spawner`,
`ScaledClock`, `wait_until`, `FakeWatchBackend`, `BackendFactory`, `capture_selects`/`capture_writes` for query-count
tests), `fmp4_builder.py` (synthetic fMP4), `hls_stub.py` (argv-driven stub ffmpeg for API tests).

## Server conventions

- **Writes**: every DB write goes through `db.write_session()` (process-wide lock +
  `BEGIN IMMEDIATE`). Keep transactions short. Never hold one across an `await`, a
  subprocess, an HTTP call or a directory walk. Reads use `db.read_session()` / `ReadSession`.
- **Event loop stays free**: DB route handlers are plain `def`. Async code calls DB work through
  `asyncio.to_thread`.
- **Pure modules** (`text`, `parser`, `probe.normalize`, and later `matching`, `decide`,
  `segments`) do no I/O and are table-tested.
- **Subprocesses**: `asyncio.create_subprocess_exec` with argument lists only (never a shell),
  explicit timeouts, pipes drained. Wrap them so tests can inject stubs (see `probe.runner`).
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
- **Change detection**: the watcher only reads (inotify never writes); no timer touches the disk
  (re-arming happens in `sync()` at full runs and after an overflow). Watcher tests use
  `FakeWatchBackend` + `auto_poll=False` + a fake clock; API tests that need another watcher state
  swap `app.state.scheduler.watcher_status` (bound once by the lifespan). `make_settings` keeps
  `scan.watch_enabled=False`, so no other test arms watches or gets spontaneous `watch` runs.
- **Library folders**: production `Scanner`/`Scheduler` construction passes the
  `LibraryFolders.current` provider (`folders=`); the default (env/YAML roots) is for tests only.
  Only a `libraries` run (`PUT /api/admin/libraries` via `trigger("libraries", before=save)`)
  relinks (`relinks=True`); every other run reports `relink_pending` and leaves the row as is.
  Admin library routes: `GET`/`PUT /api/admin/libraries`, `POST .../plan`, `GET .../browse`;
  web client in `api/admin.ts` (`getLibraries`/`planLibraries`/`saveLibraries`/`browseFolders`),
  keys `admin.libraries()`/`admin.browse(path)`, event `librariesChanged`.
- **Catalog visibility**: a file is hidden when `missing_since` is set; films/episodes need a
  present file, shows/seasons a visible episode (`watch.film_has_present_file`,
  `watch.present_file_exists`). Build responses in batches, never one query per item.
- Service functions used by routes raise `ApiError` with the documented error codes (e.g. `match_item`,
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
  `absolute` there. A restarted run of the B-frame MP4 with `aac_stereo` starts its audio about
  11 ms below one AAC frame of reorder; the AAC re-encode lower bound is two audio frames, so the integration suite passes on 5.1 too.
- **Timing in tests**: never assume ffmpeg (or a stub) is slower than the HLS monitor. Tiny
  clips remux in milliseconds: wait for a settled run (`wait_for_settled_run`) or evict
  explicitly before asserting restarts; the stub ffmpeg has `lockstep` (producer slower than the
  monitor) and `burst` (producer ahead of it) for deterministic throttle tests.
- Integration clips: `tests/integration/conftest.py` `CLIPS` (generated once per session; a
  hand-built PGS `.sup` provides the image-subtitle track and sidecar);
  `make_media_app()` gives a scanned app over the clip library. Keyframe reader fixtures are
  regenerated with `uv run python -m tests.fixtures.keyframes.make_fixtures`.
