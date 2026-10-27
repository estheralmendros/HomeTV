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
- A media directory (one broad parent, e.g. a whole disk), mounted read-only at `/media`; HomeTV
  never writes to it. The film and TV folders inside it are chosen later in the web app.
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
   - `MEDIA_DIR`: host folder with your media, one broad parent (e.g. a whole disk). It is mounted
     read-only at `/media`; folder names such as `Films`/`TV` are not required.
5. **Start**: `docker compose up -d --build` (run in `deploy/`).
6. **First use**: open `https://hometv.<tailnet>.ts.net`, create the first admin profile (4-digit
   PIN), then open **Administración → Bibliotecas**: add the folders under `/media` that hold
   films (**Películas**) and TV shows (**Series**), typed or picked with the folder browser, and
   press **Guardar cambios**. The save starts a scan (see **Administración → Escaneo**).
7. **TV app**: point it to the same URL (see `../docs/tv-app-installation.md`).

## Operations

- Status: `docker compose ps` (no published ports; `app` turns `healthy` once
  `/api/health` answers). Logs: `docker compose logs -f app`.
- Update: `git pull && docker compose up -d --build`.
- Data: the `hometv-data` volume holds `hometv.db`, `secret.key`, `artwork/`, `subtitles/` and
  `hls/` (HLS scratch, wiped on start and stop). Back up the volume to keep profiles and watch
  history. The `tailscale-state` volume keeps the node identity (do not delete it, or the node
  re-registers).
- Libraries: the folders of **Películas** and **Series** are managed in **Administración →
  Bibliotecas** (stored in the database; a save starts a scan, no restart). Each library can have
  several folders, or none.
  - The folder browser explores `/media` (`HOMETV_MEDIA_BROWSE_ROOT`); typing a full path works
    everywhere.
  - A folder on a new host disk still needs a volume line in `docker-compose.yml` (e.g.
    `/srv/disk2:/media/disk2:ro`) and `docker compose up -d`; then add it in Bibliotecas.
  - Series layout: the Series folder is the one that holds one subfolder per show (episode files
    may also sit directly in it, named with the show and `S01E01`). Choose exactly that folder,
    not its parent or a show's subfolder. Changing it later re-reads the episodes from the new
    folder; shows whose name changes lose their watch history (the screen asks first). Film
    folders may be flat or split in subfolders (`A/`, `B/`…): film titles come from file names.
  - Moving a folder between Películas and Series deletes the watch history of its titles (the
    screen asks first). A removed folder's titles are hidden at once and deleted after the grace
    period (`HOMETV_SCAN_MISSING_GRACE_DAYS`) unless the folder is added back.
  - Upgrade: on the first start of this version the existing folders are kept automatically
    (from `HOMETV_FILMS_PATHS`/`HOMETV_SHOWS_PATHS` or `libraries.films|shows` in `config.yaml`,
    else from the already indexed files). Those settings are then ignored (the Bibliotecas screen
    says so) and can be removed. If older settings left some files in the wrong library or under
    another Series folder, nothing changes at startup; the first save in Bibliotecas lists them
    in its confirmation.
  - A typed folder with accents (e.g. `/media/películas`) reported as "La carpeta no existe en el
    servidor" although it exists: pick it with the folder browser instead (the name on disk may
    use another Unicode form, e.g. copied from a Mac). "No encontrada" / "Sin permiso" chips
    mean the path is not mounted or not readable by uid 1000.
- Change detection: new, renamed and deleted media are in the catalog about a minute after a
  copy ends (a 30 s quiet period, then a scan of the changed folder only and its metadata
  lookup; the scan card shows the trigger "cambio detectado" and the scanned folders). The
  periodic scan (`HOMETV_SCAN_INTERVAL_HOURS`) stays as a safety net.
  - Settings: `HOMETV_SCAN_WATCH_ENABLED` (default `true`) and `HOMETV_SCAN_WATCH_QUIET_SECONDS`
    (default `30`, minimum `5`) go under the `app` service's `environment:` in
    `docker-compose.yml` (not in `.env`, which only feeds the `${…}` substitutions), e.g.:

    ```yaml
      app:
        environment:
          HOMETV_HOST: 127.0.0.1
          HOMETV_SCAN_WATCH_QUIET_SECONDS: "60"   # optional; default 30
    ```

    then `docker compose up -d`.
  - Linux host limit: one inotify watch per library directory, counted against the host user
    with uid 1000 (shared with that user's own host processes). Check
    `cat /proc/sys/fs/inotify/max_user_watches`; raise it with
    `sudo sysctl fs.inotify.max_user_watches=524288` and persist it with
    `echo 'fs.inotify.max_user_watches=524288' | sudo tee /etc/sysctl.d/90-hometv.conf`, then
    press **Escanear ahora** (a full scan re-arms every folder after the limit error). The scan
    card shows "Detección de cambios: con errores" when the limit is reached ("Detalles
    técnicos" names `max_user_watches`). A detail "[Errno 24] Too many open files" (`EMFILE`
    at inotify start) means the per-user instance limit instead: check
    `cat /proc/sys/fs/inotify/max_user_instances` and raise it the same way (e.g.
    `sudo sysctl fs.inotify.max_user_instances=512`, persisted with
    `echo 'fs.inotify.max_user_instances=512' | sudo tee /etc/sysctl.d/91-hometv.conf`).
  - Right after a start the card may show "Detección de cambios: iniciando…" while the folders
    are registered (longer on a sleeping USB disk).
  - "No disponible en: …" lists folders that are not watched: network shares (NFS, SMB) and
    Docker Desktop bind mounts are not supported (new files appear with the periodic scan or
    **Escanear ahora**). After unplugging/replugging the disk, change detection resumes with the
    next **full** scan (periodic or **Escanear ahora**; a change-triggered scan does not re-arm
    folders); if the card still lists the folders, `docker compose restart app` (bind mounts do
    not follow a remount).
- Other settings (`HOMETV_*`, e.g. `HOMETV_MAX_SESSIONS`, `HOMETV_HLS_DIR` to put HLS scratch
  on an SSD/tmpfs, `HOMETV_SCAN_INTERVAL_HOURS`): add them to the `app` environment. The full
  list is the [Configuration reference](#configuration-reference) below.
- The image runs as user `hometv` (uid/gid 1000). The data volume is created owned by it; if
  you bind-mount a host directory at `/data` instead, `chown 1000:1000` it first.
- Exactly one uvicorn worker (`WEB_CONCURRENCY` other than 1 aborts startup): SQLite writes and
  HLS sessions live in one process.

## Configuration reference

Precedence (highest first): `HOMETV_*` environment variables, then the YAML file at
`HOMETV_CONFIG_FILE` (when set; otherwise `{data_dir}/config.yaml` if it exists), then the
defaults below. An invalid value aborts startup with a message naming the key.

| Env var | YAML key | Default | Description |
|---------|----------|---------|-------------|
| `HOMETV_CONFIG_FILE` | — | unset | YAML path; else `{data_dir}/config.yaml` if present |
| `HOMETV_DATA_DIR` | `data_dir` | `/data` | DB, secret, artwork, subtitles, HLS scratch |
| `HOMETV_FILMS_PATHS` | `libraries.films` (list) | empty | comma-separated absolute roots; only read on the first start to seed the Películas folders (then managed in **Administración → Bibliotecas**) |
| `HOMETV_SHOWS_PATHS` | `libraries.shows` (list) | empty | same, for the Series folders |
| `HOMETV_MEDIA_BROWSE_ROOT` | `libraries.browse_root` | `/media` | base folder of the Bibliotecas folder browser; missing = browser disabled |
| `HOMETV_TMDB_API_KEY` | `tmdb.api_key` | unset | TMDB v3 key or v4 read token |
| `HOMETV_TMDB_LANGUAGE` | `tmdb.language` | `es-ES` | primary metadata language |
| `HOMETV_TMDB_FALLBACK_LANGUAGE` | `tmdb.fallback_language` | `en-US` | used for empty fields |
| `HOMETV_TMDB_MAX_RPS` | `tmdb.max_rps` | `20` | TMDB client rate limit (requests per second) |
| `HOMETV_CAST_LIMIT` | `tmdb.cast_limit` | `15` | cast members stored per item |
| `HOMETV_SCAN_INTERVAL_HOURS` | `scan.interval_hours` | `6` | periodic full scans; `0` disables them |
| `HOMETV_SCAN_ON_STARTUP` | `scan.on_startup` | `true` | full scan 10 s after start |
| `HOMETV_SCAN_PROBE_CONCURRENCY` | `scan.probe_concurrency` | `2` | parallel ffprobe (raise for SSD/multiple disks) |
| `HOMETV_SCAN_MISSING_GRACE_DAYS` | `scan.missing_grace_days` | `7` | days a missing file stays hidden before it (and orphaned items/progress) is purged; `0` = purge in the same scan |
| `HOMETV_SCAN_WATCH_ENABLED` | `scan.watch_enabled` | `true` | change detection (inotify, Linux hosts only): a copy or move in a library folder triggers a scan of that folder; `false` = only periodic/manual scans |
| `HOMETV_SCAN_WATCH_QUIET_SECONDS` | `scan.watch_quiet_seconds` | `30` | seconds without changes (and with stable video sizes) before that scan starts; minimum `5` |
| `HOMETV_MAX_SESSIONS` | `playback.max_sessions` | `4` | concurrent HLS sessions (direct play is unlimited) |
| `HOMETV_HLS_DIR` | `playback.hls_dir` | `{data_dir}/hls` | HLS scratch dir (point at an SSD/tmpfs if `/data` is small); never inside a media folder, never equal to or an ancestor of `data_dir`; wiped at start and stop |
| `HOMETV_HLS_SEGMENT_SECONDS` | `playback.hls_segment_seconds` | `6` | minimum segment length (segments follow source keyframes) |
| `HOMETV_HLS_AHEAD_SECONDS` | `playback.hls_ahead_seconds` | `120` | ffmpeg is paused once this much is ready ahead of the player |
| `HOMETV_HLS_BEHIND_SECONDS` | `playback.hls_behind_seconds` | `60` | segments further behind the player are deleted |
| `HOMETV_HLS_MAX_DISK_MB` | `playback.hls_max_disk_mb` | `4096` | total cap for `hls_dir` |
| `HOMETV_SESSION_IDLE_TIMEOUT_SECONDS` | `playback.session_idle_timeout_seconds` | `120` | idle HLS session cleanup |
| `HOMETV_SECRET_KEY` | `auth.secret_key` | auto (`{data_dir}/secret.key`) | token signing key |
| `HOMETV_TOKEN_TTL_DAYS` | `auth.token_ttl_days` | `30` | profile token lifetime |
| `HOMETV_ADMIN_TOKEN_TTL_MINUTES` | `auth.admin_token_ttl_minutes` | `15` | admin unlock token lifetime |
| `HOMETV_HOST` | `server.host` | `0.0.0.0` | listen address (the compose file sets `127.0.0.1`) |
| `HOMETV_PORT` | `server.port` | `8000` | listen port |
| `HOMETV_WEB_DIST` | `server.web_dist` | `/app/web/dist` | built web app served at `/` |
| `HOMETV_CORS_ORIGINS` | `server.cors_origins` | empty | comma-separated; development only |
| `HOMETV_LOG_LEVEL` | `server.log_level` | `info` | `debug`, `info`, `warning`, `error`, `critical` |
| `HOMETV_FFMPEG_PATH` | `ffmpeg.ffmpeg_path` | `ffmpeg` | ffmpeg binary |
| `HOMETV_FFPROBE_PATH` | `ffmpeg.ffprobe_path` | `ffprobe` | ffprobe binary |

Example `config.yaml`:

```yaml
tmdb: { api_key: "xxxx", language: es-ES, fallback_language: en-US }
scan: { interval_hours: 6, watch_quiet_seconds: 30 }
playback: { max_sessions: 4 }
```

## Building the image alone

From the repo root: `docker build --platform=linux/amd64 -f deploy/Dockerfile -t hometv .`
(ffmpeg/ffprobe 5.1 from Debian bookworm), or `make docker-build`. The `web-build` stage
(`node:24-bookworm-slim`, run on the build platform: the output is static files) runs `npm ci`
and `npm run build` in `web/` and copies `web/dist` to `/app/web/dist`, which the server serves at
`/` with SPA fallback (`index.html` no-cache, `/assets/*` immutable, a missing asset is a 404).
`web/package-lock.json` must be present (`npm ci`). The API stays at `/api` (docs at `/api/docs`).
