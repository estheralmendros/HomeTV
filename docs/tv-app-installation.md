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
4. Turn on **USB debugging** (or **Wireless debugging** on newer builds).

## 2. Build the APK
On your computer (JDK 21 and the Android SDK; see `tv/README.md`):

1. Create a signing key once, and keep it safe. Updates with `adb install -r` need the same key:
   ```bash
   keytool -genkeypair -v -keystore ~/keys/hometv-tv.jks -alias hometv -keyalg RSA -keysize 4096 -validity 10000
   ```
2. `cp tv/keystore.properties.example tv/keystore.properties` and fill in `storeFile`,
   `storePassword`, `keyAlias` and `keyPassword` (the file is gitignored).
3. From the repo root, run `make tv-build`. The APK is
   `tv/app/build/outputs/apk/release/app-release.apk` (about 3 MB).

A debug build (`cd tv && ./gradlew :app:assembleDebug`, at `tv/app/build/outputs/apk/debug/app-debug.apk`)
needs no key. It also accepts `http://10.0.2.2:<port>` and `http://localhost:<port>`, which only
help on an emulator. On a real TV, use the `https://….ts.net` address in both builds.

## 3. Install the app with ADB (sideloading)
Prerequisites: Android Platform Tools (`adb`) on your computer; computer and TV on the same network.

1. Find the TV's IP: **Settings → Network & Internet → (your Wi-Fi)**.
2. Connect:
   ```bash
   adb connect <tv-ip>:5555
   ```
   Accept the debugging prompt on the TV (tick "Always allow").
   With *Wireless debugging*, first pair using the code and port shown on the TV: `adb pair <tv-ip>:<pair-port>`, then `adb connect <tv-ip>:<port>`.
3. Install / update:
   ```bash
   adb install -r app-release.apk
   ```
4. During development you can also run the app straight from Android Studio on the connected TV.

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

If you see "No se puede conectar con tu servidor HomeTV", open the Tailscale app on the TV
(the **Abrir Tailscale** button does this) and check that it says *Connected*.

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
