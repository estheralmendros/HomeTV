# Deploying HomeTV

Operator reference. For step-by-step guides see
[`docs/server-installation-linux.md`](../docs/server-installation-linux.md) and
[`docs/server-installation-windows.md`](../docs/server-installation-windows.md).

HomeTV runs as two containers that share one network namespace:

- `tailscale`: joins your tailnet as `hometv` and runs `tailscale serve`, which terminates HTTPS
  on port 443 with the tailnet certificate and proxies to `http://127.0.0.1:8000`.
- `app`: the HomeTV server (uvicorn, one worker) bound to `127.0.0.1:8000`, with ffmpeg/ffprobe.

No port is published on the host or the LAN. The server is reachable only from tailnet devices
allowed by your ACL, at `https://hometv.<tailnet>.ts.net`.

## Requirements

- Linux host (amd64) with Docker Engine and the Compose plugin, and `/dev/net/tun`.
- A media directory with `Films/` and `TV/` subdirectories (mounted read-only; HomeTV never
  writes to it).
- A Tailscale account (admin access to the tailnet) and, optionally, a TMDB API key.

## Setup

1. **Tailnet DNS and certificates**: in the Tailscale admin console → DNS, enable **MagicDNS**
   and **HTTPS Certificates**.
2. **ACL**: in Access controls, add the tags and rule below, then tag the TV, laptop and phone as
   `tag:media-client` (Machines → ⋯ → Edit ACL tags). Merge with your existing rules: replacing
   the default allow-all policy also removes other access.

   ```json
   { "tagOwners": { "tag:media-server": ["autogroup:admin"], "tag:media-client": ["autogroup:admin"] },
     "acls": [ { "action": "accept", "src": ["tag:media-client", "autogroup:admin"], "dst": ["tag:media-server:443"] } ] }
   ```
3. **Auth key**: Settings → Keys → Generate auth key: **reusable**, **not ephemeral**, tagged
   `tag:media-server`.
4. **Environment**: in this directory, `cp .env.example .env` and set:
   - `TS_AUTHKEY`: the key from step 3;
   - `TMDB_API_KEY`: TMDB v3 key or v4 read token (empty = no metadata, items stay `pending`);
   - `MEDIA_DIR`: host path holding `Films/` and `TV/`.
5. **Start**: `docker compose up -d --build` (run in `deploy/`).
6. **First use**: open `https://hometv.<tailnet>.ts.net`, create the first admin profile (4-digit
   PIN), and wait for the startup scan (it starts 10 s after boot).
7. **TV app**: point it to the same URL (see `../docs/tv-app-installation.md`).

## Operations

- Status: `docker compose ps` (no published ports; `app` turns `healthy` once
  `/api/health` answers). Logs: `docker compose logs -f app`.
- Update: `git pull && docker compose up -d --build`.
- Data: the `hometv-data` volume holds `hometv.db`, `secret.key`, `artwork/`, `subtitles/` and
  `hls/` (HLS scratch, wiped on start and stop). Back up the volume to keep profiles and watch
  history. The `tailscale-state` volume keeps the node identity (do not delete it, or the node
  re-registers).
- Libraries: compose sets `HOMETV_FILMS_PATHS=/media/Films` and `HOMETV_SHOWS_PATHS=/media/TV`.
  Several roots: comma-separated lists, or a `config.yaml` in the data volume.
- Other settings (`HOMETV_*`, e.g. `HOMETV_MAX_SESSIONS`, `HOMETV_HLS_DIR` to put HLS scratch
  on an SSD/tmpfs, `HOMETV_SCAN_INTERVAL_HOURS`): add them to the `app` environment. The full
  list is the Configuration Reference in `specs/HOMETV-1/hometv-app/prd.md`.
- The image runs as user `hometv` (uid/gid 1000). The data volume is created owned by it; if
  you bind-mount a host directory at `/data` instead, `chown 1000:1000` it first.
- Exactly one uvicorn worker (`WEB_CONCURRENCY` other than 1 aborts startup): SQLite writes and
  HLS sessions live in one process.

## Building the image alone

From the repo root: `docker build --platform=linux/amd64 -f deploy/Dockerfile -t hometv .`
(ffmpeg/ffprobe 5.1 from Debian bookworm), or `make docker-build`. The `web-build` stage
(`node:24-bookworm-slim`, run on the build platform: the output is static files) runs `npm ci`
and `npm run build` in `web/` and copies `web/dist` to `/app/web/dist`, which the server serves at
`/` with SPA fallback (`index.html` no-cache, `/assets/*` immutable, a missing asset is a 404).
`web/package-lock.json` must be present (`npm ci`). The API stays at `/api` (docs at `/api/docs`).
