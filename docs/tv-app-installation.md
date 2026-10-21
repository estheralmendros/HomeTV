# Installing the TV App (Android TV / Google TV) + Tailscale

## Compatible devices
- ✅ **Chromecast with Google TV** (HD or 4K) and **Google TV Streamer** — run Android TV OS and can install apps.
- ❌ Older Chromecast dongles (no remote) — cannot install apps; they only support casting.

## 1. Enable Developer options on the TV
1. Go to **Settings → System → About**.
2. Select **Android TV OS build** 7 times until it says you are a developer.
3. Go back to **Settings → System → Developer options**.
4. Turn on **USB debugging** (or **Wireless debugging** on newer builds).

## 2. Install the app with ADB (sideloading)
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

## 3. Install Tailscale on the TV
Tailscale's official Android app supports Android TV (Android 8.0+).
1. Install **Tailscale** from the Play Store on the TV.
2. Open it and select **Log in**; note the code shown below the QR code.
3. In the Tailscale admin console: **Machines → Add device → Client device → Android → Input code**, enter the code and **Submit** (or scan the QR code).
4. Confirm the TV shows up in the Machines list.

## 4. Make the server tailnet-only
1. Install Tailscale on the server and join the same tailnet.
2. Either bind the app to the server's Tailscale IP (`100.x.y.z`), or publish it with:
   ```bash
   tailscale serve --bg <app-port>
   ```
   which exposes it at `https://<machine>.<tailnet>.ts.net` with a valid HTTPS certificate (Android blocks cleartext HTTP by default).
3. Restrict access with tailnet **ACLs** so only your chosen devices can reach the server.
4. Point the TV app to the `ts.net` URL. The app itself needs nothing Tailscale-specific — Tailscale runs as a system VPN.

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
