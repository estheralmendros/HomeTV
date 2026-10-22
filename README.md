# HomeTV

Self-hosted media server for one household. HomeTV indexes the films and TV shows on a local
disk, fetches metadata and artwork from TMDB, keeps per-profile watch history, and plays video
in the browser and on Android TV / Google TV. Direct play when the client supports the file;
otherwise an on-the-fly HLS remux (video copied, audio converted when needed, no video
transcoding). It is reachable only over your Tailscale tailnet, with HTTPS.

The repo holds:

- `server/`: the HomeTV server (Python 3.12, FastAPI, SQLite, ffmpeg). It serves the REST API
  under `/api` (OpenAPI docs at `/api/docs`) and the web UI.
- `deploy/`: Docker image and Compose stack (the server plus a Tailscale sidecar).
- `specs/HOMETV-1/hometv-app/`: the product specs. `prd.md` (server and deployment) is the
  source of truth and the API contract. `prd2.md` (web UI, `web/`) and `prd3.md` (TV app,
  `tv/`) are not implemented yet.
- `docs/`: user guides (e.g. installing the TV app).

## Development

Requires [uv](https://docs.astral.sh/uv/). ffmpeg/ffprobe are optional: the integration tests
are skipped without them.

```bash
make install   # uv sync (server dependencies)
make test      # unit + service + API (+ integration when ffmpeg is installed)
make lint      # ruff check + format check (make format fixes)
make run       # dev server on http://127.0.0.1:8000, data in .dev-data/
make perf      # browse-latency benchmark (not part of make test)
```

Without a built web UI, `/` shows a "Web UI not built" page. Use `/api/docs` to explore the
API. Contributor conventions (TDD, DB write rules, test layout) are in `CLAUDE.md`.

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
