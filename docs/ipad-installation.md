# Using HomeTV on an iPad

There is no App Store app for HomeTV. On an iPad (and on an iPhone) you use the **HomeTV web
app in Safari**. It is the same app as on a computer: browse, search, resume, choose audio and
subtitles, next episode, and (for admins) Administración. To reach your server from the iPad,
the iPad joins your Tailscale network.

> **Status:** the web app supports iPadOS, with Safari's own video playback and a layout for
> touch screens, but it has not been tested on a real iPad yet. Please report anything that
> looks wrong.

## What you need

- The HomeTV server running, and its address: `https://hometv.<your-tailnet>.ts.net` (see the
  [Linux](server-installation-linux.md) or [Windows](server-installation-windows.md) guide).
- An iPad with a recent iPadOS and Safari.
- Access to the Tailscale admin console (you, or whoever set up the server).

## 1. Install Tailscale on the iPad

1. Install **Tailscale** from the App Store and open it.
2. Tap **Log in** and sign in with the same account (Google, Microsoft, Apple…) used for the
   tailnet. To use a family member's own account instead, invite them first from the admin
   console (**Users → Invite users**).
3. When iPadOS asks to **add a VPN configuration**, tap **Allow** and confirm with Face ID,
   Touch ID or your passcode.
4. The switch at the top shows **Connected**.

Tailscale runs as a VPN: only traffic to your tailnet goes through it, and it doesn't slow down
the rest of the iPad's internet.

## 2. Allow the iPad to reach HomeTV

The server only accepts devices tagged `tag:media-client` (and the tailnet admin's devices). In
the [Tailscale admin console](https://login.tailscale.com/admin/machines):

1. Find the iPad under **Machines**.
2. Open **⋯ → Edit ACL tags**, add `tag:media-client` and save.
3. Optional: in the same menu, **Disable key expiry**, so the iPad doesn't have to sign in to
   Tailscale again every few months.

## 3. Open HomeTV in Safari

1. In Safari, open `https://hometv.<your-tailnet>.ts.net`. Your tailnet name is shown on the
   **DNS** page of the admin console and in the Tailscale app.
2. Choose your profile. Profiles never ask for a PIN.

## 4. Add it to the Home Screen

In Safari, tap the **Share** button → **Add to Home Screen** → **Add**. A HomeTV icon then opens
the app directly.

You choose your profile once per browser. To switch, use the avatar menu (top right) →
**Cambiar de perfil**; **Cerrar sesión** forgets it on this iPad.

## Watching on the iPad

- **Playback**: films and episodes open in a full-screen player. MP4 files play directly. Other
  formats (MKV, files with surround audio) are repackaged by the server on the fly and start a
  little later (about 6 seconds), sometimes after "Preparando el vídeo…".
- **Volume** follows the iPad's buttons; the player has a mute button but no volume slider (iPadOS
  doesn't let web pages change the volume).
- **Audio and subtitles**: use the **Audio** and **Subtítulos** menus in the player. Your
  subtitle choice is remembered for the next time.
- **"Toca para reproducir"**: Safari sometimes blocks starting video by itself, for example after
  reopening the app or when the next episode starts. Tap the button to continue.
- **Resume**: stop at any time with **Volver**; the film or episode appears under *Seguir viendo*
  on every device of that profile, the TV included.
- **Administración** (admin profiles only): avatar menu → Administración, then enter the 4-digit
  PIN on the keypad.

## Troubleshooting

- **The page doesn't load / "Safari can't connect".** Open the Tailscale app and check that it
  says **Connected**. If you use VPN On Demand in Tailscale, its rule for your network must not
  be *Never*. Check that the iPad has the tag `tag:media-client` (step 2).
- **"Tu sesión ha caducado"**: your profile login expired after a long time unused; choose the
  profile again.
- **"No se puede reproducir este vídeo en este navegador"**: Safari can't play that file's video
  format. If the film has another version, the message offers it (**Probar …**); otherwise watch
  it on the TV.
- **Away from home**: it works anywhere the iPad has internet and Tailscale is connected. Hotel or
  captive-portal Wi-Fi may block Tailscale; try mobile data.
