# Installing the TV App (Android TV / Google TV) + Tailscale

The server must be installed first: see [`server-installation-linux.md`](server-installation-linux.md)
or [`server-installation-windows.md`](server-installation-windows.md). For an iPad, see
[`ipad-installation.md`](ipad-installation.md).

## Compatible devices
- ✅ **Chromecast with Google TV** (HD or 4K) and **Google TV Streamer** — run Android TV OS and can install apps.
- ❌ Older Chromecast dongles (no remote) — cannot install apps; they only support casting.

## 1. Enable Developer options on the TV
1. Go to **Settings → System → About**.
2. Select **Android TV OS build** 7 times until it says you are a developer.
3. Go back to **Settings → System → Developer options**.
4. Turn on **Wireless debugging** (newer Google TV builds, e.g. Google TV Streamer and updated
   Chromecasts) or, on older builds without it, **USB debugging**. On newer builds USB debugging
   alone does not open port 5555, so `adb connect <tv-ip>:5555` is refused: use Wireless debugging.

## 2. Build the APK
On your computer (JDK 21 and the Android SDK; see `tv/README.md`). Run the commands from the repo
root.

### First time only: the signing key
Every update must be signed with the same key as the installed app, so this is done once and the
key is kept forever.

1. Create the key (on macOS with Homebrew, `keytool` is
   `/opt/homebrew/opt/openjdk@21/bin/keytool`). Run it on its own line:
   ```bash
   mkdir -p ~/keys
   keytool -genkeypair -v -keystore ~/keys/hometv-tv.jks -alias hometv -keyalg RSA -keysize 4096 -validity 10000
   ```
   - *Enter keystore password*: you choose it now (at least 6 characters; nothing is shown while
     typing). It is asked only here; afterwards it lives in `keystore.properties`.
   - *First and last name, organizational unit…, country code*: certificate labels, not checked
     by anyone. Press Enter for each (they are readable inside every APK, so don't type anything
     you would not publish).
   - *Is CN=… correct? [no]*: type `yes` (Enter alone means "no" and asks everything again).
   - A separate key password, if asked: press Enter to reuse the keystore password.
2. Tell the build where the key is (the file is gitignored):
   ```bash
   cp tv/keystore.properties.example tv/keystore.properties
   chmod 600 tv/keystore.properties ~/keys/hometv-tv.jks
   nano tv/keystore.properties
   ```
   `storeFile` must be the **absolute** path (e.g. `/Users/<you>/keys/hometv-tv.jks`, not `~/…`,
   and not the example's `/Users/you/…`); `keyAlias=hometv`; `storePassword` and `keyPassword` =
   the password from step 1.
3. **Back up `hometv-tv.jks` and its password** off this computer (e.g. a password manager entry
   with the file attached). Losing either means the next version cannot update the installed app:
   it must be uninstalled and set up again on the TV.

### Every build
```bash
make tv-build
```
The APK is `tv/app/build/outputs/apk/release/app-release.apk` (about 95 MB: it bundles libVLC, the
fallback player for files ExoPlayer can't decode, for ARM devices only (`armeabi-v7a` and `arm64-v8a`,
which covers Chromecast with Google TV and Google TV Streamer); it doesn't install on an x86 emulator
without ARM translation). Its version comes from
the repo-root `version.txt` (`MAJOR.MINOR.PATCH`, version code `M*10000 + m*100 + p`), so bump
`version.txt` before building a new release. A build with a lower version code than the installed
app is refused by `adb install -r`.

If the build fails with `Keystore file '…' not found`, `storeFile` is wrong; with `Keystore was
tampered with, or password was incorrect`, a password in `keystore.properties` is wrong.

A debug build (`cd tv && ./gradlew :app:assembleDebug`, at `tv/app/build/outputs/apk/debug/app-debug.apk`)
needs no key. It also accepts `http://10.0.2.2:<port>` and `http://localhost:<port>`, which only
help on an emulator. On a real TV, use the `https://….ts.net` address in both builds. A debug build
and a release build are signed with different keys, so one cannot update the other.

## 3. Install the app with ADB (sideloading)
Prerequisites: Android Platform Tools (`adb`) on your computer; computer and TV on the same home
network (not a guest network).

1. Find the TV's IP: **Settings → Network & Internet → (your network)**. If the Wireless debugging
   screen shows another address, e.g. a `100.x.y.z` one, that is the TV's Tailscale address; use
   the home-network IP with the ports from the Wireless debugging screen.
2. **Wireless debugging** (the usual case). It uses two different ports:
   - **Pairing (once per computer).** On the TV: **Developer options → Wireless debugging → Pair
     device with pairing code**. The pop-up shows a 6-digit **code** and an *IP address & port*:
     that port is the **pairing port**. Keep the pop-up open and run:
     ```bash
     adb pair <tv-ip>:<pairing-port>       # then type the 6-digit code when asked
     ```
     It prints `Successfully paired`, and the computer is listed under *Paired devices* on the TV.
   - **Connecting (every session).** Close the pop-up. The main Wireless debugging screen shows
     another *IP address & port*: that port is the **connection port**. It changes whenever
     Wireless debugging restarts (TV reboot, sleep, toggle), so read it again each time:
     ```bash
     adb connect <tv-ip>:<connection-port>
     adb mdns services                     # alternative: lists _adb-tls-connect._tcp with the current port
     ```

   **USB debugging** (older builds only): `adb connect <tv-ip>:5555`, then accept the prompt on the
   TV and tick "Always allow".
3. Check that the TV is listed as `device`, then install / update:
   ```bash
   adb devices
   adb -s <tv-ip>:<port> install -r tv/app/build/outputs/apk/release/app-release.apk
   ```
   It prints `Success`. `-s` picks the TV when another device (e.g. a running emulator) is also
   attached. `-r` replaces the installed app and keeps its data (server address, session).
4. During development you can also run the app straight from Android Studio on the connected TV.

**Connection errors:**
- `No route to host` while `ping <tv-ip>` works (macOS): the **Local Network** permission is
  blocking `adb`. Turn your terminal app on in **System Settings → Privacy & Security → Local
  Network**, quit it (⌘Q) and reopen it, then run `adb kill-server` and connect again: the adb
  server is a background process that keeps the permission of whichever app started it (an IDE or
  another tool may have started it). Corporate VPNs and security software can also block local
  connections.
- `No route to host` and `ping` fails too: wrong IP, the TV is asleep, or the computer and the TV
  are on different networks.
- `Connection refused`: the network is fine but nothing listens on that port. Use the connection
  port from the main Wireless debugging screen (not the pairing port, and not 5555 on newer builds).
- `failed to connect` right after pairing: the connection port changed, or you used the pairing
  port. Read the main screen again or use `adb mdns services`.

> **About Google's 2026 sideloading rules:** ADB installs do not require developer registration. Initial enforcement (from 30 Sept 2026) covers only Brazil, Indonesia, Singapore and Thailand; wider rollout is planned for 2027. A personal app installed via ADB is fine for now — re-check before 2027.

## 4. Install Tailscale on the TV
Tailscale's official Android app supports Android TV (Android 8.0+).
1. Install **Tailscale** from the Play Store on the TV.
2. Open it and select **Log in**; note the code shown below the QR code.
3. In the Tailscale admin console: **Machines → Add device → Client device → Android → Input code**, enter the code and **Submit** (or scan the QR code).
4. Confirm the TV shows up in the Machines list.

## 5. Let the TV reach the server
1. Nothing to install on the server: the Compose stack in `deploy/` already runs Tailscale as a
   sidecar and publishes HomeTV over HTTPS at `https://hometv.<tailnet>.ts.net` (see the server
   installation guide linked at the top). Don't install Tailscale or run `tailscale serve` on the
   server host as well: that would add a second node.
2. In the Tailscale admin console, tag the TV `tag:media-client` (**Machines → … → Edit ACL
   tags**). The server guide's ACL only lets devices with that tag (and the admin's own) reach
   the server.
3. The release app accepts only `https://` addresses, so a bare Tailscale IP
   (`http://100.x.y.z`) does not work.
4. The app itself needs nothing Tailscale-specific: Tailscale runs as a system VPN.

## 6. First launch
1. Create the first profile from the web app (`https://<machine>.<tailnet>.ts.net`). The TV
   never creates profiles; until one exists it says "Todavía no hay perfiles…".
2. Open **HomeTV** from the apps row on the TV. In the "Dirección del servidor" field, type the address
   (`https://hometv.<tailnet>.ts.net`; `https://` is added if you leave it out) and press
   **Conectar**.
3. Pick a profile. No PIN is asked on the TV, not even for the admin profile.
4. To change the profile later, select your avatar at the right end of the top bar → **Cambiar de perfil** (admins
   also find it in **Ajustes**). The **Ajustes** tab, and its **Cambiar servidor** button, are shown only to admin
   profiles; **Cambiar servidor** asks for the admin PIN (the server checks it, so it must be reachable). If the old server is gone for
   good, clear the app's data (Settings → Apps → HomeTV → Clear data) or reinstall it to start from the setup screen.
   To leave HomeTV, press Back on Inicio until it asks "¿Salir de la aplicación?" and choose **Salir**: this signs
   the profile out, and the next launch shows "¿Quién está viendo?". Leaving with the remote's Home button keeps the
   profile signed in.

If you see "No se puede conectar con tu servidor HomeTV", open the Tailscale app on the TV
(the **Abrir Tailscale** button does this) and check that it says *Connected*.

## Playback
HomeTV plays with the TV's own decoders (ExoPlayer). For formats the TV can't decode (e.g. Xvid/DivX
AVI films) it switches automatically to the built-in VLC engine (libVLC 3.7.2), which plays the file
straight from the server with no conversion. Nothing to choose: the player screen looks the same with
both engines.

- **Ajustes → Reproducción → "Usar siempre VLC (experimental)"** (admins only, shown when VLC is
  available) plays every video with VLC, for testing. It applies from the next playback, to every
  profile on that TV, and survives restarts and "Salir". If VLC crashes on every video while it is on,
  an admin turns it off there (Ajustes never starts VLC).
- The APK is ARM-only (`armeabi-v7a` + `arm64-v8a`): it doesn't install on an x86 emulator without ARM
  translation.
- The licence notice (libVLC is LGPL v2.1 or later; the other libraries are Apache 2.0) is in
  **Ajustes → Acerca de → Licencias de código abierto**.

## Updating the app
After changing the code (the server is updated separately: `git pull && docker compose up -d
--build` in `deploy/` on the server, see the server installation guide). When a change touches the
REST API, update the server and the TV app in the same round.

1. Bump `version.txt` if it is a new release, and commit.
2. Make sure the key file (`~/keys/hometv-tv.jks`) and `tv/keystore.properties` are in place. On
   a new or cleaned-up computer, restore the key from your backup and recreate
   `keystore.properties` (section 2, step 2); do **not** create a new key.
3. Build: `make tv-build`.
4. On the TV, open **Developer options → Wireless debugging** and read the current connection port
   (pairing is not needed again from the same computer).
5. Connect and install:
   ```bash
   adb connect <tv-ip>:<connection-port>
   adb -s <tv-ip>:<connection-port> install -r tv/app/build/outputs/apk/release/app-release.apk
   ```
6. Open HomeTV on the TV: the server address and the session are kept.

## Gotchas
- After the TV reboots, check that Tailscale reconnects automatically; otherwise the app can't reach the server.
- At home, Tailscale connects TV and server directly over the LAN, so there's no performance penalty.
- Hotel / captive-portal Wi-Fi may block Tailscale when travelling.
- **Spanish keyboard (ñ)**: the app asks the keyboard for Spanish, but Gboard only switches to it when Spanish is one of its languages. Add it once in the TV's settings (Settings → System → Keyboard → Gboard settings → Languages → add "Español (España)"; menu names vary by TV). Without it, "ñ" is still there: long-press OK on "n".

## Sources
- [Install Tailscale on Android – Tailscale docs](https://tailscale.com/docs/install/android.md)
- [Google TV + Tailscale setup – selfhost.it](https://selfhost.it.com/blog/google-tv-tailscale-plex)
- [Android sideloading changes 2026 – DroidRooter](https://www.droidrooter.com/blog/android-sideloading-changes-2026)
- [Enable developer options on Chromecast with Google TV – allaboutchromecast](https://allaboutchromecast.com/enable-developer-options-in-chromecast-with-google-tv/)
