# HomeTV

Self-hosted, single-household media server (films + TV shows on a local disk, TMDB metadata,
playback on web and Android TV over Tailscale). The REST API (`/api/openapi.json`, `/api/docs`)
is a contract shared by the web and TV clients: change it deliberately and update
`web/src/api/types.ts` and `tv/core/.../api/ApiModels.kt` in the same change.

## Layout

- `server/`: Python 3.12 uv project (FastAPI, SQLAlchemy 2 sync, Alembic, guessit).
  - `hometv/config.py`: Settings, `load_settings()` (env `HOMETV_*` > YAML > defaults; fills
    `library_sources`), `prepare_runtime()` (env-root path guards, data dirs, secret key),
    `check_library_folders()` (stored folders vs data/HLS dir), `prepare_hls_dir()` (mkdir + wipe).
    `libraries.films|shows` / `HOMETV_FILMS_PATHS|SHOWS_PATHS` only seed the stored folders once;
    `libraries.browse_root` (`HOMETV_MEDIA_BROWSE_ROOT`, default `/media`) is the browser base.
    `scan.watch_enabled` / `scan.watch_quiet_seconds` (`HOMETV_SCAN_WATCH_ENABLED|QUIET_SECONDS`,
    default on / 30 s, `ge=5`): change detection; `make_settings` turns it off for tests.
  - `hometv/text.py` (also `undecodable(name)`, shared by scanner/folder browser/scheduler/changes),
    `hometv/parser.py`: pure modules (no I/O).
  - `hometv/probe.py`: ffprobe wrapper (injectable `runner`) + pure `normalize()`.
  - `hometv/db/engine.py`: `Database` (read/write engines, `write_session()`),
    `run_migrations()`. `db/models.py`: full data model (incl. `WatchlistEntry`: profile + film|show,
    partial unique per target, `added_at`; `Rating`: profile + film|show, `stars_x2` 1..10 CHECK,
    `rated_at`, partial unique led by the title column `uq_rating_{film|show}_id_profile_id`,
    `ix_rating_rated_at_id`; `RatingDismissal`: same shape, `dismissed_at`).
    `db/migrations/versions/`: Alembic (head `0006`).
  - `hometv/libraries.py`: library folders owned by the DB (`library_folder`, seeded once per
    library: env/YAML > present `media_file` roots > empty; `library.folders_seeded_at`). Pure
    `normalize_folder`/`same_folders`/`choose_seed`/`validate_change`/`split_removals`/
    `browse_listing` (fs via injectable `FolderFs`); `LibraryFolders` (`app.state.libraries`:
    `seed_once`, `current()` immutable `LibraryFolderSet` snapshot, `plan` -> `ChangePlan`
    removed/covered/moved/relayout, `save(confirm_relinks=)`, `folder_status`/`describe`, `browse`).
  - `hometv/scanner.py`: `Scanner(db, settings, probe_fn=, fs=FsOps(), keyframe_hook=, folders=,
    relinks=, scope=).run()`: walk/diff/parse/probe/link/sidecars/soft-delete+purge; same-library
    Películas root change = path-only re-root; move/Series relayout = relink (only with
    `relinks=True`, else untouched + `relink_pending:` report line). `scope` (list of `ScanScope`,
    `watch` runs): `_walk_scoped` (one root listing, then only the scope dirs via the shared
    `_walk_tree`; symlinked top-level dirs never walked), rows loaded by a `path` range per scope
    (root-shallow: direct children only),
    root guards (empty root listing / whole-root scope without videos -> `root_empty_skipped_removal`),
    missing/sidecars limited to the scope, rows of another configured (nested) root left alone (the row's own root walk decides when present),
    unconfigured scope root -> `scope_unconfigured:`. `scan_report.py`: `ScanReport`
    (counts/errors, `save()` into `scan_run`; in-memory `unreachable_roots` = roots with
    `root_unavailable` or a root `walk_error`, handed to the watcher).
  - `hometv/changes/` (change-triggered scoped scans): `scope.py` (pure `ScanScope(root, rel_dir,
    recursive)` = top-level dir / root shallow / whole root, `root_of` longest root, `scope_for`, `within` (the one path-prefix helper),
    `merge_scopes` (cap `MAX_PENDING_SCOPES` = 50, widens roots, stops when only whole roots
    remain)), `mounts.py` (pure mountinfo parsing, `mount_for`, fs-type denylist `is_watchable`),
    `backend.py` (`WatchEvent` kinds incl. synthetic `watch_limit`/`backend_failed`, `WatchBackend`
    protocol `start/add_tree/remove/is_alive/abort/close`, `WatchLimitError`, `WatchUnavailable`, shared `SIZED_KINDS`/`file_extension`/`is_video`),
    `inotify.py` (`InotifyBackend` over ctypes, Linux only; pure `parse_events`/`map_batch`; reader
    thread applies drops before arms; dot/undecodable dirs never armed (`_skipped_name`); seams `libc/scandir/stat/read/poll_factory/read_sleep`),
    `settle.py` (pure `ChangeSettler`: quiet period + size check, `suspend`/`set_roots`/
    `drop_root`), `watcher.py` (`ChangeWatcher` on `app.state.watcher`: inert `disabled`/not Linux,
    `starting`/`active`/`unavailable`/`error` snapshot `status()`, single-flight `sync()` steps 0-7,
    health facts (`_fact_seq`) + instance-bound `HealthAction`s drained under the sync lock,
    `note_unreachable`, `poll_once`, `wait_idle`; seams `backend_factory/mountinfo/platform/clock/
    stat_size/stat_identity/auto_poll/poll_retry_seconds`; `stop()` logs, never re-raises, task failures).
  - `hometv/metadata/`: `tmdb.py` (`TmdbClient`: limiter, retries, language fallback;
    `person_details` = `/person/{id}` + `combined_credits`, biography fallback without credits,
    `BIOGRAPHY_LANGUAGE_KEY`), `matching.py` (pure `choose_match`), `enrich.py` (`Enricher`,
    `merge_items` (moves progress, watchlist entries, ratings and dismissals through the generic
    `_merge_per_profile(model, column, resolve)`: `_keep_earlier_added`, `_keep_later_rating` (survivor
    row stays, the loser's stars copied onto it), `_keep_earlier_dismissal`), admin `match_item`/`resolve_unmatched`), `artwork.py` (`ArtworkCache`,
    `image()` Image object; profile = `w185`/`h632`, both lazy).
  - `hometv/admin.py`: admin services (`list_unmatched`, `unmatched_count`, `TmdbProxy`).
  - `hometv/profiles.py`: PIN hashing, `TokenService` (profile/admin tokens, injectable
    `clock`), `UnlockLimiter`, pure `plan_pin_change`, profile CRUD (`bootstrap`,
    `create_profile`, `update_profile`, `delete_profile`).
  - `hometv/watch.py`: pure rules (90 %, last part, in-progress, `next_up`, ratings "finished":
    `last_episode` (max present (season, episode), specials only without regulars),
    `is_title_finished` (used by the show prompt; `last_episode` = reference rule + test oracle)) + `report_progress` -> `ProgressReport` / `mark_watched` -> `WatchedResponse`
    (`updated` + `rating_prompt`), both write blocks in `fk_to_unauthorized()`; prompt only when the
    request watches the finishing item (film; the show's last present episode, incl. a sibling from
    `_mark_siblings_watched` (returns the sibling ids) or a season/show mark) and the profile has no
    rating nor dismissal: `_show_last_episode_id` (one `LIMIT 1`) + `_rating_prompt` (one combined
    EXISTS, reuses the loaded film/show row), ≤ +3 statements, +0 below the rule.
    `hometv/catalog.py`: batched list/detail/home/search
    queries returning the pydantic shapes (`media_file_out`, `media_label`, `not_found` 404 helper,
    `show_visible()` EXISTS, `ItemKind` (defined once in `api/schemas.py`, re-exported; `WatchlistKind` alias), `require_visible(session, kind, id)`
    (one SELECT, 404; watchlist + rating writes), `in_watchlist(session, profile_id, kind, item_id)` (one
    EXISTS; `FilmDetail`/`ShowDetail.in_watchlist`); ratings: pure `household_average` (one decimal,
    half up), `stars_from_x2` (`stars_to_x2` lives in `api/schemas.py` next to `RatingRequest`), `_my_ratings` (+1 query per non-empty kind:
    `my_rating` on every `film_summaries`/`show_summaries`), `item_ratings` -> `ItemRatings` (one
    query: `my_rating`, `others` newest first, `average` from 2 ratings; detail `my_rating`/
    `ratings`/`household_average`);
    `person_detail`: TMDB credits (`person_credit`, `library_id` subquery so SQLite seeks
    `uq_*_library_id_tmdb_id`) ∪ local `cast_credit`, matched + visible only, ≤ 9 queries).
  - `hometv/watchlist.py` ("Mi lista", per profile): `add`/`remove` (one write each, visible
    title only else 404 `not_found` on both; `add` = `ON CONFLICT DO NOTHING`, keeps `added_at`; FK
    `IntegrityError` (profile deleted after auth) -> 401 `unauthorized`, any other one re-raised;
    `WatchlistKind` re-exported from `catalog`), `list_entries(session,
    profile_id, WatchlistParams(type all|films|shows, sort added|title|year, watched bool|None, page,
    page_size))` (one SQL over `watchlist_entry` LEFT JOIN film/show: visible only, `watched` = the
    library rule `catalog.watched_condition(model, profile_id, watched)` OR'ed per kind, fixed
    directions, ties by entry id; ≤ 8 queries, none added by the filter). Hidden entries kept; purge/profile deletion cascade via FKs.
  - `hometv/ratings.py` (per profile, household-wide reads): `set_rating(db, profile_id, kind, id,
    stars_x2)` (upsert on the partial index, bumps `rated_at` even with the same value),
    `remove_rating` (DELETE + a dismissal, one transaction), `dismiss_prompt` (`ON CONFLICT DO
    NOTHING`, first `dismissed_at` kept): visible title only else 404, wrapped in
    `fk_to_unauthorized()` (401); `list_activity(session, profile_id, page, page_size)` (every
    visible rating, `rated_at DESC, id DESC`, `item` = the viewer's summary; ≤ 8 queries, 2 empty).
  - `hometv/people.py` (actor screen): pure `normalize_credits`/`normalize_person`/`is_fresh`
    (`PERSON_TTL` 30 d, `PERSON_FETCH_WAIT_SECONDS` 5); `PersonService` (`app.state.people`:
    `detail()` single-flight fetch task per person in `_inflight`, never-fetched waits
    `wait_seconds`, stale answers at once + background refresh, failure leaves the DB untouched;
    `_apply` = one write (columns + multi-row credit insert + `details_fetched_at`); `wait_idle`
    (tests, awaits only unfinished tasks), `stop()` (sets `_stopped`: later requests start no fetch,
    answered from the DB like the no-TMDB path); seams `now/wait_seconds/ttl`).
  - `hometv/scheduler.py`: `Scheduler` (single-flight scan -> enrich; `trigger(t, before=)`
    (`before` runs in a thread under the lock, before the run row), `ensure_idle`, `status`;
    `folders=` forwarded to every `Scanner`; `request_scoped(scopes)` -> pending queue + one kick
    task -> `watch` run (`Scanner(scope=)`); a full run clears the queue once its row exists and
    calls `on_full_run`; `run_scheduled` waits for a `watch` run (`_wait_watch_holder`,
    `_full_waiting`), `trigger` never waits; `on_run_finished(unreachable_roots)`,
    `watcher_status`; `wait_idle` covers kick + run), `scan_status(session, watcher)` (`scope`,
    `watcher` in `ScanStatus`).
  - `hometv/playback/`: `decide.py` (pure decision + `ClientCaps`), `stream.py` (byte ranges),
    `keyframe_readers.py` (pure MKV Cues / MP4 stss readers, ffprobe row parsing),
    `keyframes.py` (`KeyframeService`: cache, ffprobe fallback + rchar watchdog, failure map,
    background indexer = scanner `keyframe_hook`), `segments.py` (pure plan/check/playlist,
    run.csv verification, `split_fmp4` (bytes or mmap -> init + `media_ranges`, no media copy),
    init helpers), `hls.py` (`HlsSessionManager`: `_create_lock` makes check + register atomic;
    `open_segment()` -> `SegmentFile(file, size)` opened before eviction, the caller closes it,
    streamed by `stream.open_file_chunks`; `_stop_run` waits for an in-flight `finalizing` split),
    `subtitles.py` (`SubtitleService`), `procs.py` (spawn, `IDLE_PREFIX`, kill sequence).
  - `hometv/spa.py`: static `web_dist` serving + SPA fallback (`SpaRoute` catch-all for
    non-`/api` GET/HEAD, appended last by `install_spa`; `/assets/*` immutable, stale asset ->
    plain 404, else `index.html` no-cache; "Web UI not built" placeholder without a dist).
  - `hometv/main.py`: `create_app(settings, *, watch_backend_factory=None)` factory + lifespan
    (sets `app.state.db/settings/libraries/tmdb/tmdb_proxy/artwork/people/scheduler/watcher/
    keyframes/hls/subtitles`); order: `prepare_runtime` -> migrations -> `libraries.seed_once` ->
    `check_library_folders` -> `prepare_hls_dir` (sets `hls_ready`; the `finally` wipes the HLS
    dir only when set) -> services -> `Scheduler` -> `ChangeWatcher` (hooks `on_full_run`/
    `on_run_finished`/`watcher_status` bound) -> `scheduler.start()` -> `watcher.start()` (never
    awaits the arming walk); the `finally` stops the watcher before the scheduler and
    `people.stop()` before `tmdb.aclose()`; `run()`
    single-worker entry (console script `hometv`, the container CMD).
  - `hometv/api/`: `errors.py` (envelope, `unauthorized()` so services need not import `deps`,
    `fk_to_unauthorized()` context manager: FK `IntegrityError` -> 401, other ones re-raised), `deps.py` (auth deps), `schemas.py`,
    `list_params.py` (films/shows query params; `PageNumber` = `page` 1..`MAX_PAGE` (1 000 000) and the strict `WatchedParam` (`"true"|"false"`) + `watched_flag`, also used by the watchlist list), `routes_*.py` (system, profiles, catalog,
    films, shows, people (async `GET /api/people/{id}`, `PeopleDep`), watch, watchlist
    (`PUT`/`DELETE /api/watchlist/{films|shows}/{id}` 204, `GET /api/watchlist?type=&sort=&watched=` →
    `Page[WatchlistEntry]` (`kind`, `item`, `added_to_list_at`); Bearer only), ratings (tag
    `ratings`: `PUT`/`DELETE /api/ratings/{films|shows}/{id}` 204 (`RatingRequest.stars` ½..5 in ½
    steps, strict float), `POST .../dismiss-prompt` 204, `GET /api/activity?page=&page_size=` →
    `Page[ActivityEntry]` (`profile`, `kind`, `item`, `stars`, `rated_at`); Bearer only), artwork, admin,
    media).
  - `tests/`: `unit/` (pure), `service/` (tmp dirs + SQLite, stubs), `api/` (in-process
    httpx), `integration/` (real ffmpeg/ffprobe, marker `integration`; `test_inotify_backend.py`
    also marked `inotify`: no ffmpeg needed, skipped off Linux), `perf/` (marker `perf`, incl.
    `test_scoped_scan_perf.py`, `test_people_perf.py`, `test_watchlist_perf.py`, `test_ratings_perf.py`), `fixtures/`, `factories.py` (`add_film`/
    `add_show`/`add_file(root=, rel=, library=)`/`add_progress`/`add_person`/`add_cast_credit`/
    `add_person_credit`/`add_watchlist_entry(s, profile_id, film=|show=, added_at=)`/`add_rating(s,
    profile_id, film=|show=, stars_x2=8, rated_at=)`/`add_rating_dismissal(s, profile_id, film=|show=,
    dismissed_at=)`/`add_profile(s, name)`),
    `stubs.py` (`FakeProc`, `Spawner`, `ScaledClock`, `wait_until`, `FakeWatchBackend` (`emit`,
    `calls`/`ops`, `fail_with`, `alive`, `gate(op)`/`open_gate`), `BackendFactory`, `capture_selects`/
    `capture_writes` (SQL statement recorders for query-count tests)),
    `fmp4_builder.py` (synthetic fMP4), `hls_stub.py` (argv-driven stub ffmpeg for API tests).
- `web/`: React 18 + TS (strict, `noUncheckedIndexedAccess`, alias `@/`) + Vite 5 SPA, Node 24
  (`.nvmrc`), TanStack Query 5, React Router 6 data router, hls.js (lazy), CSS Modules over
  `styles/tokens.css`, no UI kit. `tsconfig.json` = references to `tsconfig.app.json` (src +
  tests) and `tsconfig.node.json` (configs + `tests/e2e`).
  - `src/strings.es.ts`: `S`, the only UI string catalog (es-ES; functions for values/plurals;
    `S.ratings.stars(n)` = the star text equivalent "4,5 estrellas"/"1 estrella" via `lib/intl`
    `starsFormat`, `S.ratings.average` "4,0 (3)" via `oneDecimalFormat` (also `formatRating`), `S.activity.*`, `S.cards.withRating`).
  - `src/api/`: `client.ts` (`apiFetch`, 401 hook, `X-Admin-Token`), `errors.ts` (`ApiError`,
    `errorMessage`), `types.ts` (REST shapes, snake_case; `TitleKind` = `RatingKind`/`WatchlistKind`), `kind.ts` (`kindSegment(kind)` → `films|shows`), one module per area,
    `queryKeys.ts` (`queryKeys`, `mutationKeys` (`watchlistWrite(kind, id)`, `ratingWrite(kind, id)`), `staleTimes`
    (`activity: 0`), `invalidateAfter(event)`, `title(kind, id)` = the film/show detail key; `['person', id]` /
    `personAll()` and `watchlistAll()` (`['watchlist']`; list key `watchlist({type, sort, watched})`) are
    invalidated by `playerExit`/`watchedFilm`/`watchedEpisodes`/`catalogChanged`; event
    `watchlistChanged {kind, id}` → the film/show detail + `['watchlist']`; `activity()` =
    `['activity', 'list']` / `activityAll()` only by `catalogChanged` and event `ratingChanged {kind, id}` →
    the detail, `['activity']`, that kind's library lists, home, search, person, watchlist (every
    card showing `my_rating`); watch events never touch `['activity']`, dismissing a prompt invalidates nothing),
    `queries.ts`, `people.ts` (`getPerson`), `watchlist.ts` (`getWatchlist`/`addToWatchlist`/
    `removeFromWatchlist`, `WATCHLIST_PAGE_SIZE` 50), `ratings.ts` (`setRating(kind, id, stars)`/
    `removeRating`/`dismissRatingPrompt`/`getActivity`, `ACTIVITY_PAGE_SIZE` 50), `watch.ts`
    (`putProgress` → `ProgressReport` (`Progress` + `rating_prompt`), `setWatched` → `WatchedResponse`
    with `rating_prompt`), `paging.ts` (generic `nextPageParam<T>`, shared
    by the library grids, Mi lista and Actividad).
  - `src/auth/`: per-tab session (`AuthProvider`, `session.ts`), `BootGate`/`RequireProfile`/
    `RequireAdmin`, in-memory `adminUnlock`, `useAdminAction` (keypad + one retry on
    `admin_locked`), `AdminPinModal`. `AppProviders` (Query, Toast, Auth, `RatingPromptHost`, AdminPin;
    optional `subscribeLocation` built by `lib/routerLocation.ts` `locationSubscriber(router)` in
    `main.tsx` and the test harness: committed location changes only, as `LocationChange {pathname, previousPathname, action}`; `isSamePageReplace`).
  - `src/player/`: pure `capabilities`, `versions` (`selectVersion`/`startPosition`/`nextPart`),
    `playerMachine` (reducer), `tracks`, `nextEpisode`, `progressSync`; I/O hooks
    `usePlaybackSession` (POST/preparing/attach/keep-alive/recovery/exit), `attachSource`
    (direct, hls.js, native iOS HLS), `useProgressReporter` (`PositionTracker` attach
    generations; `ratingPrompt` state = the latest non-null `rating_prompt` of a non-final report,
    `onFinalPrompt` option read at send time → `registerFinalReport(qc, item, put, onPrompt)` caches
    the `Progress` keys only, then calls `onPrompt`), `subtitleLoader` (Bearer fetch → blob `<track>`), `useMediaSession` (headset/
    lock-screen play/pause/seek handlers + metadata, feature-detected); `PlayerRoute` is the stable
    shell (fullscreen root + the single `<video>`, `PlayerShellContext`; holds the rating host's
    `suppress()` while mounted), `PlayerPage` is keyed by
    `pathname + ?file=` and holds all per-playback state (rating end state: `endedAtLastItem` (waits
    for next-up) → `endedWithRating` (`reporter.ratingPrompt` and not `ratingHandled`) renders the
    inline `RatingForm` in `styles.prompt` instead of "Fin de la reproducción", disables
    `shortcutsEnabled` (keys + media session); one no-deps unmount effect over the `exitState` ref: form
    visible → `markHandled` + `dismissInBackground` unless `isMutating(ratingWrite)`, else `offer(prompt)`).
  - `src/features/<screen>/` (setup, profiles, home, library, search, film, show, person, watchlist, ratings,
    activity, admin, about),
    `features/ratings/`: `ratingPrompt.ts` (`RatingPromptContext` (no-op without a host),
    `useRatingPrompt()` → `offer`/`markHandled`/`suppress`, `SubscribeLocation`), `RatingPromptHost`
    (in `AppProviders`; one pending offer (newer replaces unopened), shown as `RatingDialog
    mode="prompt"` only with no suppression, `handled` keys (added on open / `markHandled`), cleared
    on a profile change; while shown it subscribes to `subscribeLocation` (a same-pathname REPLACE is not a navigation): `/profiles`|`/setup` =
    close, no dismissal, key forgotten; any other path = close + `dismissInBackground` unless
    `isMutating(ratingWrite)`), `RatingDialog.tsx` (`RatingForm(mode prompt|manual, target, initial,
    onClose? (required by `RatingDialog`, omitted inline), onAnswered?, notify?, inline?)`: `<form>`, `StarInput onEnter=save`, prompt mode marks
    handled at the first save and at Ahora no, one `answerSecondary` (Escape: Modal's `onClose`, or a
    `document` listener when `inline`; ignored while saving), error line + [Reintentar], [Quitar
    puntuación] (manual, rated); `RatingDialog` = `Modal` + form), `useRatingWrite(kind, id, {notify})`
    (`save`/`remove`/`saving`; optimistic `my_rating` patch, rollback, `ratingChanged` on settle (fire-and-forget: the dialog closes before the refetch; pending buttons use `aria-disabled`, a failed save focuses [Reintentar]);
    toasts in the `useMutation` options: `notify` while mounted (layout-effect `mounted` ref), else the
    app toast ("form gone" rule); removals: no success toast, `updateError` on failure;
    `dismissInBackground` = fire-and-forget + `logApiError`), `RatingsBlock` (detail `extra` slot:
    "Tu puntuación" [Puntuar]/[Cambiar] → manual dialog, "Media de la familia" (icons `roundToHalf`,
    text "4,0 (3)"), other profiles 3 + "y N más"/"Ver menos" (`aria-expanded`), `aria-disabled` (focusable, click guarded) while
    `useIsMutating(ratingWrite)`), `Ratings.module.css`;
    `features/activity/`: `ActivityPage` (`/activity`, eager; `<h1>` "Actividad" labels the `<ol>`;
    infinite query, `refetchOnMount: 'always'`, deduped by `profile-kind-id`, `LoadMoreFooter`, empty
    text, skeleton/`ErrorState`), `ActivityRow` (one `<Link>` named `S.activity.row`/`ownRow` with the
    star text: avatar, sentence + `StarDisplay`, `<time>` `formatActivityTime`, 46×69 poster; phones:
    time under the sentence),
    `features/watchlist/` ("Mi lista"): `WatchlistPage` (`/watchlist`, eager; single `<h1>` "Mi lista" →
    "Mi lista · {total}" once page 1 loads; `WatchlistToolbar` = type buttons (`aria-pressed`) + "Estado"
    `<select>` (Todos/Vistos/No vistos) + sort `<select>`, no order toggle, pressed type = accent fill; `PosterGrid` + `LoadMoreFooter`, items deduped by `type-id`; empty
    vs filtered empty + "Ver todo" (type only: per-type text, `watched` set: `noMatchFilters`)),
    `useWatchlistParams` (URL `?type=films|shows&watched=true|false&sort=title|year`, defaults/invalid
    not written, replace navigation, `setWatched`, `showAll` clears type + watched, keeps sort; setters build on the latest requested
    params until the URL changes from outside, no ref written in render), `useWatchlistToggle(kind, id, inList)`
    (optimistic `in_watchlist` patch of the detail key, rollback + toast, `watchlistChanged` on settle,
    `pending` = `useIsMutating(mutationKeys.watchlistWrite)` so it survives a remount,
    no success toast; used by Film detail's secondary row and Show detail's hero). Film detail's
    `FilmWatchedToggle`, Show detail's `MarkWatchedButton` and `EpisodeList` `offer` a
    `rating_prompt` from `POST /api/watched` (`onSuccess`),
    `features/person/`: `PersonPage` (`/people/:id`, eager; `NotAvailable` (also a 404 on refetch)/skeleton/
    `ErrorState` (only without cached data)/keyed `PersonView`: header photo `Artwork kind="poster"` + initials placeholder, name, life span,
    place, then "Biografía" in that text column (below the header under `PHONE_QUERY`); "En tu catálogo" = `PosterGrid` + `subtitleFor`, empty text, `credits_complete`
    note), `Biography` (4-line clamp, "Leer más/menos" only when `useOverflows`), pure `lifeSpan`
    (`formatLongDate`/`ageOn`/`todayIso`/`lifeSpanText`, `Date.UTC` round-trip validation) and
    `appearanceSubtitle`,
    `features/admin/`: `AdminLayout` (tabs Escaneo/Bibliotecas/Sin identificar/Perfiles, the one
    scan poll: `scanRefetchInterval` while `running` or `watcher.state === 'starting'`), `ScanPage`
    (card + "Carpetas:" scope line + "Detección de cambios" watcher lines), `scanSummary`
    (`scanFinishedToast`, `isScanStatus`, pure `scopeLabel`), `LibrariesPage` (Bibliotecas: pure `libraryDraft` reducer, save = plan → server-built
    confirmation → PUT; results on the page), `AddFolderDialog`, `FolderBrowser` (keeps the last good
    listing as the way back), `adminQueries` (`useLibrariesQuery`, `useBrowseQuery`),
    `src/components/` (shared UI; `LoadMoreFooter` (sentinel + "Cargar más"/retry, Library and Mi lista; strings `S.common.loadMore`/`loadMoreError`),
    `Toolbar.module.css` (toolbar/field/input/select classes shared by both toolbars); `Artwork` `largeAt2x` (false = `small` only) and `placeholder`
    (null/failed image); `PosterCard`/`SummaryCard` `subtitle` (also in the link name via
    `S.cards.withSubtitle`) and `myRating` (bottom-left "★ 4,5" badge, above the progress bar,
    name + `S.cards.withRating`; `SummaryCard` and Home's `ContinueFilm` pass `my_rating`), `PosterGrid` `subtitleFor`;
    `StarRating` (inline SVG `StarIcon` (`data-fill`, half = `clipPath`), `StarDisplay` (`role="img"`,
    `label` override), `StarInput` (`role="slider"` ½…5: ←/↓ →/↑ Home End, Enter → `onEnter`, every
    handled key prevented + stopped, Space not handled; pointer halves via `starsFromPointer`, mouse
    hover preview; 40 px hit areas); colour token `--color-rating` (`styles/tokens.test.ts` checks
    ≥ 3:1 on bg/surface/surface-2, dark-only theme)); `CastRow` items are `<Link>`s to
    `/people/{person_id}` named "name, character", `small` only, initials circle; `WatchlistToggle`
    (presentational: "Mi lista"/"En Mi lista", named "Añadir a/Quitar de Mi lista", `aria-pressed`);
    `NavBar` links Inicio/Películas/Series/Mi lista/Actividad (`.link` nowrap); below 960 px
    (`SEARCH_ICON_QUERY`) a "Buscar" magnifier `NavLink` (48×48, `.searchIcon`) replaces `NavSearch`
    (`min-width: 160px`) and stays before the avatar in the compact bar; `MobileTabBar` = the five sections
    as equal tabs, never truncated (`NavBar.styles.test.ts` checks the CSS source)), `src/lib/`
    (`format` (incl. `formatActivityTime(iso, now)`: ahora mismo / hace N minutos|horas (same day) /
    ayer / "12 oct" / "12 oct 2025", local calendar days; `''` for an unparseable timestamp), pure `stars` (`starFills`, `stepStars`,
    `starsFromPointer`, `roundToHalf`), `routerLocation`, `languages`, `chunkReload`, `RouteErrorBoundary`, `lazyRoute`, hooks incl.
    `useOverflows(ref, active, content)` (clamped-text overflow, re-measured on `content` change and
    by `ResizeObserver` when present, keeps its value while inactive)), `router.tsx` (lazy `/admin/*` and `/watch/*`).
  - `tests/`: `setup.ts` (MSW `onUnhandledRequest: 'error'`, media/TextTrack stubs, resets),
    `fixtures.ts` (`f.*` factories), `msw/handlers.ts` (`handlers`, `apiError`),
    `msw/server.ts` (`recordRequests()`), `renderWithProviders.tsx`, `player.tsx`, `fakeHls.ts`,
    `flush.ts`, `capsEnvs.ts`, `media.ts`, `e2e/smoke.spec.ts` (optional Playwright; also the 360 px
    tab bar + banner "Buscar", 768 px NavBar, 720/959 px (icon, no field, no overflow) and 960 px
    (field ≥ 160 px) layout checks and a Mi lista add/remove round trip). Fixtures `progressReport`,
    `ratingPrompt`, `otherRating`, `activityEntry`; MSW defaults: `PUT /progress` echoes with
    `rating_prompt: null`, `POST /watched` `{updated: 1, rating_prompt: null}`, rating writes 204,
    `GET /activity` empty.
- `deploy/`: `Dockerfile` (multi-stage linux/amd64: `server-build` uv venv, `web-build`
  `node:24-bookworm-slim` on `$BUILDPLATFORM` (`npm ci && npm run build`), `runtime`
  python:3.12-slim-bookworm + Debian ffmpeg 5.1 + `web/dist` in `/app/web/dist`, user `hometv`
  1000, healthcheck, CMD `hometv`; build context = repo root, `Dockerfile.dockerignore`
  whitelists `server/` and `web/` minus node_modules/dist), `docker-compose.yml`
  (app in the tailscale sidecar's netns, `HOMETV_HOST=127.0.0.1`, media `:ro`, no ports),
  `tailscale/serve.json`, `.env.example`, `README.md` (setup steps + "Configuration reference", the only tracked list of `HOMETV_*`
  settings).
- `docs/`: user guides: `server-installation-linux.md`, `server-installation-windows.md` (Docker
  Desktop + WSL 2; no native Windows: HLS throttling uses SIGSTOP/SIGCONT), `tv-app-installation.md`,
  `ipad-installation.md` (the web app in Safari over Tailscale; there is no iOS app).
- `tv/`: Android TV app, Gradle 8.14 wrapper, AGP 8.13, Kotlin 2.2, compile/target SDK 35,
  minSdk 26, JDK 21 (bytecode 17). Versions in `gradle/libs.versions.toml` (Media3 1.5.1, Compose
  BOM, tv-material, Retrofit 3 + OkHttp 4.12, kotlinx.serialization, Coil 2, DataStore, JUnit 5,
  Robolectric). `local.properties` (sdk.dir) and `keystore.properties` are gitignored.
  - `core/` (pure Kotlin/JVM, package `com.hometv.tv.core`): `api/` (`HomeTvApi` Retrofit,
    `ApiModels` DTOs, `ApiException`/`apiCall`, `ApiClients` (API / stream / validation OkHttp),
    `AuthInterceptor` Bearer for the server host only), `data/` (`SessionStore` StateFlow +
    `InMemorySessionStore`, `SessionGuard` single-flight `renew` → Renewed|ProfileGone|Unreachable, `currentProfileId()`,
    `withRenewal(Origin.Foreground|Background)`, repositories, `AppEvents`, `NextEpisodeResolver`
    (skips the playing multi-episode file), `CatalogRepository.person(id)` (`GET /api/people/{id}`, drops non film/show appearances),
    `ProfileRepository.verifyAdminPin` → `PinCheck` (the only PIN on the TV: server change; an
    expired token renews once, `invalid_pin` never renews), `PlaybackRepository` (`preparing` loop as a `Flow<PlaybackProgress>`, POSTs in
    `appScope`; `subtitleLoop`, keep-alive, DELETE), `WatchlistRepository` ("Mi lista": `list(type,
    sort, page, pageSize = 50, watched: Boolean? = null)` drops non film/show items; `write(kind, id, inList)` → `Deferred<Boolean>`,
    a per-title worker in `appScope` (latest wish wins, one request in flight, plain-lock map, the
    acknowledged value lives for one worker run; each wish records its profile and a follow-up made
    under a profile no longer current is dropped, its `Deferred` completed with the acknowledged value),
    `WatchlistWriteException(acknowledged, cause)`,
    `writing`/`isWriting`/`awaitIdle`; enums `WatchlistKind`/`WatchlistType`/`WatchlistSort` carry wire
    values only), `RatingsRepository` (ratings: `set`/`remove` run in `appScope` and are awaited, a second
    write for a title in `writing` (`RatingKey`) joins the in-flight one; `changes: SharedFlow<RatingChange>`
    on every successful write; `dismiss` fire-and-forget (`Origin.Background`, logged); `activity(page)`
    drops non film/show; `RatingKind(wire, segment)`), `WatchRepository.putProgress` → `ProgressReport`
    (`rating_prompt`), `setWatched` → `WatchedResult(updated, ratingPrompt)`), `playback/` (pure `ClientCapsBuilder`,
    `VersionSelector`, `TrackRules`, `TrackCheck`, `PlaybackErrorMapper`,
    `PlaybackPlanner`, `SeekAccumulator`, `ProgressReporter`, the `PlayerController` interface (its `Listener.onRemote*` carry
    media-session commands, no Media3 types in `core`)),
    `util/` (`ServerUrl`, `Format` (constant es-ES; `longDate`/`age`/`lifeSpan(birthday, deathday,
    today)` → structured `LifeSpan` Living|Deceased|DeathOnly, invalid dates = unknown; `stars(Stars)` "4,5",
    `shortDay(date, withYear)` "12 oct"), `Stars` (value class, halves 1..10: `of(Double?)` nearest ½,
    `fills()`/`fillsOf(null)` → `StarFill`, `step`), pure `relativeTime(at|iso, now, zone)` → `RelativeTime`
    JustNow|Minutes|Hours|Yesterday|Day (future = JustNow; bad iso = null), `LanguageNames`).
    `src/testFixtures`: `FakeHomeTvApi`, `TestServer` (MockWebServer routing), `F` media files.
  - `app/` (`com.hometv.tv`): `MainActivity` (offers every key to `KeyDispatch` before Compose
    focus; screens register with `KeyEventInterceptor { event -> Boolean }`), `di/AppContainer`
    (manual DI: store, `clients()` rebuilt per server URL, `guard`, repositories, `events`,
    `sessionExpired`, `appScope`, `codecs`, `playerLaunches`, `homeRefresh`, `detailRefresh`,
    `watchlist` + `watchlistMemory` (Mi lista sort for the app session; the type filter lives in the ViewModel and resets when the screen is left, like the Browse filters),
    `ratings` + `ratingPrompts` (every `ratings.changes` marks `homeRefresh` stale), `avatars` (`account.info`
    colours for other profiles), `zone` seam (default `ZoneId::systemDefault`: Actividad times, Person ages), one shared Coil cache base for the per-server `ImageLoader`s, one factory per
    ViewModel), `data/` (`ProfileAvatar` (pure `initial(name)`/`color(avatarId, avatars)`), `RatingPrompts` (`pending` `PendingPrompt(profileId, prompt)`, `offer(profileId?,
    prompt)` (null profile / handled key ignored, newer replaces untaken), `take(currentProfileId)` (another
    profile's prompt dropped, not handled; taken = handled for the process), `clear()` on a real
    (serverUrl, profileId) change and in `AppNavigator.authExpired`, never on switchProfile/changeServer),
    (`DataStoreSessionStore`, `HomeCache` (write IOExceptions are logged and swallowed),
    `HomeRefresh`, `DetailRefresh` (generation counter marked by `AppNavigator.play`; Film/Show
    detail and Person `onVisible()` on `ON_RESUME` refetch in place), `PlayerLaunches`),
    `playback/` (`AndroidCodecInventory`, `PlayerFactory` (`AUDIO_ATTRIBUTES` shared with the
    passthrough query), `HomeTvLoadErrorHandlingPolicy`, `MediaItemBuilder`, `SubtitleFileCache`
    (`get` answers from an in-memory index, no disk I/O), `ExoPlayerController` (the ExoPlayer is
    private; `attach/detach(PlayerView)`; owns a Media3 `MediaSession` over `RemoteCommandPlayer`
    while attached), `ExoTrackMapper`), `ui/`
    (`HomeTvRoot` NavHost + `ratings/RatingPromptHost` after it (the one root prompt: takes a pending prompt
    for the current profile when the current entry is RESUMED and not Player/Setup/Profiles, one at a time;
    leaving for Profiles/Setup `detach`es it with no dismissal; `RatingEditor.hostCloses`: 404 → close +
    `AppEvent.ItemGone`, ProfileGone → close, a 401 after renewal → FAILED), `navigation/` (`Route` incl.
    `Route.Person(id)`, `Route.Watchlist` and `Route.Activity`,
    `NavRequest.Person`, `AppNavigator.openPerson`, `openTab(NavTab.WATCHLIST)` above Home), `text/`
    (`UiText`, `UiFormat` (`Stars` → "4,5", `RelativeTime` → catalog; top-level `starsText(Stars?)` "4,5
    estrellas"/"1 estrella"/"Sin puntuación"), `StringResolver`), `components/` (`TopNav` (also `AvatarUi.initial/color/of`, delegating to the one avatar initial + colour rule in `data/ProfileAvatar`) tabs = `NavTab` order: Search,
    Inicio, Películas, Series, `WATCHLIST` "Mi lista", `ACTIVITY` "Actividad", Ajustes (admins);
    `PosterCard(…, subtitle, subtitleTestTag, myRating)` one muted line + bottom-left star badge `rating_badge`
    ("tu puntuación: 4,5 estrellas", raised above the progress strip; `PosterItem`/Home `ItemCard.myRating`),
    `ActionButton(contentDescription=)`,
    `TextDialog` (scrollable read-only
    text, Up/Down scroll ¾ viewport, Back closes), `Artwork(placeholder=)`), `common/ScreenError`
    (`PersonGone` → "Esta persona no está disponible." [Volver], no `AppEvent`), theme
    `HomeTvDimens.WIDE_FOCUSED_SCALE` (1.02, wide blocks), one package per screen; `detail/` ratings
    (`DetailRatings` helper per VM (`showsContent`: never republishes over Error/Loading, like `DetailWatchlistToggle`; callback `publishScreen`): optimistic save/remove, rollback, `rating_error_line` after a failed
    removal, a refetch while writing keeps the shown value, `ratings.changes` of its title → `mine` + silent
    refetch (`refetchPending`: a change during an active load fetches once more after it; the VMs expose
    `onRatingStep/Save/Secondary/Remove/Dismiss` delegating to it, the screens pass method refs); `RatingsBlock` tag `rating_block`: line 1 `rating_mine` chip (disabled while writing, dimmed 0.4;
    `RatingChipColors`: focused = `SurfaceVariant`/`OnBackground`; `StarRow(described = false)` inside it) +
    `rating_average`, line 2 ≤ 2 `rating_other_<i>` + `rating_more` "y N más" → `othersDialog` ListDialog
    `selected = -1`; `ButtonId.RATING_MINE/RATING_MORE` are focus ids only; Film: last FlowRow row (button
    tops via `onPlaced`) Down → `rating_mine`, cast `up` → `rating_mine`, overview 1/3 lines, the focus
    effect skips rating ids unless the `focusVersion` is new; Show: one-shot `focusButton`, top spacer 24 dp,
    overview 2 lines, block Down = `onMineDown`/`onMoreDown`: scroll the hoisted season
    `LazyListState` to the selected (else first) season, then `requestFocusWhenReady` (no seasons: stays), first season Up → `rating_mine`; mark watched offers
    `WatchedResult.ratingPrompt` to `RatingPrompts`); Film
    detail cast OK → `onCastSelected` → `NavRequest.Person`, `CastFocus(key, index)` restores the
    card on Back (`castKey` in `DetailModels`; `onButton` sets `focus = id` and clears `castFocus`
    before navigating); Film/Show detail "Mi lista" button `ButtonId.WATCHLIST` right after `WATCHED`
    (`watchlistButton(inList)` text + `description`, icon `ic_check`/`ic_add`): optimistic, no refetch,
    shared `DetailWatchlistToggle` (holds `confirmed`, the awaited `WatchlistRepository.write` and the
    inline error): confirm or roll back (`e.acknowledged ?: confirmed`) with `WatchlistErrorLine` (tag
    `watchlist_error`, `DetailText.secondary`, cleared by the next action), a late rollback only
    updates the model unless the screen shows Content; 404/401/ProfileGone go through `fail(e.cause)`
    (`ScreenError.replacesScreen()`); a refetch while `isWriting` keeps the shown value; Film detail
    buttons follow row order with Left/Right across `FlowRow` rows, overview `maxLines` 3 with ≥ 4
    buttons (else 5, tag `film_overview`); `browse/PosterGridPane` (the 6-column grid, `grid_<i>`,
    first-row Up on the header, failed-page row, focus restore; shared by Browse and Mi lista);
    `ratings/` (`RatingEditor` (dialog state: `RatingDialogUi(mode PROMPT|MANUAL, phase EDITING|SAVING|SAVED|FAILED)`;
    save/remove/step only in EDITING/FAILED and `!isWriting`, save → SAVING synchronously; Back/secondary
    no-ops while SAVING, SAVED closes at once (else after `SAVED_MILLIS` 1.5 s); `closesOn` `detailCloses`
    (404/401/ProfileGone → `onGone`) | `hostCloses`; `isItemGone(e)`; `detach()`), `RatingDialog` (520 dp, poster 96×144,
    star row `rating_stars` initial focus Left/Right ½ OK saves, one full-width button row `rating_save`|
    `rating_retry`, `rating_not_now`|`rating_cancel`, `rating_remove`; `rating_saved`, `rating_error`),
    `StarRow` (icons `ic_star_full/half/empty`, `HomeTvColors.Rating`, `emptyColor`), `RatingPromptHost`),
    `activity/` (`ActivityViewModel(ratings, store, clock, avatars)`: pages of 50, next page in the last 5
    rows, `onHidden`/`onVisible` reload in place (≤ 4 pages in one request) keeping the row by key, a
    `ratings.changes` reloads while visible (`reloadPending`: a change during page 1 or a reload runs one more reload); `ActivityScreen` tags `activity_header`/`_row_<i>`/`_empty`/
    `_retry`, Back → Home on the tab, focus restore: only a fresh `focus` version names the row, else `restoreIndex()`; `ActivityRow` 72 dp sentence with bold name/title from the catalog
    template, 20 dp stars, `UiFormat` time, 40×60 poster, one content description),
    `watchlist/` (`WatchlistViewModel`: Browse paging rules, `WatchlistDialog` SORT/TYPE/WATCHED
    ("Estado" `watched: Boolean?`, `watchedOptions` null/true/false, reset with the screen like the type),
    `empty` vs `noMatches` (type or Estado) + `showAll` (clears both), `headerFocusVersion` when no card, one paging job with `JobKind`
    PAGE1/MORE/RESUME (PAGE1 and RESUME `awaitIdle()` first), `onVisible()` (only after an `onHidden()` = `ON_STOP` since the last resume) = `awaitIdle()` then reload in place of
    `max(lastPage, 1)` pages (one request ≤ 4 pages, else pages of 50), focus by `lastFocusedKey` only
    after `onCardClick` or with `bodyHasFocus`; `WatchlistScreen` tags `watchlist_header`/`_sort`/`_type`/`_watched`/
    `_empty`/`_no_matches`/`_show_all`/`_retry`, Back → Home on the "Mi lista" tab); `person/` (`PersonViewModel`: `PersonUiState`, `AppearanceCard`,
    `PersonFocus` Bio|Card(key)|None changed only on OK / bio close / gone target, never per D-pad
    move; `PersonScreen`: 960×540 dp fit (1-line name, 3-line bio block, 1-line subtitle, note in the
    header), lazy rows use `focusRestorer(<remembered card>)`; Film cast and Person rows restore
    through `ui/components/FocusRestorer.kt` `LazyListState.focusItem` + `rememberFocusedItemKey`); `player/`
    (`PlayerViewModel` session state machine (rating prompt: `putAndRemember` wraps every progress PUT of
    both reporters and `releasedFinalPut`; `sessionPrompt` (latest wins, kept across attachments) is handed
    to `PlayerDeps.ratingPrompts` (no default) once by `exit()`/`onCleared()`, a later answer at once),
    `NextEpisodeResolution` (next-episode state + retry),
    `HlsSessionOwner` (HLS DELETEs and abandoned-POST orphans in `appScope`), `PlayerScreen` with Media3 `PlayerView`, overlay, track dialog, countdown, error
    screen)). `res/values/strings.xml` is the only catalog; the APK keeps only `es` resources
    (`localeFilters`).
  - Tests: `app/src/test` (ViewModel/glue tests, JUnit 5; `ui/player/PlayerSessionStateTest` covers
    the player session table with `FakePlayerController`; `TestRepos(appScope =)` gives the real
    repositories incl. `watchlist`, `ratings` and `ratingPrompts`, pass a scope on the test dispatcher for
    virtual time; `ActivityViewModelTest`, `RatingEditorTest`, `DetailRatingsTest`, `PlayerRatingPromptTest`), `app/src/sharedTest` (Compose focus/navigation
    tests on Robolectric via `UiTestBase` (`assertFullyVisible`/`assertInsideSafeArea`, overridable
    `clock` passed to `testContainer`, e.g. a fixed date for age texts; `testContainer(zone = clock.zone)`, so times never depend on the host zone) +
    `testContainer` + `ScriptedApi` (`onPerson` seam, 404 by default; `PersonFocusTest`;
    `onWatchlist(type, sort, page, pageSize, watched)` (empty page by default) / `onWatchlistWrite(segment, id, inList)` seams, gate a write
    by suspending in it; `WatchlistFocusTest`, `DetailWatchlistTest`; ratings: `onRatingWrite(segment, id,
    stars?)` (null = DELETE), `onRatingDismiss`, `onActivity` (empty page by default), `onProgressPrompt(body)`
    (the `rating_prompt` added to `onProgress`'s `Progress`); `RatingDialogFocusTest`/`FitTest` (dialog nodes
    sit in the dialog window: check them against `rating_dialog`, not the screen), `RatingPromptHostTest`,
    `DetailRatingsFocusTest`/`FitTest`, `ActivityFocusTest`); also compiled into `androidTest`
    (`UiTestPlatform.declareTelevision()` instead of Robolectric shadows in shared code).

## Commands

- `make install`: uv sync + `npm ci`. `make test`: `uv run pytest -m "not perf"`, then
  `npm test` in `web/`, then `make tv-test` (`make server-test` / `make web-test` run one side).
- Perf (browse p95 < 100 ms): `make perf` (`cd server && uv run pytest -m perf -s`).
- Deploy: `make docker-build` (linux/amd64 image), `make compose-config` (validates the compose
  file with `.env.example`); run with `docker compose up -d --build` in `deploy/`.
- `make lint`: `server-lint` (ruff check + format check + `uv run mypy`: `[tool.mypy]` in
  `server/pyproject.toml`, pydantic plugin, unused `# type: ignore` are errors) + web typecheck +
  `web-lint` (`npm run lint`: ESLint flat config `web/eslint.config.js`, react-hooks rules only) +
  `tv-lint`. `make format`: auto-fix (server).
- TV: `make tv-test` (`./gradlew :core:test :app:testDebugUnitTest`), `make tv-lint`
  (`:app:lintDebug`), `make tv-build` (`:app:assembleRelease`, needs `tv/keystore.properties`).
  The Makefile defaults `JAVA_HOME` to `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  and `ANDROID_HOME` to `/opt/homebrew/share/android-commandlinetools` when unset; export both
  before calling `./gradlew` directly. No emulator: `:app:assembleDebugAndroidTest` only compiles.
- `make run`: dev server on 127.0.0.1:8000 with data in `.dev-data/`, serving `web/dist`.
- Web: `make web-build` (`tsc -b && vite build`; initial JS ≤ 200 kB gzip, admin/player/hls.js
  are lazy chunks), `make web-dev` (Vite on :5173, `/api` proxied to `HOMETV_API_TARGET`),
  `make web-typecheck`, `make web-e2e` (Playwright smoke, Chromium, against `HOMETV_E2E_URL`;
  `npx playwright install chromium` once; `HOMETV_E2E_FILM=Dune` for the demo library).
- Single test: `cd server && uv run pytest tests/unit/test_parser.py -k row11`;
  `cd web && npx vitest run src/player/versions.test.ts -t "nextPart"`;
  `cd tv && ./gradlew :core:test --tests '*VersionSelectorTest*'`.

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

## TV conventions (`tv/`)

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
  ExoPlayer outside `ExoPlayerController`.
