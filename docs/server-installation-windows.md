# Installing the HomeTV server on Windows

This guide installs the HomeTV server on a Windows 10 or 11 PC. HomeTV runs in **Docker
Desktop** (Linux containers on WSL 2), with the same setup as on Linux: when you finish, it is
reachable only from your own devices at `https://hometv.<your-tailnet>.ts.net`, through
[Tailscale](https://tailscale.com), and nothing is opened on your router or your LAN.

> **Why Docker?** The server can't run directly on Windows: while streaming, it pauses and
> resumes ffmpeg with POSIX signals that Windows doesn't have. Docker Desktop runs it inside a
> small Linux VM.

> **Status:** these steps use the same Compose stack as Linux, but they haven't been tested on a
> Windows PC yet. If something differs, the Troubleshooting section lists the likely fixes.

The Linux guide, [`server-installation-linux.md`](server-installation-linux.md), explains the
media layout, the Tailscale setup and first use in detail; this guide refers to it for those
parts.

## What you need

- **A 64-bit Intel or AMD PC** with Windows 10 22H2 or Windows 11, hardware virtualization on
  (it usually is; otherwise enable *Intel VT-x* / *AMD-V* / *SVM* in the BIOS), and 8 GB of RAM.
  Windows on ARM (Snapdragon) is not supported: the image is built for amd64.
- **The PC must stay on** while you watch, and a user must be signed in: Docker Desktop starts
  when you sign in to Windows.
- **Your media** on an internal or always-connected drive, e.g. `D:\Media`.
- **A Tailscale account**, where you are the tailnet admin, and optionally a **TMDB API key**
  (see the Linux guide, steps 4 and 5).

## 1. Install WSL 2 and Docker Desktop

1. Open **PowerShell as administrator** and run:

   ```powershell
   wsl --install
   ```

   Restart when asked. If WSL is already installed, `wsl --update` brings it up to date.
2. Download and install [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/).
   During setup keep **Use WSL 2 instead of Hyper-V** checked. Docker Desktop is free for personal
   use.
3. Start Docker Desktop and accept the terms. In **Settings → General**, turn on **Start Docker
   Desktop when you sign in to your computer**.
4. Check in a new PowerShell window:

   ```powershell
   docker compose version
   ```

## 2. Keep the PC awake

In **Settings → System → Power** (Windows 11) or **Power & sleep** (Windows 10), set **Sleep**
to **Never** while plugged in. A sleeping PC drops every playback. The screen can still turn off.

## 3. Organize your media

Use the same layout as on Linux: one broad parent folder (or a whole drive) holding your film and
TV folders, e.g.

```
D:\Media\
├── Films\Dune (2021)\Dune (2021).mkv
└── TV\The Office\Season 02\The Office S02E03.mkv
```

HomeTV sees it read-only as `/media`. The folder names are up to you: after the first start you
choose, in *Administración → Bibliotecas*, which folders hold films (**Películas**) and which
hold TV shows (**Series**). The Series folder is the one with one subfolder per show (here
`/media/TV`), not its parent or a show's subfolder. The naming rules are in the Linux guide,
step 2. HomeTV only reads this folder.

## 4. Get HomeTV

Install [Git for Windows](https://git-scm.com/download/win), then in PowerShell:

```powershell
git clone --config core.autocrlf=false <repository-url> $HOME\hometv
cd $HOME\hometv\deploy
```

`core.autocrlf=false` keeps the files with Linux line endings, as the image build expects.

## 5. Prepare Tailscale and TMDB

Follow the Linux guide, steps **4** (MagicDNS + HTTPS certificates, the ACL tags and rule, a
reusable tagged auth key) and **5** (TMDB key, optional). Nothing changes on Windows.

You don't need Tailscale for Windows for the server: the `tailscale` container joins your tailnet
as its own machine, `hometv`. If you also want to *watch* on this PC, install Tailscale for
Windows as a normal client and tag the PC `tag:media-client`.

## 6. Configure

In `$HOME\hometv\deploy`:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set:

- `TS_AUTHKEY`: the auth key.
- `TMDB_API_KEY`: the TMDB key, or empty.
- `MEDIA_DIR`: the broad media folder **with forward slashes**, e.g. `MEDIA_DIR=D:/Media`
  (mounted read-only at `/media`). Media on another drive: add a line such as
  `- E:/Series:/media/disk2:ro` to the `app` service's `volumes:` in `docker-compose.yml`.

Save the file. Keep it private: it holds your keys.

## 7. Start HomeTV

```powershell
docker compose up -d --build
```

The first build takes a few minutes. Then:

```powershell
docker compose ps                  # app should become "healthy"; no ports are listed, by design
docker compose logs -f app         # Ctrl-C to stop following
```

The containers restart on their own whenever Docker Desktop starts, so HomeTV comes back after a
reboot once you sign in. You can also see them in the Docker Desktop window under **Containers**.

## 8. First use

Exactly as in the Linux guide, step 8: open `https://hometv.<your-tailnet>.ts.net` from a
Tailscale device, create the first profile with its admin PIN, then in *Administración →
Bibliotecas* add your film folders under **Películas** and your TV folders under **Series**
(typed, e.g. `/media/Films`, or picked with the folder browser) and press **Guardar cambios**:
the scan starts. The Linux guide, step 8, explains what removing, moving or changing folders
later does (the screen always asks first), the upgrade from older versions (existing folders
are kept; the old `HOMETV_FILMS_PATHS`/`HOMETV_SHOWS_PATHS` are then ignored and can be
removed; files left in the wrong library by older settings are listed in the first save's
confirmation), and that a folder on a new drive needs its volume line and
`docker compose up -d` first.
Then set up the [Android TV app](tv-app-installation.md) and the [iPad](ipad-installation.md).

## Everyday operations

Run these in PowerShell, in `$HOME\hometv\deploy`. They are the same commands as on Linux:

| Task | Command |
|---|---|
| Status | `docker compose ps` |
| Logs | `docker compose logs -f app` |
| Restart | `docker compose restart` |
| Update HomeTV | `git pull; docker compose up -d --build` |

**Backup** of profiles, watch history and artwork (the `deploy_hometv-data` volume):

```powershell
docker compose stop app
docker run --rm -v deploy_hometv-data:/data:ro -v "${PWD}:/backup" debian:bookworm-slim tar czf /backup/hometv-data.tgz -C /data .
docker compose start app
```

Don't delete the `deploy_tailscale-state` volume: it is the server's identity in your tailnet.

## Troubleshooting

- **`tailscale` keeps restarting with a TUN error** (`/dev/net/tun`, "operation not
  permitted"). Docker Desktop's VM normally provides the TUN device. If yours doesn't, switch
  Tailscale to userspace networking. In `docker-compose.yml`, under `tailscale:`, set
  `TS_USERSPACE: "true"` and delete the `devices:` and `cap_add:` lines, then run
  `docker compose up -d`. `tailscale serve` still publishes HomeTV the same way.
- **The library is empty.** Check *Administración → Bibliotecas*: each library needs at least
  one folder inside `/media`. A **No encontrada** chip means the path is not mounted: check
  `MEDIA_DIR` in `.env` (forward slashes) and that the drive is shared with Docker: **Settings →
  Resources → File sharing** if your Docker Desktop shows that page. `docker compose exec app ls
  /media` should list your folders. **Sin permiso** means the folder is not readable by the app.
- **"La carpeta no existe en el servidor" for a folder with accents** (e.g. `/media/películas`)
  that does exist: pick it with the folder browser instead of typing it (the name on disk may use
  another Unicode form).
- **The first scan is slow.** The container reads Windows drives through WSL's file sharing,
  which is slower than a Linux disk. Later scans only look at changed files and are much faster.
  Playback is not affected.
- **Docker Desktop says WSL is not installed or virtualization is off.** Run `wsl --install`
  again as administrator, and check that virtualization is enabled in the BIOS (Task Manager →
  Performance → CPU shows *Virtualization: Enabled*).
- **Everything else** (address not reachable, auth key, unmatched files): see the Linux guide's
  Troubleshooting section.
