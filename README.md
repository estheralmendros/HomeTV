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
- `specs/HOMETV-1/hometv-app/`: the product specs. `prd.md` (server and deployment) is the
  source of truth and the API contract, `prd2.md` the web UI. `prd3.md` (TV app, `tv/`) is not
  implemented yet.
- `docs/`: user guides (e.g. installing the TV app).

## Development

Requires [uv](https://docs.astral.sh/uv/) and Node 24 (`web/.nvmrc`). ffmpeg/ffprobe are
optional: the server integration tests are skipped without them.

```bash
make install   # uv sync (server) + npm ci (web)
make test      # server: unit + service + API (+ integration with ffmpeg); then the web suite
make lint      # ruff check + format check (make format fixes), web typecheck
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

## Deployment

See [`deploy/README.md`](deploy/README.md). In short: set up the tailnet ACL and an auth key,
`cp deploy/.env.example deploy/.env`, fill in `TS_AUTHKEY`, `TMDB_API_KEY` and `MEDIA_DIR`,
then run `docker compose up -d --build` in `deploy/`. The app is served at
`https://hometv.<tailnet>.ts.net`.

## Configuration

Settings come from `HOMETV_*` environment variables, then an optional YAML file
(`HOMETV_CONFIG_FILE`, or `{data_dir}/config.yaml`), then defaults. Typical ones:
`HOMETV_FILMS_PATHS` / `HOMETV_SHOWS_PATHS` (comma-separated media roots, read-only),
`HOMETV_TMDB_API_KEY`, `HOMETV_DATA_DIR` (default `/data`) and `HOMETV_MAX_SESSIONS`. The full
list is the Configuration Reference in `specs/HOMETV-1/hometv-app/prd.md`.
