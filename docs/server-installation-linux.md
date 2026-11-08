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

Put your videos under one broad parent folder, for example `/srv/media` or a whole disk. HomeTV
sees it read-only as `/media` and you choose, in the web app (*Administración → Bibliotecas*,
step 8), which folders inside it hold films (**Películas**) and which hold TV shows (**Series**).
The folder names are up to you (`Films` and `TV` are not required); each library can have
several folders. For example:

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
- Film folders may be flat or split in subfolders (e.g. `A/`, `B/`…): film titles come from the
  file names.
- **Series**: `Show/Season NN/Show SxxEyy.ext`. Double episodes (`S01E01E02`) and absolute
  numbers (`One Piece - 105.mkv` inside `Season 05`) also work. The Series folder is the one
  that holds one subfolder per show (episode files may also sit directly in it, named with the
  show and `S01E01`): in the example, choose `/media/TV`, not its parent or a show's subfolder.
- **Subtitles**: next to the video, named like it plus a language and an optional `forced`
  flag: `Movie (2020).es.srt`, `Movie (2020).en.forced.srt`. `.srt`, `.ass`, `.ssa` and `.vtt`
  are recognized; subtitles inside the video file are found automatically. Image subtitle files
  (`.sup`, `.idx/.sub`) are listed in the subtitle menu but can't be displayed.
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

### USB or NTFS disks

If your videos are on external disks (often formatted on Windows, NTFS), mount each one inside
the media folder, e.g. `/srv/media/HD01`, and let the system do it at every boot. Don't rely on
opening the disk in the desktop's file manager: that mount disappears when you log out, and it
refuses a disk that was unplugged without "Safely remove".

1. Install the NTFS driver and find each disk's UUID:

   ```bash
   sudo apt install ntfs-3g
   sudo blkid                           # e.g. /dev/sdb1: LABEL="HD01" UUID="3A92EBC392EB822D" TYPE="ntfs"
   sudo mkdir -p /srv/media/HD01
   ```

2. Add one line per disk at the end of `/etc/fstab`. Each entry must stay on **one line**, so add
   it from the terminal rather than pasting into an editor:

   ```bash
   echo 'UUID=3A92EBC392EB822D /srv/media/HD01 ntfs-3g ro,uid=1000,gid=1000,nofail,x-systemd.device-timeout=10 0 0' | sudo tee -a /etc/fstab
   sudo findmnt --verify                # 0 errors; warnings about ntfs-3g are normal
   sudo systemctl daemon-reload && sudo mount -a
   ls /srv/media/HD01
   ```

   `ro` mounts it read-only (HomeTV only reads, and a power cut can't mark the disk dirty), and
   `nofail` lets the server boot without the disk. The disk's files are owned by uid 1000, so
   step 2's `chmod` isn't needed for them.

3. Make Docker wait for the disks at boot. The names are the mount paths with `/` replaced by `-`
   and `.mount` added (`systemd-escape -p --suffix=mount /srv/media/HD01` prints it). List every disk:

   ```bash
   sudo mkdir -p /etc/systemd/system/docker.service.d
   printf '[Unit]\nWants=srv-media-HD01.mount\nAfter=srv-media-HD01.mount\n' \
     | sudo tee /etc/systemd/system/docker.service.d/wait-for-media.conf
   sudo systemctl daemon-reload
   ```

4. If a USB disk keeps disconnecting (`sudo dmesg` shows `uas_eh_abort_handler`, `reset
   SuperSpeed USB device` and `I/O error`), its enclosure doesn't get along with the UAS driver.
   Turn UAS off for that enclosure only. `lsusb` shows its `ID`, e.g. `0bc2:2312` for a Seagate
   Expansion:

   ```bash
   echo "options usb-storage quirks=0bc2:2312:u" | sudo tee /etc/modprobe.d/disable-uas.conf
   sudo update-initramfs -u && sudo reboot
   ```

   After the reboot, `sudo dmesg | grep -i "uas is ignored"` confirms it. If it still resets, try
   another cable, a port on the computer itself or a powered hub.

All of this is permanent: it survives reboots and updates. You only repeat steps 1–3 for a new disk
(and step 4 for a new enclosure model).

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
- `MEDIA_DIR`: the broad parent folder from step 2, e.g. `/srv/media` (mounted read-only at
  `/media`). Media on more disks: mount each disk inside it (e.g. `/srv/media/HD01`,
  `/srv/media/HD02`), as explained in [USB or NTFS disks](#usb-or-ntfs-disks). A disk mounted
  there later, even while HomeTV runs, shows up inside `/media` without a restart. A disk you
  can't mount inside `MEDIA_DIR` needs its own line in the `app` service's `volumes:` in
  `docker-compose.yml` (there is an example in the comment).

`.env` holds secrets: keep it private (`chmod 600 .env`) and never commit it.

Optional: change detection (new files show up about a minute after a copy ends, without
waiting for the periodic scan) is on by default. To change its quiet period or turn it off, do
not use `.env`: add `HOMETV_SCAN_WATCH_QUIET_SECONDS` (seconds, default `30`, minimum `5`) or
`HOMETV_SCAN_WATCH_ENABLED: "false"` under the `app` service's `environment:` in
`docker-compose.yml`, e.g.:

```yaml
  app:
    environment:
      HOMETV_HOST: 127.0.0.1
      HOMETV_SCAN_WATCH_QUIET_SECONDS: "60"   # optional; default 30
```

then run `docker compose up -d`.

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
3. Open *Administración → Bibliotecas*. Under **Películas**, press **Añadir carpeta** and type
   the folder (e.g. `/media/Films`) or pick it with the folder browser; do the same under
   **Series** (e.g. `/media/TV`). Press **Guardar cambios**: the folders are saved and a scan
   starts. Follow it in *Administración → Escaneo*. With a TMDB key, posters and synopses
   appear as the scan completes.
4. In *Administración → Perfiles*, add a profile for each person in the house.

**Changing folders later** (*Administración → Bibliotecas*; nothing changes until **Guardar
cambios**, and the screen asks for confirmation before any save that has consequences):

- Removing a folder hides its titles at once; they are deleted after a few days (the screen says
  how many) unless you add the folder back, which brings them back with their watch history.
- Moving a folder between Películas and Series deletes the watch history of its titles.
- Changing the Series folder re-reads its episodes from the new folder; shows whose name changes
  lose their watch history.
- A folder on a new disk: mount the disk inside `MEDIA_DIR` first ([USB or NTFS
  disks](#usb-or-ntfs-disks)), then add the folder in Bibliotecas. No restart is needed.

**Upgrading from an older HomeTV**: the folders you had (`HOMETV_FILMS_PATHS` /
`HOMETV_SHOWS_PATHS`, or the files already indexed) are kept automatically on the first start.
Those variables are then ignored (Bibliotecas shows a notice) and can be removed from
`docker-compose.yml`. If older settings left some files in the wrong library or under another
Series folder, nothing changes at startup: the first save in Bibliotecas lists them in its
confirmation.

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

**Settings**: media folders are managed in *Administración → Bibliotecas*. Other options
(`HOMETV_MAX_SESSIONS`, scan interval…) are `HOMETV_*` variables added under `app: environment:`
in `docker-compose.yml`; see [`deploy/README.md`](../deploy/README.md).

## Troubleshooting

- **The address does not open.** Check that the device you're using is connected in its
  Tailscale app and is tagged `tag:media-client` (or is the admin's). Check that `hometv` is
  online under **Machines**, and that MagicDNS and HTTPS Certificates are on.
- **`tailscale` keeps restarting.** Run `docker compose logs tailscale`. "invalid key" means the
  auth key expired or was single-use: generate a reusable one, update `.env`, run
  `docker compose up -d`. A TUN error means `/dev/net/tun` is missing on the host.
- **`app` is not healthy.** `docker compose logs app` names the problem. A setting with a
  wrong value aborts startup and names the variable.
- **The library is empty after the scan.** Check *Administración → Bibliotecas*: each library
  needs at least one folder, and every folder must be inside `/media` (the `MEDIA_DIR` mount).
  A **No encontrada** chip means the path is not mounted (check `MEDIA_DIR` and the volume
  lines); **Sin permiso** means it is not readable by uid 1000 (step 2). The scan card in
  *Administración → Escaneo* lists errors.
- **"Falta el archivo de este título en el disco del servidor" when playing**, or a folder that
  is suddenly **No encontrada**: the disk isn't mounted. Compare `findmnt -l | grep srv/media` on
  the server with `docker compose exec app ls /media/HD01`. Mount it again (`sudo mount -a`) and
  check `sudo dmesg` for USB resets ([USB or NTFS disks](#usb-or-ntfs-disks), step 4). HomeTV keeps
  the titles while a disk is missing.
- **"La carpeta no existe en el servidor" for a folder with accents** (e.g. `/media/películas`)
  that does exist: pick it with the folder browser instead of typing it. The name on disk may
  use another Unicode form (e.g. copied from a Mac).
- **A film or episode is missing or misnamed.** Look in *Administración → Sin identificar*,
  rename the file following step 2 and rescan, or use **Corregir identificación** (needs a TMDB
  key).
- **New files do not appear by themselves.** New, renamed or deleted files normally reach the
  catalog about a minute after the copy ends. Open *Administración → Escaneo* and read the
  **Detección de cambios** line at the bottom of the card:
  - **con errores**: open **Detalles técnicos**.
    - If the text mentions `max_user_watches`, the host's inotify watch limit is reached (one
      watch per media folder). Check the current limit, raise it, and make it permanent:

      ```bash
      cat /proc/sys/fs/inotify/max_user_watches
      sudo sysctl fs.inotify.max_user_watches=524288
      echo 'fs.inotify.max_user_watches=524288' | sudo tee /etc/sysctl.d/90-hometv.conf
      ```

      Then press **Escanear ahora**: a full scan re-arms every folder.
    - If the text says "Too many open files" (`[Errno 24]`, `EMFILE`), the host's inotify
      *instance* limit is reached instead. Check it with `cat /proc/sys/fs/inotify/max_user_instances`,
      raise it with `sudo sysctl fs.inotify.max_user_instances=512`, make it permanent with
      `echo 'fs.inotify.max_user_instances=512' | sudo tee /etc/sysctl.d/91-hometv.conf`,
      then press **Escanear ahora**.
    - Any other text: press **Escanear ahora** (a full scan restarts change detection). If the
      line still says **con errores**, check the server log with `docker compose logs app`.
  - **No disponible en: *folders*** has two causes, most likely first:
    - **The disk was unplugged, remounted, or its mount dropped** (the usual case with a USB
      disk): check that the disk is plugged in and mounted on the host, then press **Escanear
      ahora**. If the folders are still listed, run `docker compose restart app` in
      `~/hometv/deploy` (Docker bind mounts do not follow a remount) and press **Escanear
      ahora** again.
    - **The folder is on a network share (NFS, SMB)**: change detection does not work there and
      the line stays. The periodic scan and **Escanear ahora** still pick up new files.
  - **iniciando…** right after a start: the folders are still being registered (longer on a
    sleeping USB disk); the line updates by itself.
  - **desactivada**: `HOMETV_SCAN_WATCH_ENABLED` is `false` under `app: environment:` in
    `docker-compose.yml` (step 6).
- **"El servidor ya está convirtiendo el número máximo de vídeos"** (web) **or "El servidor ya
  está emitiendo en el número máximo de dispositivos"** (TV). By default, 4 screens can watch
  converted (HLS) video at the same time. Raise `HOMETV_MAX_SESSIONS` if the machine
  can take it.
