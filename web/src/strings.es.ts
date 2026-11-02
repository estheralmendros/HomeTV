// The ONLY UI string catalog (es-ES, tú form). Every visible
// text, aria-label, alt, placeholder, title, toast and document.title comes from here. Texts
// with values are functions; plurals are chosen here (Intl.PluralRules es-ES). Later phases add
// their screens' groups (home, library, film, show, player, admin…).
import { countFormat, oneDecimalFormat, plural, pluralRules, starsFormat } from '@/lib/intl';

const APP_NAME = 'HomeTV';

const titleCount = (n: number) => plural(n, 'título', 'títulos');
const showCount = (n: number) => plural(n, 'serie', 'series');
/** "4,5 estrellas", "1 estrella", "0,5 estrellas": singular only for exactly 1 (ratings glossary). */
const starsText = (n: number) => `${starsFormat.format(n)} ${n === 1 ? 'estrella' : 'estrellas'}`;

type ErrorText = string | ((retryAfterSeconds: number | null) => string);

const errorTexts = {
  network_error: 'No se puede conectar con el servidor.',
  invalid_response: 'El servidor ha enviado una respuesta no válida.',
  not_found: 'Este contenido no está disponible. Puede que se haya eliminado o que su disco esté desconectado.',
  forbidden: 'Solo para administradores.',
  unauthorized: 'Tu sesión ha caducado. Elige un perfil para continuar.',
  too_many_sessions:
    'El servidor ya está convirtiendo el número máximo de vídeos. Detén la reproducción en otro dispositivo y vuelve a intentarlo.',
  too_many_attempts: (n: number | null) => `Demasiados PIN incorrectos. Vuelve a intentarlo en ${n ?? 60} s.`,
  invalid_pin: 'PIN incorrecto.',
  admin_locked: 'Introduce tu PIN de administrador para continuar.',
  pin_required: 'Los perfiles de administrador necesitan un PIN de 4 dígitos.',
  pin_not_allowed: 'Solo los perfiles de administrador tienen PIN.',
  scan_in_progress: 'Ya hay un escaneo en curso.',
  tmdb_unavailable: 'TMDB no responde. Vuelve a intentarlo más tarde.',
  tmdb_not_configured: 'No hay ninguna clave de API de TMDB configurada en el servidor.',
  tmdb_not_found: 'Esa entrada de TMDB ya no existe.',
  name_taken: 'Ya existe un perfil con ese nombre.',
  profile_limit_reached: 'Se ha alcanzado el máximo de 12 perfiles.',
  last_admin: 'Tiene que haber al menos un perfil de administrador.',
  file_missing: 'Falta el archivo de vídeo en el disco (¿está conectado el disco?).',
  invalid_audio_stream: 'Esa pista de audio no está disponible.',
  preparing: 'Preparando el vídeo…',
  hls_disk_full:
    'El servidor no tiene espacio para convertir vídeo ahora mismo. Vuelve a intentarlo en unos minutos o detén la reproducción en otro dispositivo.',
  transcoder_failed: 'El servidor no ha podido preparar este archivo para reproducirlo.',
  segment_timeout: 'El servidor ha tardado demasiado en preparar la siguiente parte del vídeo.',
  session_not_found: 'La sesión de reproducción ha terminado.',
  subtitle_pending: 'Los subtítulos aún se están preparando.',
  subtitle_extraction_failed: 'No se han podido extraer estos subtítulos del archivo.',
  subtitle_timeout: 'Los subtítulos han tardado demasiado en convertirse.',
  not_resolvable:
    'Este archivo no se puede leer, así que no se puede identificar. Repara o sustituye el archivo y escanea de nuevo.',
  not_a_file_entry: 'Esta entrada se tiene que corregir desde su película o serie.',
  media_file_not_linked: 'Esta versión ya no pertenece a este contenido.',
  profile_not_found: 'El perfil ya no existe.',
  artwork_not_found: 'Imagen no disponible.',
  validation_error: 'Revisa los datos introducidos.',
  already_bootstrapped: 'HomeTV ya está configurado.',
  invalid_library_folders: 'Revisa las carpetas marcadas.',
  relinks_unconfirmed: 'Hay títulos que se volverán a identificar; revisa los cambios.',
  outside_browse_root: 'Esa carpeta está fuera de la zona que se puede explorar.',
  folder_not_found: 'La carpeta no existe en el servidor.',
  folder_not_readable: 'HomeTV no tiene permiso para leer esta carpeta.',
  browse_unavailable: 'No se puede explorar el servidor.',
} satisfies Record<string, ErrorText>;

export const S = {
  app: { name: APP_NAME },

  /** document.title per route: "{page} · HomeTV". */
  titles: {
    page: (page: string) => `${page} · ${APP_NAME}`,
    default: APP_NAME,
    home: 'Inicio',
    films: 'Películas',
    shows: 'Series',
    search: 'Buscar',
    profiles: '¿Quién está viendo?',
    setup: 'Configuración inicial',
    admin: 'Administración',
    about: 'Acerca de',
    watchlist: 'Mi lista',
    activity: 'Actividad',
    player: 'Reproductor',
    notFound: 'Página no encontrada',
    error: 'Error',
  },

  common: {
    loading: 'Cargando…',
    retry: 'Reintentar',
    cancel: 'Cancelar',
    back: 'Volver',
    apply: 'Aplicar',
    save: 'Guardar',
    edit: 'Editar',
    delete: 'Eliminar',
    goHome: 'Ir a Inicio',
    previous: 'Anterior',
    next: 'Siguiente',
    loadMore: 'Cargar más',
    loadMoreError: 'No se han podido cargar más.',
    adminTag: 'Administrador',
    watched: 'Visto',
    markWatched: 'Marcar como visto',
    markUnwatched: 'Marcar como no visto',
    unknown: 'Desconocido',
    episodes: (n: number) => plural(n, 'episodio', 'episodios'),
    seasons: (n: number) => plural(n, 'temporada', 'temporadas'),
    unwatched: (n: number) => `${countFormat.format(n)} sin ver`,
    progressLabel: (percent: number) => `${countFormat.format(percent)} % visto`,
    titleWithYear: (title: string, year: number | null) => (year ? `${title} (${year})` : title),
  },

  nav: {
    home: 'Inicio',
    films: 'Películas',
    shows: 'Series',
    watchlist: 'Mi lista',
    activity: 'Actividad',
    /** Also the name of the magnifier icon link (compact bar, 720–959 px). */
    search: 'Buscar',
    searchPlaceholder: 'Buscar títulos…',
    searchLabel: 'Buscar títulos',
    mainLabel: 'Navegación principal',
    tabsLabel: 'Secciones',
    logo: 'HomeTV, ir a Inicio',
    profileMenu: (name: string) => `Menú de perfil de ${name}`,
    switchProfile: 'Cambiar de perfil',
    admin: 'Administración',
    about: 'Acerca de',
    signOut: 'Cerrar sesión',
  },

  about: {
    title: 'Acerca de',
    serverVersion: (version: string) => `Versión del servidor: ${version}`,
    tmdbLogoAlt: 'Logotipo de TMDB',
    tmdbLink: 'The Movie Database (TMDB)',
    privacy: 'Los vídeos se reproducen desde tu propio servidor. Nada sale de tu tailnet.',
  },

  crossTab: {
    banner: (name: string, current: string) =>
      `El perfil ha cambiado a ${name} en otra pestaña. Esta pestaña sigue con ${current}: recarga para cambiar.`,
    otherProfile: 'otro perfil',
    reload: 'Recargar',
    dismiss: 'Cerrar aviso',
  },

  profiles: {
    title: '¿Quién está viendo?',
    adminTag: 'Administrador',
    expired: 'Tu sesión ha caducado. Elige un perfil para continuar.',
    notFound: 'El perfil ya no existe.',
    empty: 'No hay ningún perfil configurado.',
    goToSetup: 'Ir a la configuración inicial',
  },

  setup: {
    title: 'Bienvenido a HomeTV',
    intro: 'Crea el primer perfil. Será el perfil de administrador.',
    name: 'Nombre',
    avatar: 'Avatar',
    avatarOption: (n: number) => `Avatar ${n}`,
    pin: 'PIN de administrador',
    pinConfirm: 'Confirmar PIN',
    pinHelp: 'Introducirás este PIN cuando uses las funciones de administración. No hace falta para entrar.',
    submit: 'Crear perfil',
    errors: {
      name: 'El nombre debe tener entre 1 y 30 caracteres.',
      avatar: 'Elige un avatar.',
      pin: 'El PIN debe tener 4 dígitos.',
      pinMismatch: 'Los PIN no coinciden.',
    },
  },

  adminPin: {
    title: 'Introduce el PIN de administrador',
    purpose: (purpose: string) => `Para: ${purpose}`,
    progress: (n: number) => `${n} de 4 dígitos introducidos`,
    keypad: 'Teclado numérico',
    backspace: 'Borrar',
    ok: 'OK',
    okLabel: 'Aceptar',
    invalid: 'PIN incorrecto.',
    tooManyAttempts: (clock: string) => `Demasiados intentos. Vuelve a intentarlo en ${clock}`,
    forbidden: 'Solo para administradores.',
  },

  admin: {
    onlyAdmins: 'Solo para administradores',
    title: 'Administración',
    tabsLabel: 'Secciones de administración',
    tabs: { scan: 'Escaneo', libraries: 'Bibliotecas', unmatched: 'Sin identificar', profiles: 'Perfiles' },

    scan: {
      title: 'Escaneo de la biblioteca',
      scanNow: 'Escanear ahora',
      states: { idle: 'Inactivo', running: 'En curso', completed: 'Completado', failed: 'Fallido' },
      triggers: {
        startup: 'al iniciar',
        scheduled: 'programado',
        manual: 'manual',
        libraries: 'cambio de carpetas',
        watch: 'cambio detectado',
      },
      stateWithTrigger: (state: string, trigger: string) => `${state} (${trigger})`,
      phases: {
        walking: 'Recorriendo carpetas',
        probing: 'Analizando',
        enriching: 'Obteniendo metadatos',
        done: 'Terminado',
      },
      phase: (phase: string) => `Fase: ${phase}`,
      started: (relative: string) => `Empezó ${relative}`,
      finished: (relative: string) => `Terminó ${relative}`,
      progressLabel: 'Progreso del escaneo',
      progress: (processed: number, total: number) => `${countFormat.format(processed)} / ${countFormat.format(total)}`,
      countsLabel: 'Resultados del escaneo',
      counts: {
        files_seen: 'Encontrados',
        new: 'Nuevos',
        changed: 'Modificados',
        unchanged: 'Sin cambios',
        ignored: 'Ignorados',
        unmatched: 'Sin identificar',
        missing: 'Ausentes',
        restored: 'Recuperados',
        removed: 'Eliminados',
      },
      countHints: {
        missing: 'Archivos que no se han encontrado en este escaneo. Están ocultos, pero se conserva su historial.',
        restored: 'Archivos ausentes que se han vuelto a encontrar.',
        removed: 'Archivos ausentes durante más tiempo que el periodo de gracia, ya borrados.',
      } as Partial<Record<string, string>>,
      missingWarning: (n: number) =>
        `${pluralRules.select(n) === 'one' ? 'Falta 1 archivo' : `Faltan ${countFormat.format(n)} archivos`}. Si hay un disco desconectado, vuelve a conectarlo y escanea de nuevo. No se borra nada durante unos días.`,
      errors: (n: number) => `Errores (${countFormat.format(n)})`,
      failedError: 'Error del escaneo',
      tmdbNotConfigured:
        'No hay ninguna clave de API de TMDB configurada. Los contenidos no tendrán carteles ni metadatos.',
      scopeLabel: 'Carpetas:',
      scopeLoose: (name: string) => `${name} (archivos sueltos)`,
      scopeWholeRoot: (name: string) => `${name} (toda la carpeta)`,
      scopeMore: (n: number) => `y ${countFormat.format(n)} más`,
      watcher: {
        line: (state: string) => `Detección de cambios: ${state}`,
        states: {
          active: 'activa',
          starting: 'iniciando…',
          disabled: 'desactivada',
          unavailable: 'no disponible',
          error: 'con errores',
        },
        unavailableIn: (folders: string) => `No disponible en: ${folders}`,
        technicalDetails: 'Detalles técnicos',
      },
      toast: {
        finished: (summary: string) => `Escaneo terminado: ${summary}`,
        noChanges: 'Sin cambios',
        new: (n: number) => plural(n, 'nuevo', 'nuevos'),
        changed: (n: number) => plural(n, 'modificado', 'modificados'),
        restored: (n: number) => plural(n, 'recuperado', 'recuperados'),
        removed: (n: number) => plural(n, 'eliminado', 'eliminados'),
        unmatched: (n: number) => `${countFormat.format(n)} sin identificar`,
        missing: (n: number) =>
          pluralRules.select(n) === 'one'
            ? 'Ahora falta 1 archivo: ¿hay algún disco desconectado?'
            : `Ahora faltan ${countFormat.format(n)} archivos: ¿hay algún disco desconectado?`,
        failed: 'El escaneo ha fallado.',
      },
    },

    libraries: {
      sections: { films: 'Películas', shows: 'Series' },
      empty: 'Sin carpetas',
      addFolder: 'Añadir carpeta',
      status: { available: 'Disponible', not_found: 'No encontrada', not_readable: 'Sin permiso' },
      titleCount: (n: number) => titleCount(n),
      added: 'Nueva',
      removed: 'Se quitará',
      remove: 'Quitar',
      removeLabel: (path: string) => `Quitar ${path}`,
      undo: 'Deshacer',
      undoLabel: (path: string) => `Deshacer quitar ${path}`,
      save: 'Guardar cambios',
      discard: 'Descartar',
      saved: 'Carpetas guardadas. Escaneo iniciado',
      savedNoScan: 'Carpetas guardadas',
      scanInProgress: 'Hay un escaneo en curso; guarda cuando termine.',
      ignoredEnv: (names: string[]) =>
        `HomeTV ya no usa ${names.join(', ')} para elegir las carpetas: ahora se gestionan desde esta pantalla. Puedes quitar esa configuración.`,
      errorSummary: 'Revisa las carpetas marcadas.',
      confirm: {
        title: (n: number) => (pluralRules.select(n) === 'one' ? '¿Quitar 1 carpeta?' : `¿Quitar ${countFormat.format(n)} carpetas?`),
        titleChanges: '¿Guardar estos cambios de carpetas?',
        body: (days: number) =>
          days === 0
            ? 'Sus títulos y su historial de reproducción se borrarán en el escaneo que empieza ahora.'
            : `Sus títulos dejarán de verse en el catálogo ahora y se borrarán dentro de ${plural(days, 'día', 'días')} si no vuelves a añadir la carpeta. Si la vuelves a añadir antes, volverán con su historial de reproducción.`,
        item: (library: string, path: string, n: number) => `${library} · ${path} (${titleCount(n)})`,
        movedItem: (fromLib: string, fromPath: string, toLib: string, toPath: string, n: number) =>
          `${fromLib} · ${fromPath} → ${toLib} · ${toPath} (${titleCount(n)})`,
        movedSameItem: (fromLib: string, toLib: string, path: string, n: number) => `${fromLib} → ${toLib} · ${path} (${titleCount(n)})`,
        movedBody:
          'Se volverán a identificar en la otra biblioteca y su historial de reproducción se borrará en el escaneo que empieza ahora. No se puede recuperar.',
        relayoutItem: (fromPath: string, toPath: string, n: number) => `Series · ${fromPath} → ${toPath} (${showCount(n)})`,
        relayoutBody:
          'Sus episodios se volverán a identificar a partir de la nueva carpeta en el escaneo que empieza ahora. Las series que salgan con otro nombre perderán su historial de reproducción; no se puede recuperar.',
        coveredItem: (library: string, path: string, by: string) => `${library} · ${path} → ${by}`,
        coveredBody: 'Sus títulos siguen dentro de la nueva carpeta y conservan su historial de reproducción.',
        submit: 'Quitar y guardar',
        submitChanges: 'Guardar cambios',
      },
      dialog: {
        title: (library: string) => `Añadir carpeta a ${library}`,
        pathLabel: 'Ruta de la carpeta en el servidor',
        placeholder: '/media/Peliculas',
        add: 'Añadir',
        browserLabel: 'Carpetas del servidor',
        current: (path: string) => `Carpeta actual: ${path}`,
        up: 'Subir',
        choose: 'Elegir esta carpeta',
        noSubfolders: 'No hay subcarpetas',
        browseUnavailable: (path: string) => `No se puede explorar ${path} en el servidor. Escribe la ruta completa.`,
        folderGone: 'Esta carpeta ya no existe.',
        folderUnreadable: 'HomeTV no tiene permiso para leer esta carpeta.',
        back: 'Volver',
        backToRoot: 'Volver al inicio',
      },
      errors: {
        not_absolute: 'Escribe la ruta completa; tiene que empezar por /.',
        not_found: 'La carpeta no existe en el servidor.',
        not_directory: 'Esa ruta no es una carpeta.',
        not_readable: 'HomeTV no tiene permiso para leer esta carpeta.',
        duplicate: 'Esta carpeta ya está en la lista.',
        overlaps_folder: (other: string | null) =>
          `Se solapa con ${other ?? 'otra carpeta'}: una carpeta no puede estar dentro de otra.`,
        contains_data_dir: 'Esta carpeta contiene los datos de HomeTV.',
        inside_data_dir: 'Esta carpeta está dentro de los datos de HomeTV.',
        contains_hls_dir: 'Esta carpeta contiene la carpeta temporal de reproducción de HomeTV.',
        inside_hls_dir: 'Esta carpeta está dentro de la carpeta temporal de reproducción de HomeTV.',
      },
    },

    unmatched: {
      title: 'Sin identificar',
      library: 'Biblioteca',
      reason: 'Motivo',
      allLibraries: 'Todas',
      allReasons: 'Todos',
      libraries: { films: 'Películas', shows: 'Series' } as Record<string, string>,
      reasons: {
        no_title: 'No se ha podido leer un título',
        no_season: 'Sin número de temporada',
        no_episode_number: 'Sin número de episodio',
        multi_part: 'Película en varias partes',
        probe_failed: 'No se ha podido analizar el archivo',
        no_tmdb_results: 'Sin resultados en TMDB',
        low_confidence: 'Identificación dudosa',
      } as Partial<Record<string, string>>,
      reasonWithPart: (reason: string, part: string) => `${reason} · ${part}`,
      probeFailedHint:
        'El archivo no se puede leer. Repáralo o sustitúyelo; esta entrada desaparecerá en el próximo escaneo correcto.',
      parsedLabel: 'Detectado',
      createdAt: (date: string) => `Añadida el ${date}`,
      pageOf: (page: number, pages: number) => `Página ${countFormat.format(page)} de ${countFormat.format(pages)}`,
      pagination: 'Paginación',
      empty: 'Todo está identificado.',
      emptyFiltered: 'No hay entradas sin identificar con estos filtros.',
    },

    profiles: {
      title: 'Perfiles',
      newProfile: 'Nuevo perfil',
      limitReached: 'Se ha alcanzado el máximo de 12 perfiles.',
      editLabel: (name: string) => `Editar ${name}`,
      deleteLabel: (name: string) => `Eliminar ${name}`,
      deleteTitle: (name: string) => `¿Eliminar ${name}?`,
      deleteBody: 'Se perderá su historial de reproducción.',
    },

    profileForm: {
      createTitle: 'Nuevo perfil',
      editTitle: 'Editar perfil',
      name: 'Nombre',
      avatar: 'Avatar',
      admin: 'Administrador',
      pinSection: 'PIN de administrador',
      keepPin: 'Mantener el PIN actual',
      changePin: 'Cambiar el PIN',
      pin: 'PIN',
      pinConfirm: 'Confirmar',
      demoteNote: 'Se eliminará su PIN de administrador.',
      errors: {
        name: 'El nombre debe tener entre 1 y 30 caracteres.',
        avatar: 'Elige un avatar.',
        pin: 'El PIN debe tener 4 dígitos.',
        pinMismatch: 'Los PIN no coinciden.',
      },
    },
  },

  home: {
    continueWatching: 'Seguir viendo',
    recentlyAdded: 'Añadidos recientemente',
    details: 'Detalles',
    detailsOf: (title: string) => `Detalles de ${title}`,
    emptyAdmin: 'Tu biblioteca está vacía. Añade las carpetas de tus vídeos en Administración → Bibliotecas o ejecuta un escaneo.',
    goToLibraries: 'Ir a Administración → Bibliotecas',
    goToScan: 'Ir a Administración → Escaneo',
    emptyViewer: 'La biblioteca está vacía. Pide a un administrador que ejecute un escaneo.',
    nothingToShow: 'Explora tu biblioteca.',
  },

  library: {
    toolbar: 'Filtros de la biblioteca',
    filterPlaceholder: 'Filtrar por título…',
    filterLabel: 'Filtrar por título',
    sort: 'Orden',
    sortTitle: 'Título',
    sortYear: 'Año',
    sortAdded: 'Añadidos recientemente',
    asc: 'Asc',
    desc: 'Desc',
    genre: 'Género',
    allGenres: 'Todos los géneros',
    genreOption: (name: string, count: number) => `${name} (${countFormat.format(count)})`,
    year: 'Año',
    yearFrom: 'Desde',
    yearTo: 'Hasta',
    yearRangeError: 'El año inicial no puede ser posterior al final.',
    watched: 'Estado',
    watchedAll: 'Todas',
    watchedOnly: 'Vistas',
    unwatchedOnly: 'No vistas',
    clearFilters: 'Borrar filtros',
    count: (n: number, type: 'films' | 'shows') =>
      type === 'films' ? plural(n, 'película', 'películas') : plural(n, 'serie', 'series'),
    noMatch: { films: 'Ninguna película coincide con estos filtros.', shows: 'Ninguna serie coincide con estos filtros.' },
    filters: 'Filtros',
    clear: 'Borrar',
    apply: 'Aplicar',
  },

  search: {
    hint: 'Busca películas y series por título.',
    inputLabel: 'Buscar películas y series',
    films: (n: number) => `Películas (${countFormat.format(n)})`,
    shows: (n: number) => `Series (${countFormat.format(n)})`,
    noResults: (q: string) => `No hay resultados para «${q}».`,
  },

  versions: {
    part: (n: number) => `Parte ${n}`,
    unreadable: '(ilegible)',
    mayNotPlay: '(puede que no se reproduzca en este navegador)',
  },

  film: {
    resume: (time: string) => `Reanudar desde ${time}`,
    resumePart: (part: number, time: string) => `Reanudar parte ${part} desde ${time}`,
    play: 'Reproducir',
    playPart: (part: number) => `Reproducir parte ${part}`,
    playFromStart: 'Ver desde el principio',
    noPlayableFile: 'No hay ningún archivo reproducible',
    version: 'Versión',
    cast: 'Reparto',
    /** "Reparto" link name: "Timothée Chalamet, Paul Atreides" (actor, character). */
    castMemberLabel: (name: string, character: string) => `${name}, ${character}`,
    fixMatch: 'Corregir identificación',
    notFound:
      'Esta película no está disponible. Puede que se haya eliminado o que su archivo esté en un disco desconectado.',
    backToFilms: 'Volver a Películas',
  },

  fixMatch: {
    title: (name: string) => `Corregir identificación: ${name}`,
    action: 'Corregir identificación',
    type: 'Tipo',
    types: { movie: 'Película', tv: 'Serie' },
    query: 'Título',
    year: 'Año',
    search: 'Buscar',
    results: 'Resultados de TMDB',
    noResults: 'No hay resultados en TMDB. Prueba con otro título o quita el año.',
    season: 'Temporada',
    episodes: 'Episodios',
    seasonOption: (name: string, episodes: string) => `${name} · ${episodes}`,
    episodeOption: (parts: string[]) => parts.join(' · '),
    episodesHint: 'Por ejemplo: 3 o 16-17',
    invalidSeason: 'Introduce un número de temporada.',
    invalidEpisodes: 'Introduce los episodios, por ejemplo 3 o 16-17.',
    loadingSeasons: 'Cargando temporadas…',
    matched: (name: string) => `Identificado como ${name}`,
    matchedParts: (name: string) =>
      `Identificado como ${name}. También se han vinculado las otras partes de esta película.`,
    open: 'Abrir',
    alreadyResolved: 'Ya estaba identificado.',
    notConfigured: 'No hay ninguna clave de API de TMDB configurada en el servidor.',
  },

  show: {
    seasonName: (n: number) => (n === 0 ? 'Especiales' : `Temporada ${n}`),
    episodeName: (n: number) => `Episodio ${n}`,
    resume: (code: string) => `Reanudar ${code}`,
    play: (code: string) => `Reproducir ${code}`,
    playEpisode: (code: string) => `Reproducir ${code}`,
    markShowWatched: 'Marcar serie como vista',
    markShowUnwatched: 'Marcar serie como no vista',
    markSeasonWatched: 'Marcar temporada como vista',
    markSeasonUnwatched: 'Marcar temporada como no vista',
    /** "22 episodios marcados como vistos" / "1 episodio marcado como no visto". */
    markedToast: (n: number, watched: boolean) =>
      pluralRules.select(n) === 'one'
        ? `${countFormat.format(n)} episodio marcado como ${watched ? 'visto' : 'no visto'}`
        : `${countFormat.format(n)} episodios marcados como ${watched ? 'vistos' : 'no vistos'}`,
    seasons: 'Temporadas',
    season: 'Temporada',
    remaining: (minutes: number) =>
      pluralRules.select(minutes) === 'one' ? `Queda ${countFormat.format(minutes)} min` : `Quedan ${countFormat.format(minutes)} min`,
    noEpisodes: 'No se han encontrado episodios.',
    notFound:
      'Esta serie no está disponible. Puede que se haya eliminado o que sus archivos estén en un disco desconectado.',
    backToShows: 'Volver a Series',
    status: {
      Ended: 'Finalizada',
      'Returning Series': 'En emisión',
      Canceled: 'Cancelada',
      'In Production': 'En producción',
      Planned: 'Planificada',
      Pilot: 'Piloto',
    } as Record<string, string | undefined>,
  },

  /** Player. */
  player: {
    loading: 'Cargando el vídeo…',
    playbackError: (message: string) => `Error de reproducción: ${message}`,
    decodeFailed: 'Este navegador no ha podido descodificar el archivo.',
    versionFailed: 'El servidor no puede preparar esta versión para reproducirla.',
    prepareTimeout: 'Este vídeo está tardando demasiado en prepararse. Vuelve a intentarlo en unos minutos.',
    recoveryCancelled: 'Se ha cancelado la recuperación de la reproducción.',
    audioUnsupported: 'Esa pista de audio no se puede reproducir en este navegador',
    differentVersion: 'Reproduciendo otra versión desde el principio',
    /** "Kill Bill: Vol. 1 (2003) · Parte 2". */
    titleWithPart: (title: string, part: number) => `${title} · Parte ${part}`,
    gone: {
      film: 'Esta película no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.',
      episode: 'Este episodio no está disponible ahora mismo. Puede que su archivo esté en un disco desconectado.',
    },
    technicalDetails: 'Detalles técnicos',
    otherVersions: 'Otras versiones',
    /** "Probar 1080p H264 · AAC 2.0 · 23,4 GB". */
    tryVersion: (label: string) => `Probar ${label}`,
    controls: {
      label: 'Controles del reproductor',
      back: 'Volver',
      play: 'Reproducir',
      pause: 'Pausa',
      rewind: 'Retroceder 10 segundos',
      forward: 'Avanzar 10 segundos',
      volume: 'Volumen',
      mute: 'Silenciar',
      unmute: 'Activar sonido',
      fullscreen: 'Pantalla completa',
      exitFullscreen: 'Salir de pantalla completa',
      position: 'Posición',
      /** aria-valuetext: "12 minutos y 31 segundos de 22 minutos". */
      positionValue: (current: string, total: string) => `${current} de ${total}`,
      time: 'Tiempo',
    },
    preparing: {
      plain: 'Preparando el vídeo…',
      /** N = ceil(estimate / 60); "unos 1 min" isn't Spanish, so N = 1 has its own form. */
      minutes: (n: number) =>
        pluralRules.select(n) === 'one'
          ? `Preparando el vídeo… queda alrededor de ${countFormat.format(n)} min`
          : `Preparando el vídeo… quedan unos ${countFormat.format(n)} min`,
      underMinute: 'Preparando el vídeo… queda menos de un minuto',
      audio: 'Cambiando el audio… preparando el vídeo',
      detail: 'Se está analizando este archivo para reproducirlo.',
      elapsed: 'Tiempo de espera',
    },
    tapToPlay: 'Toca para reproducir',
    nextEpisode: {
      label: 'Siguiente episodio',
      upNext: (code: string) => `A continuación: ${code}`,
      countdown: (n: number) => `Se reproducirá en ${countFormat.format(n)} s`,
      playNow: 'Reproducir ahora',
      cancel: 'Cancelar',
    },
    nextPart: {
      label: 'Siguiente parte',
      finished: (n: number) => `Parte ${n} terminada`,
      play: (n: number) => `Reproducir parte ${n}`,
      back: 'Volver',
    },
    ended: {
      label: 'Fin de la reproducción',
      back: 'Volver',
      replay: 'Volver a reproducir',
    },
    unsupported: {
      video_codec_unsupported: {
        heading: 'No se puede reproducir este vídeo en este navegador',
        body: (codec: string) =>
          `Este navegador no admite el códec de vídeo ${codec}. Prueba otra versión, otro navegador (Safari o Edge pueden admitir HEVC) o la app del televisor.`,
      },
      hls_not_supported: {
        heading: 'Este navegador no puede reproducir vídeo convertido',
        body: 'Usa una versión reciente de Chrome, Firefox, Edge o Safari.',
      },
      probe_failed: {
        heading: 'No se ha podido analizar este archivo',
        body: 'Puede que el archivo esté dañado. Un administrador puede revisarlo en Administración → Sin identificar.',
      },
      no_video_stream: { heading: 'Este archivo no tiene vídeo', body: null },
      duration_unknown: {
        heading: 'Este archivo no se puede convertir para reproducirlo',
        body: 'Se desconoce su duración, así que solo se puede reproducir directamente, y este navegador no puede reproducirlo directamente. Prueba otra versión o la app del televisor.',
      },
      keyframes_too_sparse: {
        heading: 'Este archivo no se puede convertir para reproducirlo',
        body: 'Sus fotogramas clave están demasiado separados para dividirlo en segmentos. Prueba otra versión, un navegador que pueda reproducir el archivo original directamente o la app del televisor.',
      },
      generic: {
        heading: 'No se puede reproducir este archivo en este navegador',
        body: 'Prueba otra versión o la app del televisor.',
      },
    },
    subtitles: {
      menu: 'Subtítulos',
      off: 'Desactivados',
      forced: 'Forzados',
      sdh: 'SDH',
      external: 'Externo',
      pending: 'Preparando…',
      none: 'No hay subtítulos disponibles',
      imageNote: 'Los subtítulos de imagen no se admiten en el navegador',
      pendingGaveUp: 'Los subtítulos aún se están preparando. Vuelve a intentarlo más tarde.',
      loadFailed: 'No se han podido cargar los subtítulos.',
    },
    audio: {
      menu: 'Audio',
    },
  },

  /** "Mi lista" toggle, /watchlist page and toolbar. */
  watchlist: {
    toggle: 'Mi lista',
    inList: 'En Mi lista',
    add: 'Añadir a Mi lista',
    remove: 'Quitar de Mi lista',
    title: 'Mi lista',
    heading: (n: number) => `Mi lista · ${countFormat.format(n)}`,
    toolbar: 'Opciones de Mi lista',
    typeLabel: 'Tipo',
    typeAll: 'Todo',
    typeFilms: 'Películas',
    typeShows: 'Series',
    sort: 'Orden',
    sortAdded: 'Añadidos recientemente',
    sortTitle: 'Título',
    sortYear: 'Año',
    /** Masculine: Mi lista mixes films and shows (the library selects say Todas/Vistas/No vistas). */
    watched: 'Estado',
    watchedAll: 'Todos',
    watchedOnly: 'Vistos',
    unwatchedOnly: 'No vistos',
    empty: 'Tu lista está vacía. Añade películas y series desde su ficha con «Mi lista».',
    noMatch: { films: 'No hay películas en tu lista.', shows: 'No hay series en tu lista.' },
    noMatchFilters: 'Ningún título de tu lista coincide con los filtros.',
    showAll: 'Ver todo',
  },

  /** Ratings (ratings-per-user glossary): stars, dialog, detail block. */
  ratings: {
    rate: 'Puntuar',
    change: 'Cambiar',
    mine: 'Tu puntuación',
    /** "4,0 (3)" */
    average: (stars: number, n: number) => `${oneDecimalFormat.format(stars)} (${countFormat.format(n)})`,
    averageHeading: 'Media de la familia',
    averageLabel: (stars: number, n: number) =>
      `Media de la familia: ${oneDecimalFormat.format(stars)} estrellas, ${plural(n, 'puntuación', 'puntuaciones')}`,
    question: '¿Qué te ha parecido?',
    notNow: 'Ahora no',
    remove: 'Quitar puntuación',
    saved: 'Puntuación guardada',
    saveError: 'No se ha podido guardar la puntuación.',
    updateError: 'No se ha podido actualizar tu puntuación.',
    stars: starsText,
    none: 'Sin puntuación',
    inputLabel: 'Puntuación',
    /** Accessible name of the other profiles' list on detail. */
    others: 'Puntuaciones de la familia',
    more: (n: number) => `y ${countFormat.format(n)} más`,
    less: 'Ver menos',
  },

  /** /activity: the household's ratings, newest first. */
  activity: {
    heading: 'Actividad',
    /** Link name of another profile's row: "Ana ha puntuado Dune con 4,5 estrellas, ayer". */
    row: (name: string, title: string, stars: string, time: string) => `${name} ha puntuado ${title} con ${stars}, ${time}`,
    ownRow: (title: string, stars: string, time: string) => `Has puntuado ${title} con ${stars}, ${time}`,
    ratedPrefix: 'ha puntuado',
    ownPrefix: 'Has puntuado',
    with: 'con',
    empty: 'Todavía nadie ha puntuado nada. Cuando alguien puntúe una película o serie, aparecerá aquí.',
    time: {
      now: 'ahora mismo',
      minutes: (n: number) => `hace ${plural(n, 'minuto', 'minutos')}`,
      hours: (n: number) => `hace ${plural(n, 'hora', 'horas')}`,
      yesterday: 'ayer',
    },
  },

  /** Actor screen (/people/:id). Dates are long es-ES; "–" is U+2013 with spaces. */
  person: {
    biography: 'Biografía',
    readMore: 'Leer más',
    readLess: 'Leer menos',
    inCatalog: 'En tu catálogo',
    asCharacter: (character: string) => `como ${character}`,
    lifeLiving: (birth: string, age: number) => `${birth} (${plural(age, 'año', 'años')})`,
    lifeDeceased: (birth: string, death: string, age: number) => `${birth} – ${death} (${plural(age, 'año', 'años')})`,
    lifeDeceasedNoAge: (birth: string, death: string) => `${birth} – ${death}`,
    deathOnly: (death: string) => `– ${death}`,
    empty: 'No aparece en ningún título disponible de tu catálogo.',
    incomplete: 'Puede que falten títulos: no se ha podido consultar TMDB.',
    notFound: 'Esta persona no está disponible.',
  },

  row: {
    previous: 'Anterior',
    next: 'Siguiente',
  },

  cards: {
    unmatchedTag: 'Sin identificar',
    /** Card link name with its subtitle: "Dune (2021), como Paul Atreides". */
    withSubtitle: (label: string, subtitle: string) => `${label}, ${subtitle}`,
    /** Card link name with the viewer's rating: "Dune (2021), tu puntuación: 4,5 estrellas". */
    withRating: (label: string, stars: string) => `${label}, tu puntuación: ${stars}`,
  },

  notFound: {
    title: 'Página no encontrada',
    goHome: 'Ir a Inicio',
  },

  routeError: {
    generic: 'Algo ha ido mal.',
    appUpdated: 'La aplicación se ha actualizado, pero no se han podido cargar sus archivos.',
    reload: 'Recargar',
    goHome: 'Ir a Inicio',
  },

  toast: {
    region: 'Notificaciones',
    dismiss: 'Cerrar notificación',
  },

  errors: {
    byCode: errorTexts as Record<string, ErrorText | undefined>,
    unknown: (code: string) => `Se ha producido un error inesperado (${code}).`,
  },
} as const;
