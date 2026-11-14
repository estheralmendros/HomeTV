# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.9.2] - Released on 2026-11-14 by Esther Almendros Pérez

### Fixed

#### TV app
- Opening the app again after leaving it with "Salir" sometimes selected the film that was newest the last time instead of a newly added one: "Inicio" now selects the first card of the fresh rows, unless you moved before they arrived.

## [0.9.1] - Released on 2026-11-14 by Esther Almendros Pérez

### Changed

#### Web app
- A film's versions are now listed by their file name (without the extension) instead of their resolution, codecs and size, in the "Versión" list of the film's page and in the "Otras versiones" buttons of the player. Parts keep their "Parte N ·" prefix and the "(ilegible)" and "(puede que no se reproduzca en este navegador)" notes stay.

#### TV app
- A film's versions are now listed by their file name (without the extension) instead of their resolution and codecs: in the "Versión" and "Versión de la parte" buttons and lists of the film's page, and in the player's list of other versions. A long name is cut with "…" on the button; the list shows it in full.

## [0.9.0] - Released on 2026-11-12 by Esther Almendros Pérez

### Added

#### TV app
- On a TV show's page, "Ver TxEx desde el principio" appears next to "Reanudar TxEx" and plays that episode from the beginning. It only shows when the episode can be resumed (at least 30 seconds watched and not marked as watched).

### Changed

#### TV app
- On a TV show's page, moving to another season scrolls its episode list to the episode that pressing Right will select: the season's next episode to watch, or its first episode. The list also opens at the next episode to watch. Going back from an episode to its own season keeps the list where it was.
- Coming back to the app after leaving it (for example with the remote's Home button) reloads "Inicio" and selects the first card of the first row, so a newly added film is the one selected. Going back from a title's page or the player still returns to the card you opened.

### Fixed

#### TV app
- Holding OK on a "Seguir viendo" card or on an episode opened its options and then also pressed their first button when OK was released (for example starting playback, or "Ver desde el principio").
- Screens no longer leave an empty strip at the bottom: on Inicio, a film's or TV show's page, a person's page and Ajustes, the content now scrolls down to the bottom edge of the screen, and the selected item still stays clear of the edge.

## [0.8.2] - Released on 2026-11-12 by Esther Almendros Pérez

### Fixed

#### Web app
- Some films whose subtitles or audio last longer than the video (for example, more than a minute past the end of the credits) couldn't be played in the browser, which showed "Este archivo no se puede convertir para reproducirlo". They now play. The TV app was not affected.

## [0.8.1] - Released on 2026-11-12 by Esther Almendros Pérez

### Added

#### Web app
- The search bar has a magnifier button at its right end: clicking it searches, just like pressing Enter. On narrower windows (below 960 px), the search field on the "Buscar" page has it too.

### Changed

#### Web app
- On a film's or TV show's page, the description now reaches about three quarters of the window instead of stopping around the middle, and it is justified (long words can break with a hyphen to avoid large gaps). On phones it takes the full width.
- The search bar is emptied when you switch screen: Inicio, Películas, Series, Mi lista, Actividad, the HomeTV logo or an entry of the profile menu. Opening a result and going back still keeps your search.
- Pressing Enter (or the magnifier) with an empty search bar no longer opens an empty "Buscar" page; it does nothing.

### Fixed

#### Web app
- In "Corregir identificación", the year field was too narrow to show its four digits.

## [0.8.0] - Released on 2026-11-12 by Esther Almendros Pérez

### Added

#### Web app and TV app
- The player remembers the audio track and the subtitles you choose, per profile and per title (a film, or a whole TV show). Every later playback of that title by that profile starts with the same choice, on the web and on the TV: from the detail page, "Seguir viendo", resuming, starting again, another version of the film, and every episode of the show, including the next episode played automatically. The choice is saved only when you pick a track yourself, so the defaults and automatic fallbacks never overwrite it.
- When the remembered track doesn't exist in the file being played (another version of a film, another episode), the player picks the closest one: the same language, preferring the same kind of track (for example forced or for the hearing impaired), or the usual default when that language isn't there.
- "Formato de los subtítulos", in the player's "Subtítulos" menu (web) and in the "Audio y subtítulos" dialog (TV), lets each profile choose how text subtitles look: "Tamaño" (Pequeño, Mediano, Grande), "Color" (Blanco, Amarillo, Verde, Cian, Magenta, Naranja), "Contorno" (Contorno, Sombra, Sin contorno), "Color del contorno" (Negro, Gris oscuro, Blanco) and "Posición" (Baja, Media, Alta). The subtitles on screen change while you choose, and a sample line "Así se verán los subtítulos" shows the style when no subtitle is visible. The style is the same on every device and on both TV players (the TV's own player and VLC). Image subtitles (such as Blu-ray PGS or DVD subtitles) keep their own colours.

#### Server
- Remembered choices and the subtitle style are stored on the server for each profile. A profile only sees and changes its own. Choices are deleted with their profile or title, and are kept when a title is merged or re-identified.

### Changed

#### Web app
- Subtitles no longer have a semi-transparent black box behind them: they use the chosen outline instead (white text with a black outline by default), so they look like on the TV.
- The last subtitle language is no longer kept per browser; the remembered choice on the server replaces it.

### Fixed

#### Web app
- Turning subtitles off ("Desactivados") while a line was on screen left that line frozen over the video (Firefox).

## [0.7.0] - Released on 2026-11-08 by Esther Almendros Pérez

### Added

#### TV app
- A built-in VLC player (libVLC 3.7.2) for videos the TV can't play by itself. Old-format films and episodes (Xvid/DivX and other AVI, WMV/ASF, FLV, RealMedia, MPEG-PS files) and codecs the TV has no decoder for (e.g. HEVC in 4K on a 1080p TV) now play directly from the server with VLC, with no conversion and nothing to choose: the player screen, controls, tracks, subtitles and resume point work the same with both players.
- When the TV's own player fails on a video (a decoding error, or the server can't convert the file for it), playback continues with VLC at the same point instead of showing an error. Each video gets this second chance once per playback.
- When a film has several versions, the one the TV plays by itself is still preferred. A version that only VLC can play is chosen when there is no other, and keeps its resume point.
- Ajustes → Reproducción → "Usar siempre VLC (experimental)" (admins only, shown when VLC is available) plays every video with VLC, for testing. It applies from the next playback, to every profile on that TV, and survives restarts and «Salir».
- Ajustes → Acerca de → "Licencias de código abierto" shows the open-source notices of the libraries the app includes, among them VLC's (LGPL).
- The remote's and headset's play/pause and seek keys also work while VLC is playing.

#### Documentation
- A new guide, `docs/server-automation-linux.md`, starts HomeTV at every boot once the media disk is mounted, and sets a daily power schedule (shut down at 02:00, power on at 15:00).
- The Linux installation guide and the deploy README explain how to mount external USB/NTFS disks for HomeTV: one `/etc/fstab` line per disk (read-only, the server still boots without the disk), making Docker wait for the disks at boot, and the fix for USB enclosures that keep disconnecting. A troubleshooting entry covers "Falta el archivo… en el disco del servidor".
- The TV app installation guide describes the VLC player, the "Usar siempre VLC" setting and the licence notices.

### Changed

#### TV app
- The APK is larger (about 95 MB, was about 3 MB) because it includes VLC, and it only installs on ARM devices (such as Chromecast with Google TV and Google TV Streamer); x86 emulators without ARM translation are not supported.

#### Server
- Disks mounted inside the media folder (`MEDIA_DIR`) after HomeTV started, such as a USB disk that is late at boot or reconnects after a reset, are now visible to HomeTV without restarting it. On Docker Desktop (Windows) add `MEDIA_PROPAGATION=rprivate` to `deploy/.env`, as the Windows installation guide explains.

## [0.6.1] - Released on 2026-11-07 by Esther Almendros Pérez

### Changed

#### TV app
- The search field on the Search screen now spans the whole width between the screen's safe margins, instead of keeping a fixed width that left an empty gap where the "Buscar por voz" button used to be.

### Fixed

#### TV app
- The "Buscar por voz" button listed as removed in 0.5.2 was still in the Search screen's code, and since 0.6.0 its missing label stopped the TV app from building. The button is now removed for good. Voice search still starts with the remote's search/microphone key on that screen, when the TV has a speech recognizer.

## [0.6.0] - Released on 2026-11-04 by Esther Almendros Pérez

### Added

#### TV app
- Leaving the app from Inicio asks first. Back on Inicio first moves the focus to the first card (or to the first card from the top nav); Back on the first card, or on Inicio's error screen, opens "¿Salir de la aplicación?" with [Salir] and [Cancelar] («Salir» focused). [Cancelar] or Back closes the dialog and leaves Inicio as it was.
- «Salir» signs the profile out: the next launch shows "¿Quién está viendo?" (the server address is kept, and so are the cached Inicio rows). The "Películas"/"Series" and "Mi lista" sorts go back to their defaults. Leaving with the remote's Home button still keeps the profile signed in.
- A rating prompt that arrives while the exit dialog is open waits behind it: it opens after [Cancelar], and «Salir» drops it.

### Fixed

#### TV app
- A session renewal still in flight could undo a profile change: a slow renewal could sign the previous profile back in after "Cambiar de perfil" (or after «Salir»), and a request refused before the switch was retried with the new profile's session. Renewals now only update the session of the profile that started them, and a request is never retried under another profile.

#### Documentation
- The TV app installation guide explains how to leave the app with «Salir» and how that differs from the Home button.

## [0.5.2] - Released on 2026-11-04 by Esther Almendros Pérez

### Removed

#### TV app
- The "Buscar por voz" button on the Search screen. It could not be reached with the remote (Right from the search field moves the text cursor instead of the focus). Voice search still starts with the remote's search/microphone key on that screen, when the TV has a speech recognizer.

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
