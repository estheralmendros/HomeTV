# HomeTV

Self-hosted, single-household media server (films + TV shows on a local disk, TMDB metadata,
playback on web and Android TV over Tailscale). The REST API (`/api/openapi.json`, `/api/docs`)
is a contract shared by the web and TV clients: change it deliberately and update
`web/src/api/types.ts` and `tv/core/.../api/ApiModels.kt` in the same change.

## Layout

- `server/`: Python 3.12 uv project (FastAPI, SQLAlchemy 2 sync, Alembic, guessit).
  Module map and server conventions: `server/CLAUDE.md`.
- `shared/track-choice-cases.json`: the matching rule's case table (`prd.md` §2 of
  `specs/subtitles-improvements`: 24 rows, 11 and 22 split per mode = 26 entries; `expected` +
  `expected_web`/`expected_tv` overrides, case 24 `pick`). Read by path by the web and TV suites,
  never imported (the Docker web build context has no `shared/`); the server only checks its shape
  (`tests/unit/test_track_choice_cases.py`).
- `web/`: React 18 + TS (strict, `noUncheckedIndexedAccess`, alias `@/`) + Vite 5 SPA, Node 24
  (`.nvmrc`), TanStack Query 5, React Router 6 data router, hls.js (lazy), CSS Modules over
  `styles/tokens.css`, no UI kit. `tsconfig.json` = references to `tsconfig.app.json` (src +
  tests) and `tsconfig.node.json` (configs + `tests/e2e`).
  Module map and web conventions: `web/CLAUDE.md`.
- `deploy/`: `Dockerfile` (multi-stage linux/amd64: `server-build` uv venv, `web-build`
  `node:24-bookworm-slim` on `$BUILDPLATFORM` (`npm ci && npm run build`), `runtime`
  python:3.12-slim-bookworm + Debian ffmpeg 5.1 + `web/dist` in `/app/web/dist`, user `hometv`
  1000, healthcheck, CMD `hometv`; build context = repo root, `Dockerfile.dockerignore`
  whitelists `server/` and `web/` minus node_modules/dist), `docker-compose.yml`
  (app in the tailscale sidecar's netns, `HOMETV_HOST=127.0.0.1`, media read-only bind with `propagation: ${MEDIA_PROPAGATION:-rslave}` (disks mounted later under `MEDIA_DIR` appear without a restart; `rprivate` on Docker Desktop), no ports),
  `tailscale/serve.json`, `.env.example`, `README.md` (setup steps + "Configuration reference", the only tracked list of `HOMETV_*`
  settings).
- `docs/`: user guides: `server-installation-linux.md`, `server-automation-linux.md` (systemd
  unit for `docker compose up -d` at boot, 02:00 shutdown timer + `rtcwake` alarm for 15:00),
  `server-installation-windows.md` (Docker
  Desktop + WSL 2; no native Windows: HLS throttling uses SIGSTOP/SIGCONT), `tv-app-installation.md`,
  `ipad-installation.md` (the web app in Safari over Tailscale; there is no iOS app).
- `tv/`: Android TV app, Gradle 8.14 wrapper, AGP 8.13, Kotlin 2.2, compile/target SDK 35,
  minSdk 26, JDK 21 (bytecode 17). Versions in `gradle/libs.versions.toml` (Media3 1.5.1, libvlc-all 3.7.2 (libVLC 3
  API, never 4.x; 3.7.3+ AARs need compileSdk 36/37), Compose
  BOM, tv-material, Retrofit 3 + OkHttp 4.12, kotlinx.serialization, Coil 2, DataStore, JUnit 5,
  Robolectric). libVLC's native code limits the APK to `ndk.abiFilters` armeabi-v7a + arm64-v8a (one universal
  APK, ≈ 95 MB, `.so` stored uncompressed; no x86); `proguard-rules.pro` keeps `org.videolan.libvlc.**` (JNI; the
  AAR has no consumer rules). `local.properties` (sdk.dir) and `keystore.properties` are gitignored.
  Module map and TV conventions: `tv/CLAUDE.md`.

### Area notes (`server/CLAUDE.md`, `web/CLAUDE.md`, `tv/CLAUDE.md`)

Each area keeps its module map and conventions in its own `CLAUDE.md`, which is not loaded at
startup. Before reading or changing code under `server/`, `web/` or `tv/`, read that folder's
`CLAUDE.md` once with the Read tool (a shell `cat`/`grep` does not load it). Read only the areas the
task touches. When a change alters what an area file describes, update that file, not this one.

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

- Release version: `version.txt` (repo root) is the only file a bump edits: no other file and no
  command is part of a version bump. The TV app reads it in `tv/app/build.gradle.kts` (`versionCode` =
  M×10000 + m×100 + p); the server package takes it at build time (`server/pyproject.toml`
  `dynamic = ["version"]` + `[tool.hatch.version] path = "../version.txt"`; `[tool.uv] cache-keys` makes
  the next `uv sync`/`uv run` reinstall, so `hometv.__version__`, shown in "Acerca de", follows; `uv.lock`
  holds no version; the image copies `version.txt` to `/version.txt` before the project install); the web
  package has no version. `server/tests/unit/test_version.py` guards all three (no `version` in
  `pyproject.toml` `[project]`, `package.json` or `package-lock.json`).

## Conventions

- **Strict TDD**: write the failing test first, confirm it fails for the right reason, make it
  pass, refactor, run the full suite. `make test` and `make lint` must pass.
- **Language**: identifiers, API fields, error codes, logs and comments are English.
  Server-produced text shown to viewers is Spanish (es-ES), e.g. `tmdb_attribution` and the
  MediaFile `label` fallback `"Desconocido"`.
