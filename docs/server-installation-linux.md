# Installing the HomeTV server on Linux

This guide installs the HomeTV server on a Linux machine at home (a NUC, a mini PC, an old
laptop, a home server). When you finish:

- HomeTV runs in Docker and starts on its own after a reboot.
- It is reachable only from your own devices, at `https://hometv.<your-tailnet>.ts.net`,
  through [Tailscale](https://tailscale.com). Nothing is opened on your router or your LAN.
- Your films and series stay where they are. HomeTV only reads them and never changes,
  moves or deletes a file.

The short operator reference is [`deploy/README.md`](../deploy/README.md). On Windows, follow
[`server-installation-windows.md`](server-installation-windows.md) instead.

## What you need

- **A 64-bit x86 (amd64) Linux machine** that can stay on, with Docker Engine and its Compose
  plugin. Debian 12 or Ubuntu 22.04/24.04 are the easiest choices. HomeTV never re-encodes video
  (it only repackages it, and converts audio when needed), so a modest CPU is enough. Plan for
  2 GB of RAM and about 5 GB of free disk for the app's data and temporary streaming files.
  ARM boards (Raspberry Pi) are not supported: the image is built for amd64.
- **Your media** on a disk that machine can read, organized as described in step 2.
- **A Tailscale account** (the free Personal plan is enough). You must be the tailnet admin.
- **Optional: a TMDB API key**, for posters, synopses, cast and genres in Spanish. Without it,
  HomeTV still works and shows file names.

## 1. Install Docker

On Debian or Ubuntu, install Docker Engine with the Compose plugin using Docker's script:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"     # run docker without sudo; log out and back in afterwards
```

Check it, and check that the machine has a TUN device (Tailscale needs it):

```bash
docker compose version
ls -l /dev/net/tun
```

Docker starts at boot by default (`sudo systemctl enable --now docker` if not).

## 2. Organize your media

HomeTV needs one folder holding two subfolders, `Films` and `TV`, for example `/srv/media`:

```
/srv/media/
├── Films/
│   ├── Dune (2021)/
│   │   ├── Dune (2021).mkv
│   │   └── Dune (2021).es.srt               ← external subtitles (optional)
│   └── Heat (1995)/
│       ├── Heat (1995) - Part 1.mkv         ← a film split in parts
│       └── Heat (1995) - Part 2.mkv
└── TV/
    └── The Office/
        └── Season 02/
            ├── The Office S02E03.mkv
            └── The Office S02E04.mkv
```

Naming rules that give the best matches:

- **Films**: `Title (Year)`, ideally in a folder with the same name. Two files of the same film
  (e.g. `Dune (2021) 2160p.mkv` and `Dune (2021) 1080p.mp4`) become two versions of one film.
- **Series**: `Show/Season NN/Show SxxEyy.ext`. Double episodes (`S01E01E02`) and absolute
  numbers (`One Piece - 105.mkv` inside `Season 05`) also work.
- **Subtitles**: next to the video, named like it plus a language and an optional `forced`
  flag: `Movie (2020).es.srt`, `Movie (2020).en.forced.srt`. `.srt`, `.ass`, `.ssa`, `.vtt`,
  `.sup` and `.idx/.sub` are recognized; subtitles inside the video file are found automatically.
- Extras are ignored: folders named `Extras`, `Featurettes`, `Behind the Scenes`, `Deleted Scenes`,
  `Trailers` or `Sample`, and files ending in `-trailer` or `-sample`.

Files HomeTV cannot place show up in *Administración → Sin identificar*, where an admin can fix
them by hand. You can add more films later: HomeTV rescans every few hours, and an admin can scan
at any time.

The app runs as a user with id 1000 inside the container, so the files must be readable by
everyone or by uid/gid 1000:

```bash
sudo chmod -R a+rX /srv/media          # readable, folders listable; nothing becomes writable
```

## 3. Get HomeTV

```bash
sudo apt-get install -y git            # if git is missing
git clone <repository-url> ~/hometv
cd ~/hometv/deploy
```

## 4. Prepare Tailscale

Do this once, in the [Tailscale admin console](https://login.tailscale.com/admin):

1. **DNS** page: turn on **MagicDNS**, then **HTTPS Certificates**.
2. **Access controls** page: add the two tags and the rule below. *Merge* them with your existing
   policy; do not replace it, or devices lose the access they have today.

   ```json
   {
     "tagOwners": {
       "tag:media-server": ["autogroup:admin"],
       "tag:media-client": ["autogroup:admin"]
     },
     "acls": [
       { "action": "accept", "src": ["tag:media-client", "autogroup:admin"], "dst": ["tag:media-server:443"] }
     ]
   }
   ```

   Only devices tagged `tag:media-client` (your TV, iPad, phones) and the admin's own devices can
   reach HomeTV.
3. **Settings → Keys → Generate auth key**: **Reusable** on, **Ephemeral** off, **Tags**
   `tag:media-server`. Copy the key (`tskey-auth-…`); it is shown only once.

The server joins your tailnet by itself as a machine called `hometv`. You don't need to install
Tailscale on the Linux host.

## 5. Get a TMDB key (optional)

Create a free account at [themoviedb.org](https://www.themoviedb.org), then go to
**Settings → API** and request a developer key. Copy the **API Key** (v3) or the **API Read
Access Token** (v4); either works.

## 6. Configure

In `~/hometv/deploy`:

```bash
cp .env.example .env
nano .env
```

Set:

- `TS_AUTHKEY`: the key from step 4.
- `TMDB_API_KEY`: the key from step 5, or leave it empty.
- `MEDIA_DIR`: the folder holding `Films` and `TV`, e.g. `/srv/media`.

`.env` holds secrets: keep it private (`chmod 600 .env`) and never commit it.

## 7. Start HomeTV

```bash
docker compose up -d --build
```

The first build downloads the base images and builds the app and web interface; it takes a few
minutes. Then check:

```bash
docker compose ps                  # app should become "healthy"; no ports are listed, by design
docker compose logs -f app         # Ctrl-C to stop following
```

In the Tailscale admin console, **Machines** now lists `hometv` with the tag `tag:media-server`.

## 8. First use

1. On a computer or phone that has Tailscale and is allowed by the rule from step 4, open
   `https://hometv.<your-tailnet>.ts.net`. Your tailnet name is shown on the DNS page of the admin
   console. The first visit can take a few seconds while Tailscale obtains the HTTPS certificate.
2. HomeTV opens on **Configuración inicial**. Create the first profile: a name, an avatar and a
   4-digit admin PIN. The PIN protects *Administración* only; nobody needs it to watch.
3. The first library scan starts by itself shortly after the server starts. Follow it in
   *Administración → Escaneo*, or press **Escanear ahora**. With a TMDB key, posters and
   synopses appear as the scan completes.
4. In *Administración → Perfiles*, add a profile for each person in the house.

Then set up your other screens:

- Android TV / Google TV: [`tv-app-installation.md`](tv-app-installation.md).
- iPad: [`ipad-installation.md`](ipad-installation.md).
- Any computer: install Tailscale, tag it `tag:media-client`, and open the address in a browser.

## Everyday operations

Run these in `~/hometv/deploy`.

| Task | Command |
|---|---|
| Status | `docker compose ps` |
| Logs | `docker compose logs -f app` (or `tailscale`) |
| Restart | `docker compose restart` |
| Stop / start | `docker compose down` / `docker compose up -d` |
| Update HomeTV | `git pull && docker compose up -d --build` |

**Backups**: profiles, watch history, artwork and settings live in the Docker volume
`deploy_hometv-data` (the media folder holds nothing of HomeTV's). To back it up:

```bash
docker compose stop app
docker run --rm -v deploy_hometv-data:/data:ro -v "$PWD":/backup debian:bookworm-slim \
  tar czf /backup/hometv-data.tgz -C /data .
docker compose start app
```

Do not delete the `deploy_tailscale-state` volume: it is the server's identity in your tailnet.
Without it, `hometv` registers again as a new machine (and may get a different name).

**Settings**: other options (`HOMETV_MAX_SESSIONS`, scan interval, several media folders…) are
`HOMETV_*` variables added under `app: environment:` in `docker-compose.yml`; see
[`deploy/README.md`](../deploy/README.md).

## Troubleshooting

- **The address does not open.** Check that the device you're using is connected in its
  Tailscale app and is tagged `tag:media-client` (or is the admin's). Check that `hometv` is
  online under **Machines**, and that MagicDNS and HTTPS Certificates are on.
- **`tailscale` keeps restarting.** Run `docker compose logs tailscale`. "invalid key" means the
  auth key expired or was single-use: generate a reusable one, update `.env`, run
  `docker compose up -d`. A TUN error means `/dev/net/tun` is missing on the host.
- **`app` is not healthy.** `docker compose logs app` names the problem. A setting with a
  wrong value aborts startup and names the variable.
- **The library is empty after the scan.** `MEDIA_DIR` must be the folder that *contains*
  `Films` and `TV`, and the files must be readable by uid 1000 (step 2). The scan card in
  *Administración → Escaneo* lists errors.
- **A film or episode is missing or misnamed.** Look in *Administración → Sin identificar*,
  rename the file following step 2 and rescan, or use **Corregir identificación** (needs a TMDB
  key).
- **"El servidor ya está emitiendo en el número máximo de dispositivos".** By default, 3 screens
  can watch converted (HLS) video at the same time. Raise `HOMETV_MAX_SESSIONS` if the machine
  can take it.
