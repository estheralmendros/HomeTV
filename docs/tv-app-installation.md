# Installing the TV App (Android TV / Google TV) + Tailscale

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

## 5. Make the server tailnet-only
1. Install Tailscale on the server and join the same tailnet. The Compose stack in `deploy/`
   does this with a Tailscale sidecar (see `deploy/README.md`).
2. Publish the server over HTTPS, for example with:
   ```bash
   tailscale serve --bg <app-port>
   ```
   This exposes it at `https://<machine>.<tailnet>.ts.net` with a valid certificate. The release
   app accepts only `https://` addresses, so a bare Tailscale IP (`http://100.x.y.z`) does not work.
3. Restrict access with tailnet **ACLs** so only your chosen devices can reach the server (tag
   the TV `tag:media-client`, per prd.md).
4. The app itself needs nothing Tailscale-specific: Tailscale runs as a system VPN.

## 6. First launch
1. Create the first profile from the web app (`https://<machine>.<tailnet>.ts.net`). The TV
   never creates profiles; until one exists it says "Todavía no hay perfiles…".
2. Open **HomeTV** from the apps row on the TV. In the "Dirección del servidor" field, type the address
   (`https://hometv.<tailnet>.ts.net`; `https://` is added if you leave it out) and press
   **Conectar**.
3. Pick a profile. No PIN is asked on the TV, not even for the admin profile.
4. To change the server or profile later, go to **Ajustes**.

If you see "No se puede conectar con tu servidor HomeTV", open the Tailscale app on the TV
(the **Abrir Tailscale** button does this) and check that it says *Connected*.

## Gotchas
- After the TV reboots, check that Tailscale reconnects automatically; otherwise the app can't reach the server.
- At home, Tailscale connects TV and server directly over the LAN, so there's no performance penalty.
- If you use **casting** instead of the native app, the Chromecast fetches the video itself, so it must have Tailscale installed too.
- Hotel / captive-portal Wi-Fi may block Tailscale when travelling.

## Sources
- [Install Tailscale on Android – Tailscale docs](https://tailscale.com/docs/install/android.md)
- [Google TV + Tailscale setup – selfhost.it](https://selfhost.it.com/blog/google-tv-tailscale-plex)
- [Android sideloading changes 2026 – DroidRooter](https://www.droidrooter.com/blog/android-sideloading-changes-2026)
- [Enable developer options on Chromecast with Google TV – allaboutchromecast](https://allaboutchromecast.com/enable-developer-options-in-chromecast-with-google-tv/)
