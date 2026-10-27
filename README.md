# HomeTV

Self-hosted media server for one household. HomeTV indexes the films and TV shows on a local
disk, fetches metadata and artwork from TMDB, keeps per-profile watch history, and plays video
in the browser and on Android TV / Google TV. Direct play when the client supports the file;
otherwise an on-the-fly HLS remux (video copied, audio converted when needed, no video
transcoding). It is reachable only over your Tailscale tailnet, with HTTPS.

The repo holds:

- `server/`: the HomeTV server (Python 3.12, FastAPI, SQLite, ffmpeg). It serves the REST API
  under `/api` (OpenAPI docs at `/api/docs`) and the web UI.
- `web/`: the web UI (React 18 + TypeScript + Vite SPA, Spanish only), built into `web/dist`
  and served by the server.
- `deploy/`: Docker image and Compose stack (the server plus a Tailscale sidecar).
- `tv/`: the Android TV / Google TV app (Kotlin, Compose for TV, Media3 ExoPlayer, Spanish
  only), viewer-only. See [`tv/README.md`](tv/README.md) and
  [`docs/tv-app-installation.md`](docs/tv-app-installation.md).
- `specs/HOMETV-1/hometv-app/`: the product specs. `prd.md` (server and deployment) is the
  source of truth and the API contract, `prd2.md` the web UI, `prd3.md` the TV app.
- `docs/`: user guides: installing the server on
  [Linux](docs/server-installation-linux.md) or [Windows](docs/server-installation-windows.md),
  the [Android TV app](docs/tv-app-installation.md), and using HomeTV on an
  [iPad](docs/ipad-installation.md).

## Development

Requires [uv](https://docs.astral.sh/uv/) and Node 24 (`web/.nvmrc`). ffmpeg/ffprobe are
optional: the server integration tests are skipped without them. The TV app needs JDK 21 and an
Android SDK with `platforms;android-35` + `build-tools;35.0.0`; the Makefile defaults
`JAVA_HOME`/`ANDROID_HOME` to the Homebrew paths (`openjdk@21`, `android-commandlinetools`) when
they are unset.

```bash
make install   # uv sync (server) + npm ci (web)
make test      # server: unit + service + API (+ integration with ffmpeg); web suite; TV JVM tests
make lint      # ruff check + format check (make format fixes), web typecheck, TV Android lint
make run       # dev server on http://127.0.0.1:8000, data in .dev-data/, serves web/dist
make perf      # browse-latency benchmark (not part of make test)
```

Web UI quick start (two terminals):

```bash
make run       # API on 127.0.0.1:8000
make web-dev   # Vite on http://localhost:5173, proxies /api to the server (HOMETV_API_TARGET)
```

`make web-build` builds `web/dist`; `make run` then serves it at `/` (without a build, `/` shows a
"Web UI not built" page). `make web-test` runs the Vitest suite (no network). The optional
Playwright smoke runs against a real server: `npx playwright install chromium` once, then
`HOMETV_E2E_URL=http://127.0.0.1:8000 make web-e2e` (add `HOMETV_E2E_FILM=Dune` for the demo library below). `specs/HOMETV-1/hometv-app/create-environment.sh`
starts a server with a generated demo library (admin "Admin", PIN 1234) that serves `web/dist`.
Contributor conventions (TDD, DB write rules, test layout) are in `CLAUDE.md`.

TV app: `make tv-test` (JVM tests, including the Robolectric focus tests), `make tv-lint`,
`make tv-build` (signed release APK; needs `tv/keystore.properties`). Install on the TV with
`adb install -r`; see [`docs/tv-app-installation.md`](docs/tv-app-installation.md).

## Deployment

Step-by-step guides: [Linux](docs/server-installation-linux.md),
[Windows](docs/server-installation-windows.md). Operator reference:
[`deploy/README.md`](deploy/README.md). In short: set up the tailnet ACL and an auth key,
`cp deploy/.env.example deploy/.env`, fill in `TS_AUTHKEY`, `TMDB_API_KEY` and `MEDIA_DIR`,
then run `docker compose up -d --build` in `deploy/`. The app is served at
`https://hometv.<tailnet>.ts.net`.

## Configuration

Settings come from `HOMETV_*` environment variables, then an optional YAML file
(`HOMETV_CONFIG_FILE`, or `{data_dir}/config.yaml`), then defaults. Typical ones:
`HOMETV_TMDB_API_KEY`, `HOMETV_DATA_DIR` (default `/data`), `HOMETV_MAX_SESSIONS`,
`HOMETV_MEDIA_BROWSE_ROOT` (default `/media`, the base of the folder browser), and
`HOMETV_SCAN_WATCH_ENABLED` / `HOMETV_SCAN_WATCH_QUIET_SECONDS` (change detection on Linux hosts:
new files are scanned about a minute after a copy ends; default on, 30 s). The media folders
of Películas and Series are managed in the web app, **Administración → Bibliotecas** (stored in
the database); `HOMETV_FILMS_PATHS` / `HOMETV_SHOWS_PATHS` are only read on the first start to
seed them, then ignored. The full list is the
[Configuration reference](deploy/README.md#configuration-reference).
