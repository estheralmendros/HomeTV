#!/usr/bin/env bash
# HomeTV: local demo environment (server + web app, optionally the TV app).
#
# Usage: create-environment.sh [--tv]
#   --tv  also builds the TV debug APK (tv/, JDK 21 + Android SDK) and installs it with
#         `adb install -r` on every device `adb devices` lists (a TV over `adb connect`, or an
#         emulator). Without --tv the summary still prints the commands to do it by hand.
#
# - installs server dependencies (uv) and web dependencies (npm ci, Node 24), builds the web
#   app into web/dist
# - generates a small demo media library with ffmpeg (films, a TV show, subtitles,
#   a file that cannot be parsed)
# - starts the HomeTV server on http://127.0.0.1:${PORT:-8000} (no Docker / Tailscale)
# - bootstraps an admin profile (PIN 1234) and a viewer profile, triggers a scan
#   and waits for it to finish
#
# Idempotent: clips already generated are reused, `npm ci` runs only when web/node_modules is
# missing or package-lock.json changed, a server started by a previous run is stopped first,
# existing profiles are kept. Ctrl-C stops the server.
#
# Optional env: PORT (default 8000), TMDB_API_KEY (metadata/posters; without it
# items stay "pending" and have no artwork; when unset, read from .dev-data/tmdb.key if that
# file exists), RESET=1 (wipe DB and data first).
set -euo pipefail

BUILD_TV=0
for arg in "$@"; do
  case "$arg" in
    --tv) BUILD_TV=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) printf 'unknown argument: %s (use --tv or --help)\n' "$arg" >&2; exit 2 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_DIR="$REPO_ROOT/.dev-data/validation"
TMDB_KEY_FILE="$REPO_ROOT/.dev-data/tmdb.key"
if [[ -z "${TMDB_API_KEY:-}" && -s "$TMDB_KEY_FILE" ]]; then
  TMDB_API_KEY="$(tr -d '[:space:]' < "$TMDB_KEY_FILE")"
fi
DATA_DIR="$ENV_DIR/data"
MEDIA_DIR="$ENV_DIR/media"
PID_FILE="$ENV_DIR/server.pid"
LOG_FILE="$ENV_DIR/server.log"
PORT="${PORT:-8000}"
BASE="http://127.0.0.1:$PORT"
WEB_DIR="$REPO_ROOT/web"
WEB_LOCK_STAMP="$ENV_DIR/web-lock.sha256"
ADMIN_NAME="Admin"
ADMIN_PIN="1234"
VIEWER_NAME="Invitado"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

for bin in uv ffmpeg ffprobe curl jq; do
  command -v "$bin" >/dev/null 2>&1 || die "'$bin' is required (brew install $bin)"
done
for bin in node npm; do
  command -v "$bin" >/dev/null 2>&1 || die "'$bin' is required: install Node.js 24 (brew install node@24, or nvm install 24)"
done
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
(( NODE_MAJOR >= 24 )) || die "Node.js >= 24 is required for web/ (found $(node -v))"

# --- stop a server left over from a previous run -------------------------------------------
if [[ -f "$PID_FILE" ]]; then
  old_pid="$(cat "$PID_FILE")"
  if kill -0 "$old_pid" 2>/dev/null; then
    log "Stopping previous HomeTV server (pid $old_pid)"
    kill "$old_pid" 2>/dev/null || true
    for _ in $(seq 50); do kill -0 "$old_pid" 2>/dev/null || break; sleep 0.2; done
  fi
  rm -f "$PID_FILE"
fi
if curl -fsS "$BASE/api/health" >/dev/null 2>&1; then
  die "port $PORT is already serving something else; set PORT=<free port>"
fi

if [[ "${RESET:-0}" == "1" ]]; then
  log "RESET=1: wiping $DATA_DIR"
  rm -rf "$DATA_DIR"
fi
mkdir -p "$DATA_DIR" "$MEDIA_DIR/Films" "$MEDIA_DIR/TV"

# --- dependencies ---------------------------------------------------------------------------
log "Installing server dependencies (uv sync)"
(cd "$REPO_ROOT/server" && uv sync --quiet)

# --- web app --------------------------------------------------------------------------------
lock_sha() { shasum -a 256 "$WEB_DIR/package-lock.json" | cut -d' ' -f1; }
if [[ ! -d "$WEB_DIR/node_modules" || ! -f "$WEB_LOCK_STAMP" \
      || "$(cat "$WEB_LOCK_STAMP")" != "$(lock_sha)" ]]; then
  log "Installing web dependencies (npm ci)"
  (cd "$WEB_DIR" && npm ci --no-audit --no-fund --loglevel=error)
  mkdir -p "$ENV_DIR" && lock_sha > "$WEB_LOCK_STAMP"
fi
log "Building the web app into $WEB_DIR/dist (npm run build)"
(cd "$WEB_DIR" && npm run build --silent >"$ENV_DIR/web-build.log" 2>&1) \
  || { tail -30 "$ENV_DIR/web-build.log"; die "web build failed (log: $ENV_DIR/web-build.log)"; }
[[ -f "$WEB_DIR/dist/index.html" ]] || die "web build produced no $WEB_DIR/dist/index.html"

# --- TV app (optional) ---------------------------------------------------------------------
# Same defaults as the Makefile: Homebrew openjdk@21 and android-commandlinetools.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"
command -v adb >/dev/null 2>&1 && ADB="$(command -v adb)"
TV_APK="$REPO_ROOT/tv/app/build/outputs/apk/debug/app-debug.apk"
TV_INSTALLED=""
if [[ "$BUILD_TV" == "1" ]]; then
  [[ -x "$JAVA_HOME/bin/java" ]] || die "--tv needs JDK 21 at \$JAVA_HOME ($JAVA_HOME): brew install openjdk@21"
  [[ -d "$ANDROID_HOME/platforms" ]] || die "--tv needs the Android SDK at \$ANDROID_HOME ($ANDROID_HOME): see tv/README.md"
  [[ -f "$REPO_ROOT/tv/local.properties" ]] || echo "sdk.dir=$ANDROID_HOME" > "$REPO_ROOT/tv/local.properties"
  log "Building the TV debug APK (tv/: ./gradlew :app:assembleDebug)"
  (cd "$REPO_ROOT/tv" && ./gradlew --quiet :app:assembleDebug >"$ENV_DIR/tv-build.log" 2>&1) \
    || { tail -30 "$ENV_DIR/tv-build.log"; die "TV build failed (log: $ENV_DIR/tv-build.log)"; }
  if [[ -x "$ADB" ]]; then
    for serial in $("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
      log "Installing the TV app on $serial (adb install -r)"
      "$ADB" -s "$serial" install -r "$TV_APK" >/dev/null && TV_INSTALLED+="$serial "
    done
  fi
  [[ -n "$TV_INSTALLED" ]] || log "No adb device connected: the APK was built but not installed"
fi

# --- demo media library ---------------------------------------------------------------------
# 60 s clips, 2 s GOP, small resolution: fast to generate, long enough to seek.
clip() { # clip <output> <extra ffmpeg args...>
  local out="$1"; shift
  [[ -s "$out" ]] && return 0
  mkdir -p "$(dirname "$out")"
  ffmpeg -nostdin -loglevel error -y \
    -f lavfi -i "testsrc2=size=640x360:rate=24:duration=60" \
    -f lavfi -i "sine=frequency=440:duration=60" \
    "$@" -t 60 "$out"
}

log "Generating demo media in $MEDIA_DIR (first run only)"
# Direct play in browsers: MP4 H.264 + AAC.
clip "$MEDIA_DIR/Films/Dune (2021)/Dune (2021).mp4" \
  -map 0:v -map 1:a -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -c:a aac -b:a 96k \
  -movflags +faststart
# Remux in browsers: MKV H.264 + AC3 5.1 (+ stereo AAC track) + embedded SubRip; plus a sidecar .srt.
SRT_TMP="$ENV_DIR/embedded.srt"
cat > "$SRT_TMP" <<'EOF'
1
00:00:02,000 --> 00:00:06,000
Subtítulo incrustado: hola.

2
00:00:30,000 --> 00:00:34,000
Subtítulo incrustado: mitad del vídeo.
EOF
clip "$MEDIA_DIR/Films/Blade Runner 2049 (2017)/Blade Runner 2049 (2017).mkv" \
  -i "$SRT_TMP" \
  -map 0:v -map 1:a -map 1:a -map 2:s \
  -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p \
  -c:a:0 ac3 -ac:a:0 6 -b:a:0 384k -c:a:1 aac -ac:a:1 2 -b:a:1 96k \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa -metadata:s:s:0 language=eng \
  -c:s srt
SIDECAR="$MEDIA_DIR/Films/Blade Runner 2049 (2017)/Blade Runner 2049 (2017).es.srt"
if [[ ! -s "$SIDECAR" ]]; then
  # CP1252-encoded on purpose (accents must still render correctly).
  printf '1\r\n00:00:01,000 --> 00:00:05,000\r\nSubtítulo externo en español: ¡atención!\r\n\r\n2\r\n00:00:40,000 --> 00:00:44,000\r\nÚltima línea del subtítulo externo.\r\n' \
    | iconv -f UTF-8 -t CP1252 > "$SIDECAR"
fi
# TV show: two episodes in a season folder.
for ep in 03 04; do
  clip "$MEDIA_DIR/TV/The Office (2005)/Season 02/The Office S02E${ep}.mkv" \
    -map 0:v -map 1:a -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -c:a aac -b:a 96k
done
# More episodes in other seasons (Season 03 left out on purpose: add it to try change detection).
for ep in S01E01 S01E02 S04E01 S04E02 S07E01; do
  season="Season ${ep:1:2}"
  clip "$MEDIA_DIR/TV/The Office (2005)/$season/The Office ${ep}.mkv" \
    -map 0:v -map 1:a -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -c:a aac -b:a 96k
done
# Unparseable episode (no SxxEyy) -> listed as unmatched.
clip "$MEDIA_DIR/TV/The Office (2005)/Season 02/The Office - behind the scenes.mkv" \
  -map 0:v -map 1:a -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -c:a aac -b:a 96k

# --- start the server -----------------------------------------------------------------------
cleanup() {
  if [[ -f "$PID_FILE" ]]; then
    local pid; pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      log "Stopping HomeTV server (pid $pid)"
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

log "Starting HomeTV server on $BASE (log: $LOG_FILE)"
(
  cd "$REPO_ROOT/server"
  export HOMETV_DATA_DIR="$DATA_DIR"
  export HOMETV_FILMS_PATHS="$MEDIA_DIR/Films"
  export HOMETV_SHOWS_PATHS="$MEDIA_DIR/TV"
  export HOMETV_SCAN_ON_STARTUP=false
  export HOMETV_SCAN_INTERVAL_HOURS=0
  export HOMETV_HOST=127.0.0.1
  export HOMETV_PORT="$PORT"
  export HOMETV_LOG_LEVEL=info
  # Serve the web SPA built above from web/dist.
  export HOMETV_WEB_DIST="${HOMETV_WEB_DIST:-$WEB_DIR/dist}"
  if [[ -n "${TMDB_API_KEY:-}" ]]; then export HOMETV_TMDB_API_KEY="$TMDB_API_KEY"; fi
  exec uv run hometv
) >"$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"

log "Waiting for /api/health"
for i in $(seq 60); do
  if curl -fsS "$BASE/api/health" >/dev/null 2>&1; then break; fi
  kill -0 "$(cat "$PID_FILE")" 2>/dev/null || { tail -20 "$LOG_FILE"; die "server exited during startup"; }
  [[ $i == 60 ]] && { tail -20 "$LOG_FILE"; die "server not healthy after 30 s"; }
  sleep 0.5
done

# --- seed profiles --------------------------------------------------------------------------
api() { # api <method> <path> [json] [extra curl args...]
  local method="$1" path="$2" body="${3:-}"; shift 3 || shift $#
  curl -sS -X "$method" "$BASE$path" -H 'Content-Type: application/json' \
    ${body:+--data "$body"} "$@"
}

log "Bootstrapping admin profile '$ADMIN_NAME' (PIN $ADMIN_PIN)"
api POST /api/profiles/bootstrap \
  "{\"name\":\"$ADMIN_NAME\",\"avatar_id\":\"avatar-01\",\"pin\":\"$ADMIN_PIN\"}" >/dev/null
ADMIN_ID="$(api GET /api/profiles | jq -r '[.items[] | select(.is_admin)][0].id')"
[[ "$ADMIN_ID" != "null" ]] || die "no admin profile found"
TOKEN="$(api POST "/api/profiles/$ADMIN_ID/session" '' | jq -r .token)"
ADMIN_TOKEN="$(api POST /api/admin/unlock "{\"pin\":\"$ADMIN_PIN\"}" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.admin_token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || die "admin unlock failed (existing admin with a different PIN? rerun with RESET=1)"

api POST /api/admin/profiles \
  "{\"name\":\"$VIEWER_NAME\",\"avatar_id\":\"avatar-05\",\"is_admin\":false}" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Token: $ADMIN_TOKEN" >/dev/null

# --- scan -----------------------------------------------------------------------------------
log "Triggering a library scan"
api POST /api/admin/scan '' -H "Authorization: Bearer $TOKEN" -H "X-Admin-Token: $ADMIN_TOKEN" >/dev/null
for i in $(seq 240); do
  STATUS="$(api GET /api/admin/scan '' -H "Authorization: Bearer $TOKEN" -H "X-Admin-Token: $ADMIN_TOKEN")"
  STATE="$(jq -r .state <<<"$STATUS")"
  [[ "$STATE" == "running" ]] || break
  sleep 0.5
done
log "Scan $STATE: $(jq -c '{counts, unmatched_count, errors}' <<<"$STATUS")"

# --- summary --------------------------------------------------------------------------------
cat <<EOF

────────────────────────────────────────────────────────────────────────
 HomeTV server running
   Web app       $BASE/            (open in a browser; served from web/dist)
   API base      $BASE/api
   OpenAPI docs  $BASE/api/docs
   Data dir      $DATA_DIR
   Media dir     $MEDIA_DIR (read-only for the app)
   Log           $LOG_FILE
   TMDB          $( [[ -n "${TMDB_API_KEY:-}" ]] && echo "enabled" || echo "disabled (set TMDB_API_KEY to enable metadata/artwork)" )

 Profiles
   $ADMIN_NAME (admin, id $ADMIN_ID, PIN $ADMIN_PIN)
   $VIEWER_NAME (viewer)

 Ready-to-use shell variables:
   export BASE=$BASE
   export TOKEN=$TOKEN
   export ADMIN_TOKEN=$ADMIN_TOKEN     # expires in 15 min; re-unlock with POST /api/admin/unlock

 TV app
   APK           $( [[ -f "$TV_APK" ]] && echo "$TV_APK" || echo "not built (rerun with --tv, or: cd tv && ./gradlew :app:assembleDebug)" )
   Installed on  ${TV_INSTALLED:-no device (adb connect <tv-ip>:5555, then adb install -r <apk>)}
   Server URL    emulator: http://10.0.2.2:$PORT  (debug builds only; 10.0.2.2 is this Mac's loopback)
                 real TV:  https://<this-mac>.<tailnet>.ts.net  (run: tailscale serve --bg $PORT;
                           the TV needs Tailscale too; see docs/tv-app-installation.md)
   Profiles      pick "$ADMIN_NAME" or "$VIEWER_NAME" (the TV never asks for a PIN)

 Press Ctrl-C to stop the server.
────────────────────────────────────────────────────────────────────────
EOF

wait "$(cat "$PID_FILE")"
