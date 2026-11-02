# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.5.1] - Released on 2026-11-02 by Esther Almendros Pérez

### Added

#### Project
- HomeTV is released under the MIT License (`LICENSE`).

### Changed

#### Web app
- "Seguir viendo" on Home shows films as wide previews (the film's backdrop, title and year), like the episodes, instead of vertical posters. The progress bar, "Detalles" and the viewer's star badge are unchanged.

#### Documentation
- The README was rewritten: an overview with highlights, screenshots of the web app on desktop and phone, how it works, getting started, and tables for configuration, repository layout and the guides.

### Fixed

#### Server
- Films whose audio or subtitles run on after the last keyframe of the video (for example end credits after the picture stops) could not be converted for playback ("Sus fotogramas clave están demasiado separados…") although they stream fine. The final segment may now be up to twice the normal limit (60 s with the default 6 s segments); the other segments keep the 30 s limit.

#### Server, web app and TV app
- The version shown in "Acerca de" (web) and in the TV app's settings was stuck at 0.1.0. Every part now reports the release version from `version.txt`; the TV app's version code follows it too, so a new APK installs as an upgrade.

## [0.5.0] - Released on 2026-11-01 by Esther Almendros Pérez

### Added

#### Server
- "Mi lista": each profile has its own watchlist of films and shows. `PUT` / `DELETE /api/watchlist/{films|shows}/{id}` add and remove a title (adding it twice keeps the first date), and `GET /api/watchlist` lists it with a type filter (`type=all|films|shows`), a watched filter (`watched=true|false`) and a sort (`sort=added|title|year`). Film and show details report `in_watchlist`. Titles that are no longer available are hidden from the list but kept, so they come back if their files return.
- Ratings per profile: each profile can rate a film or a whole show from ½ to 5 stars in steps of ½ (`PUT /api/ratings/{films|shows}/{id}`) and remove its rating (`DELETE`). Film and show details return the viewer's rating, the other profiles' ratings (newest first) and the household average (one decimal, from two ratings), and every poster card in lists, Home, search, Mi lista and the actor screen carries the viewer's own rating (`my_rating`).
- Rating prompt: when a profile finishes a title, by playback or by marking it watched, `PUT /api/progress` and `POST /api/watched` return a `rating_prompt` with that title. A film is finished when it is watched; a show when its last episode in the catalog is watched (specials only count for shows that have nothing else). The prompt is offered only to a profile that has neither rated the title nor declined to: `POST /api/ratings/{films|shows}/{id}/dismiss-prompt` records "Ahora no", and removing a rating also stops the prompt for that title.
- Household activity: `GET /api/activity` lists every rating of the household, newest first, with the profile, the title and the stars.
- When a title is matched again onto one already in the catalog, Mi lista entries, ratings and declined prompts move with it (the earlier "added" date, the later rating and the first decline are kept).
- Database migrations `0005` (watchlist) and `0006` (ratings and declined prompts); run automatically at startup.

#### Web app
- "Mi lista" in the navigation (NavBar and the phone tab bar) and a "Mi lista" button on film and show pages to add or remove the title. The "Mi lista" page shows the profile's titles with the count ("Mi lista · 4"), filters by type (Todo / Películas / Series) and by status ("Estado": Todos / Vistos / No vistos), sorts by date added, title or year, keeps the filters in the address, loads more titles while scrolling, and offers "Ver todo" when nothing matches the filters.
- Star ratings: film and show pages have a ratings block with "Tu puntuación" ([Puntuar] / [Cambiar], with [Quitar puntuación] in the dialog), "Media de la familia" ("4,0 (3)") and the other profiles' ratings (three, then "y N más"). Stars are set with the mouse or the arrow keys, in halves.
- After finishing a title, or marking it watched, the app asks for a rating once ("¿Qué te ha parecido?"), with [Guardar] and [Ahora no]. At the end of a film or a show's last episode in the player, the question replaces "Fin de la reproducción"; leaving without answering counts as "Ahora no".
- Poster cards show the viewer's own rating as a "★ 4,5" badge.
- "Actividad" in the navigation: a page with the household's latest ratings ("Ana ha puntuado Dune con ★★★★½", "hace 5 minutos", "ayer", "12 oct"), each row linking to the title.

#### TV app
- "Mi lista" tab in the top nav and a "Mi lista" button on film and show details, ready the moment it is pressed (it rolls back with a message if the server refuses). The "Mi lista" screen has the poster grid with "Ordenar", "Tipo" and "Estado" buttons; the sort is remembered while the app is open, the filters reset when the screen is left, and "Ver todo" clears them when nothing matches.
- Star ratings on film and show details: a "Tu puntuación" chip that opens the rating dialog (Left/Right in halves, OK saves, [Quitar puntuación]), the household average and the other profiles' ratings ("y N más" opens the full list). Poster cards show the profile's own rating as a star badge.
- The rating prompt opens over the screen you return to after finishing a title or marking it watched, never during playback; Back or [Ahora no] declines it. A prompt for one profile is never shown to another.
- "Actividad" tab: the household's latest ratings with avatars, stars, relative times and posters; OK opens the title, and the list updates when someone rates.

### Changed

#### Web app
- On the actor page the biography sits in the text column next to the photo (below the header on phones).

### Fixed

#### Web app
- The active link in the NavBar no longer grows 2 px taller than the others.
- Adding or removing a title from Mi lista while offline now rolls back with a message.
- Changing the sort quickly on Mi lista no longer brings back the previous type filter.

#### TV app
- The Actividad row that is focused is fully visible and centred.

## [0.4.0] - Released on 2026-10-28 by Esther Almendros Pérez

### Added

#### Server
- Actor details: `GET /api/people/{id}` returns an actor's photo, name, birth and death dates, place of birth, biography and the titles of this catalog they appear in, each with the character they play, newest first. Only available titles are listed; titles outside the catalog are never shown.
- The actor's details and full TMDB credits are fetched the first time the actor is opened (waiting up to 5 s) and cached for 30 days; after that the cached copy answers at once and is refreshed in the background. So actors billed after the first 15 of a title, or who appear only in an earlier season of a show, are found too. The biography is in Spanish, in English when TMDB has no Spanish one.
- Without a TMDB key, or when TMDB can't be reached, the actor's titles come from the cast already stored in the catalog, marked as possibly incomplete (`credits_complete: false`).
- Actor photos have a large size (`h632`) for the actor screen; the "Reparto" row keeps the small one.
- Database migration `0004` adds the actor details and the `person_credit` table; run automatically at startup.

#### Web app
- The actors in "Reparto" on film and show pages are links to a new actor page (`/people/:id`): photo (or the actor's initial), name, life span with age ("27 de diciembre de 1995 (30 años)"), place of birth, the biography clamped to four lines with "Leer más", and "En tu catálogo" with the actor's titles, each card showing the character ("como …").
- The actor page shows "Esta persona no está disponible." for an unknown actor, "No aparece en ningún título disponible de tu catálogo." when none of their titles is available, a note when the list may be incomplete, and the connection error with "Reintentar" when nothing could be loaded. An actor already on screen stays visible when a refresh fails (e.g. offline), and the page refreshes after playback.

#### TV app
- OK on a cast card in a film's "Reparto" opens the new Person screen: photo, name, life span, place of birth, a three-line biography that opens in full with OK (scroll with Up/Down, Back closes), and "En tu catálogo" with the actor's titles, ready to open and play. Back returns to the same cast card, and the Person screen refreshes after playback, keeping the focused card.
- The Person screen shows "Esta persona no está disponible." for an actor that no longer exists, the empty and possibly-incomplete messages, and the usual connection error.

## [0.3.0] - Released on 2026-10-27 by Esther Almendros Pérez

### Added

#### Server
- Change detection: on a Linux host, new, renamed and deleted media files reach the catalog about a minute after a copy ends, without waiting for the periodic scan. The server watches the library folders (inotify, read-only), waits for a quiet period, then scans only the changed folders and looks up their metadata (scan trigger `watch`). The periodic scan stays as a safety net. Folders on network shares or Docker Desktop bind mounts are reported as not watched.
- Settings `HOMETV_SCAN_WATCH_ENABLED` (default `true`) and `HOMETV_SCAN_WATCH_QUIET_SECONDS` (default `30`, minimum `5`), also as `scan.watch_enabled` / `scan.watch_quiet_seconds` in `config.yaml`.
- The scan status (`GET /api/admin/scan`) reports the folders of a change-triggered scan (`scope`, stored in `scan_run.scope`) and the state of change detection (`watcher`: active, starting, disabled, unavailable or error, with the folders that are not watched and a technical detail).

#### Web app
- Administración → Escaneo names scans started by a file change ("cambio detectado"), lists the folders they scanned ("Carpetas: …"), and always shows the state of change detection ("Detección de cambios: …"), the folders that are not watched and, on errors, the technical details. The card refreshes by itself while change detection is starting.

### Changed

#### Documentation
- The Linux guide and `deploy/README.md` explain change detection, where its settings go (the `app` service's `environment:` in `docker-compose.yml`), and how to raise the host's inotify limits (`fs.inotify.max_user_watches`, `fs.inotify.max_user_instances`) when the scan card reports errors.
- The Windows guide explains that change detection is not available with Docker Desktop: new files appear with the periodic scan or "Escanear ahora".

## [0.2.0] - Released on 2026-10-26 by Esther Almendros Pérez

### Added

#### Server
- The film and TV folders are stored by the server and changed from the admin area, with no restart and no compose edit. `HOMETV_FILMS_PATHS` / `HOMETV_SHOWS_PATHS` (or the YAML keys) are only read on the first start to fill them in (an existing installation keeps the folders of the files it already indexed), then ignored; `HOMETV_MEDIA_BROWSE_ROOT` (default `/media`) is the base of the folder browser.
- Before folder changes are saved, the server reports their consequences. Moving a folder between Películas and Series, or changing the Series folder so the show folders change, needs explicit confirmation: those titles are identified again and their watch history is deleted. Titles of a removed folder disappear from the catalog at once and are deleted after the grace period (`HOMETV_SCAN_MISSING_GRACE_DAYS`, 7 days by default); adding the folder back before then restores them with their history.

#### Web app
- "Bibliotecas" tab in Administración to manage the Películas and Series folders: each folder shows whether it is available and how many titles it has; folders are added by typing a path or by picking one in a server folder browser, and removed with an undo before saving. Saving shows a confirmation with what will happen to the affected titles when needed, then starts a scan ("cambio de carpetas").
- When the library is empty, admins get a link to Bibliotecas next to the scan link.

### Changed

#### Server
- Films now count as watched at 95 % (was 90 %), so closing one during its credits no longer marks it watched; TV episodes stay at 90 %.
- The Docker Compose file no longer sets the library folders: mount one broad parent folder at `/media` (and more disks under it) and choose the folders in Administración → Bibliotecas.

## [0.1.0] - Released on 2026-10-25 by Esther Almendros Pérez

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
