# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

#### Server
- Self-hosted media server for one household: indexes films and TV shows from one or more folders on a local disk, read-only (media files are never modified, moved or deleted).
- Library scanning at startup, every 6 hours (`HOMETV_SCAN_INTERVAL_HOURS`) and on demand; it detects new, changed, moved and removed files, keeps missing files for a 7-day grace period, and recognizes multi-version films, films split in parts, multi-episode files and external subtitle files.
- Files that can't be identified are listed as "unmatched" with the reason, and an admin can fix them by hand.
- Spanish metadata and artwork from TMDB (posters, backdrops, synopsis, cast, genres, ratings, seasons and episodes), cached locally; manual "fix match" for wrong identifications. Works without a TMDB key, showing file names.
- Playback for any client: direct play with seeking when the device supports the file, otherwise on-the-fly HLS remux (video copied, audio converted when needed, no video transcoding), with audio track selection and fast far seeks.
- Embedded and external subtitles served as WebVTT, including legacy encodings (e.g. Windows-1252).
- Profiles without PINs for viewing, with an admin PIN (and brute-force protection) for administration only.
- Per-profile watch history: resume position, watched marks (automatic at 90 %), and one "Seguir viendo" (Continue watching) list that mixes in-progress titles with the next episode of the series you are following.
- Limits on simultaneous streams and on temporary streaming disk space.
- Docker deployment with a Tailscale sidecar: reachable only from your tailnet over HTTPS (`https://hometv.<tailnet>.ts.net`), with no ports opened on the host or the LAN.
- "Watched" filter for the film and series lists (`watched=true|false`), per profile: a series counts as watched when all its regular episodes are; in-progress films count as not watched.

#### Web app
- Spanish web app served by the server, for desktop and mobile browsers (including iPad/iPhone Safari): first-run setup, profile picker, Home rows (Seguir viendo, Recently added), film and series grids with filters and sorting, global search, film and series detail pages.
- Full-screen player with resume, keyboard shortcuts, fullscreen, audio and subtitle menus, version and part selection, next-episode countdown, "next part" prompt, and clear messages when a file can't be played in the browser.
- Administration area behind the admin PIN: library scan status and "Scan now", unmatched files, fix match, and profile management.
- Mobile layout with a bottom tab bar and a filters sheet; keyboard-accessible season tabs.
- Recovers on its own after a server update (stale files reload once, then a clear "app updated" message).
- "Estado" filter (Todas / Vistas / No vistas) in the Películas and Series grids, on desktop and in the phone filters sheet.

#### Android TV app
- Spanish app for Chromecast with Google TV and Google TV Streamer, navigable with the remote: server setup, profile picker, Home with hero and rows, browse grids with sorting, search with voice input, film and series detail, settings.
- Native player with direct play when the TV supports the file (including surround audio passthrough) and HLS otherwise, overlay controls, accelerated seeking, audio and subtitle selection, autoplay of the next episode and next part, and resume across devices.
- Detail screens refresh after playback, so the next part or next episode is offered right away.
- Clear error screens for an unreachable server (with a shortcut to the Tailscale app), missing files, removed titles and the stream limit.
- Películas and Series filter buttons: "Ordenar" (list dialog), "Género", "Años" and "Estado" (watched / not watched); the filters reset when you leave the screen.
- Focusable, scrollable cast row ("Reparto") on film detail.

#### Documentation
- Installation guides for the server on Linux and on Windows (Docker Desktop), for the Android TV app, and for using HomeTV on an iPad.

### Changed

#### Server
- The default limit of simultaneous converted (HLS) streams (`HOMETV_MAX_SESSIONS`) is now 4 (was 3). Direct play is not limited.
- Home has one "Seguir viendo" row: the next episode of each series you follow ("A continuación") now appears in it, next to the films and episodes you left halfway, with exactly one card per series (whichever you watched most recently: the episode you left halfway or the next one), newest first. API change: `GET /api/home` no longer returns `next_up`; `continue_watching` holds both kinds of card.

#### Web app
- The TMDB banner at the bottom of every page is gone; the TMDB logo and attribution are only on "Acerca de" (`/about`).
- One "Seguir viendo" row on Home instead of separate "Seguir viendo" and "A continuación" rows.
- Headset and Bluetooth play/pause (and the keyboard's media keys) control the player, and the episode or film shows in the system's "Now Playing" controls.

#### Android TV app
- Redesigned UI: a top bar with Search as a lens icon at its left end (the keyboard's search key closes the keyboard), a full-bleed Home hero backdrop with the year below the title, readable text and focus states on the dark theme, dimmed detail backdrops so the text over artwork stays legible, and a banner and launcher icon that match the web logo.
- The "Ajustes" tab is shown only to admin profiles; viewers change profile with the avatar at the right end of the top bar. "Cambiar servidor" in Ajustes asks for the admin PIN every time (checked by the server, with the same lockout after wrong attempts); the connection error screen no longer offers it.
- One "Seguir viendo" row on Home instead of separate "Seguir viendo" and "A continuación" rows.
- Play/pause from Bluetooth headphones, a wired headset button or other media controllers works in the player, like the remote's Play/Pause key.
- The on-screen keyboard opens in Spanish (with the "ñ" key) in Search and server setup when Gboard has Spanish enabled.
