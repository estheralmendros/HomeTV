// Typed factories for every prd.md shape. Each takes `overrides` (shallow merge) and returns a
// fresh object, so tests can mutate results freely. Defaults are a coherent small library:
// film 12 "Dune" (2021, file 91), show 7 "The Office" (season 2, episode 345, file 501).
import type {
  AudioTrack,
  Avatar,
  BrowseResponse,
  CastMember,
  ClientCaps,
  EpisodeDetail,
  EpisodeSummary,
  FilmDetail,
  FilmSummary,
  GenreCount,
  HomeEpisode,
  HomeResponse,
  Image,
  Info,
  LibrariesPlan,
  LibrariesResponse,
  LibraryFolder,
  MediaFile,
  Page,
  PersonDetail,
  PersonFilmAppearance,
  PersonShowAppearance,
  PlaybackDirect,
  PlaybackHls,
  PlaybackUnsupported,
  Profile,
  Progress,
  ScanCounts,
  ScanScope,
  ScanStatus,
  SeasonDetail,
  SessionResponse,
  ShowDetail,
  ShowSeason,
  ShowSummary,
  SubtitleTrack,
  TmdbSearchResult,
  TmdbSeasonResponse,
  TmdbSeasonsResponse,
  UnlockResponse,
  UnmatchedEntry,
  VideoInfo,
  WatcherStatus,
} from '@/api/types';
import type { StoredSession } from '@/auth/session';

const T0 = '2026-10-21T18:04:05Z';
/** Far enough in the future for every test run. */
export const FUTURE = '2099-01-01T00:00:00Z';

export const AVATAR_COLORS = [
  '#E50914', '#F5A623', '#F8E71C', '#7ED321', '#417505', '#50E3C2',
  '#4A90E2', '#0F3C8C', '#9013FE', '#BD10E0', '#FF6F91', '#8B572A',
] as const;

export function avatars(): Avatar[] {
  return AVATAR_COLORS.map((color, i) => ({ id: `avatar-${String(i + 1).padStart(2, '0')}`, color }));
}

export function info(overrides: Partial<Info> = {}): Info {
  return {
    name: 'HomeTV',
    version: '0.1.0',
    needs_bootstrap: false,
    tmdb_configured: true,
    tmdb_attribution: 'Este producto utiliza la API de TMDB, pero no está respaldado ni certificado por TMDB.',
    avatars: avatars(),
    ...overrides,
  };
}

export function profile(overrides: Partial<Profile> = {}): Profile {
  return { id: 1, name: 'Ana', avatar_id: 'avatar-05', is_admin: false, created_at: T0, ...overrides };
}

export function adminProfile(overrides: Partial<Profile> = {}): Profile {
  return profile({ id: 1, name: 'Esther', is_admin: true, ...overrides });
}

/** The localStorage `hometv.session` value. */
export function session(overrides: Partial<StoredSession> = {}): StoredSession {
  return { token: 'token-1', profile_id: 1, expires_at: FUTURE, ...overrides };
}

export function sessionResponse(p: Profile = profile(), overrides: Partial<SessionResponse> = {}): SessionResponse {
  return { profile: p, token: `token-${p.id}`, expires_at: FUTURE, ...overrides };
}

export function unlockResponse(overrides: Partial<UnlockResponse> = {}): UnlockResponse {
  return { admin_token: 'admin-token-1', expires_at: FUTURE, ...overrides };
}

export function image(name = 'abc123', kind: 'poster' | 'backdrop' | 'still' | 'profile' = 'poster'): Image {
  const sizes = { poster: ['w342', 'w780'], backdrop: ['w780', 'w1280'], still: ['w300', 'w780'], profile: ['w185', 'h632'] };
  const [small, large] = sizes[kind];
  return { small: `/api/artwork/${small}/${name}.jpg`, large: `/api/artwork/${large}/${name}.jpg` };
}

export function progress(overrides: Partial<Progress> = {}): Progress {
  return {
    position_seconds: 1234.5,
    duration_seconds: 9360,
    watched: false,
    watched_at: null,
    media_file_id: 91,
    updated_at: T0,
    ...overrides,
  };
}

export function video(overrides: Partial<VideoInfo> = {}): VideoInfo {
  return { codec: 'h264', width: 1920, height: 1080, hdr: null, frame_rate: 23.976, ...overrides };
}

export function audioTrack(overrides: Partial<AudioTrack> = {}): AudioTrack {
  return {
    index: 1,
    codec: 'aac',
    channels: 2,
    channel_layout: 'stereo',
    language: 'eng',
    title: null,
    default: true,
    ...overrides,
  };
}

export function subtitleTrack(overrides: Partial<SubtitleTrack> = {}, fileId = 91): SubtitleTrack {
  const id = overrides.id ?? 'emb-3';
  const kind = overrides.kind ?? 'text';
  return {
    id,
    source: 'embedded',
    kind,
    codec: kind === 'text' ? 'subrip' : 'hdmv_pgs_subtitle',
    language: 'spa',
    title: null,
    forced: false,
    hearing_impaired: false,
    default: false,
    url: kind === 'text' ? `/api/media-files/${fileId}/subtitles/${id}.vtt` : null,
    ...overrides,
  };
}

export function mediaFile(overrides: Partial<MediaFile> = {}): MediaFile {
  const id = overrides.id ?? 91;
  return {
    id,
    file_name: 'Dune (2021).mp4',
    size_bytes: 23_456_789_012,
    container: 'mp4',
    duration_seconds: 9360.5,
    label: '1080p H264 · AAC 2.0',
    video: video(),
    audio_tracks: [audioTrack()],
    subtitle_tracks: [subtitleTrack({}, id)],
    part_number: null,
    probe_error: null,
    added_at: T0,
    ...overrides,
  };
}

export function filmSummary(overrides: Partial<FilmSummary> = {}): FilmSummary {
  return {
    type: 'film',
    id: 12,
    title: 'Dune',
    year: 2021,
    poster: image('dune-poster'),
    backdrop: image('dune-backdrop', 'backdrop'),
    overview: 'Paul Atreides, un joven brillante y con talento…',
    match_status: 'matched',
    added_at: T0,
    progress: null,
    ...overrides,
  };
}

export function showSummary(overrides: Partial<ShowSummary> = {}): ShowSummary {
  return {
    type: 'show',
    id: 7,
    title: 'The Office',
    year: 2005,
    poster: image('office-poster'),
    backdrop: image('office-backdrop', 'backdrop'),
    overview: 'Un falso documental sobre un grupo de oficinistas…',
    match_status: 'matched',
    added_at: T0,
    season_count: 9,
    episode_count: 201,
    unwatched_episode_count: 150,
    ...overrides,
  };
}

export function episodeSummary(overrides: Partial<EpisodeSummary> = {}): EpisodeSummary {
  return {
    type: 'episode',
    id: 345,
    show_id: 7,
    show_title: 'The Office',
    season_id: 70,
    season_number: 2,
    episode_number: 3,
    title: 'Office Olympics',
    overview: 'Michael y Dwight van a ver un piso…',
    still: image('office-s2e3', 'still'),
    air_date: '2005-10-04',
    runtime_minutes: 22,
    duration_seconds: 1320.4,
    show_poster: image('office-poster'),
    show_backdrop: image('office-backdrop', 'backdrop'),
    progress: null,
    ...overrides,
  };
}

export function episodeFile(overrides: Partial<MediaFile> = {}): MediaFile {
  const id = overrides.id ?? 501;
  return mediaFile({
    id,
    file_name: 'The Office - S02E03.mkv',
    size_bytes: 400_000_000,
    container: 'mkv',
    duration_seconds: 1320.4,
    label: '720p H264 · AAC 2.0',
    video: video({ width: 1280, height: 720 }),
    subtitle_tracks: [subtitleTrack({}, id)],
    ...overrides,
  });
}

export function homeEpisode(overrides: Partial<HomeEpisode> = {}): HomeEpisode {
  return { ...episodeSummary(), media_files: [episodeFile()], ...overrides };
}

export function episodeDetail(overrides: Partial<EpisodeDetail> = {}): EpisodeDetail {
  return {
    ...episodeSummary(),
    media_files: [episodeFile()],
    previous_episode_id: 344,
    next_episode_id: 346,
    ...overrides,
  };
}

export function castMember(overrides: Partial<CastMember> = {}): CastMember {
  return {
    person_id: 3,
    name: 'Timothée Chalamet',
    character: 'Paul Atreides',
    profile_image: image('chalamet', 'profile'),
    ...overrides,
  };
}

export function filmAppearance(overrides: Partial<PersonFilmAppearance> = {}): PersonFilmAppearance {
  return { kind: 'film', item: filmSummary(), character: 'Paul Atreides', ...overrides };
}

export function showAppearance(overrides: Partial<PersonShowAppearance> = {}): PersonShowAppearance {
  return { kind: 'show', item: showSummary(), character: 'Pam Beesly', ...overrides };
}

/** Person 3 "Timothée Chalamet" (fetched: bio, dates, place) appearing in Dune (film 12). */
export function personDetail(overrides: Partial<PersonDetail> = {}): PersonDetail {
  return {
    person_id: 3,
    name: 'Timothée Chalamet',
    profile_image: image('chalamet', 'profile'),
    biography: 'Timothée Hal Chalamet es un actor estadounidense.',
    biography_language: 'es-ES',
    birthday: '1995-12-27',
    deathday: null,
    place_of_birth: 'Manhattan, New York City, New York, USA',
    credits_complete: true,
    appearances: [filmAppearance()],
    ...overrides,
  };
}

export function filmDetail(overrides: Partial<FilmDetail> = {}): FilmDetail {
  return {
    type: 'film',
    id: 12,
    title: 'Dune',
    original_title: 'Dune',
    year: 2021,
    release_date: '2021-09-15',
    overview: 'Paul Atreides, un joven brillante y con talento…',
    tagline: 'Más allá del miedo, el destino aguarda.',
    runtime_minutes: 155,
    vote_average: 7.8,
    genres: [{ id: 878, name: 'Ciencia ficción' }, { id: 12, name: 'Aventura' }],
    poster: image('dune-poster'),
    backdrop: image('dune-backdrop', 'backdrop'),
    match_status: 'matched',
    tmdb_id: 438631,
    cast: [castMember()],
    media_files: [mediaFile()],
    progress: null,
    added_at: T0,
    ...overrides,
  };
}

export function showSeason(overrides: Partial<ShowSeason> = {}): ShowSeason {
  const n = overrides.season_number ?? 2;
  return {
    id: 68 + n,
    season_number: n,
    name: n === 0 ? 'Especiales' : `Temporada ${n}`,
    poster: image(`office-s${n}`),
    air_date: '2005-09-20',
    episode_count: 22,
    unwatched_episode_count: 10,
    ...overrides,
  };
}

export function showDetail(overrides: Partial<ShowDetail> = {}): ShowDetail {
  return {
    type: 'show',
    id: 7,
    title: 'The Office',
    original_title: 'The Office',
    year: 2005,
    first_air_date: '2005-03-24',
    status: 'Ended',
    overview: 'Un falso documental sobre un grupo de oficinistas…',
    vote_average: 8.6,
    genres: [{ id: 35, name: 'Comedia' }],
    poster: image('office-poster'),
    backdrop: image('office-backdrop', 'backdrop'),
    match_status: 'matched',
    tmdb_id: 2316,
    cast: [castMember({ person_id: 9, name: 'Steve Carell', character: 'Michael Scott' })],
    seasons: [showSeason({ season_number: 1, episode_count: 6 }), showSeason({ season_number: 2 })],
    next_episode: episodeSummary(),
    unwatched_episode_count: 150,
    added_at: T0,
    ...overrides,
  };
}

export function seasonDetail(overrides: Partial<SeasonDetail> = {}): SeasonDetail {
  const n = overrides.season_number ?? 2;
  return {
    id: 68 + n,
    show_id: 7,
    show_title: 'The Office',
    season_number: n,
    name: `Temporada ${n}`,
    overview: 'La segunda temporada…',
    poster: image(`office-s${n}`),
    air_date: '2005-09-20',
    episodes: [
      episodeSummary({ id: 343, season_number: n, episode_number: 1, title: 'The Dundies' }),
      episodeSummary({ id: 345, season_number: n, episode_number: 3 }),
    ],
    ...overrides,
  };
}

export function homeResponse(overrides: Partial<HomeResponse> = {}): HomeResponse {
  return {
    // An in-progress film and a Next Up episode (no progress), prd.md Rev 13.
    continue_watching: [filmSummary({ progress: progress() }), homeEpisode({ id: 346, episode_number: 4, title: 'The Fire', progress: null })],
    recently_added: [filmSummary({ id: 13, title: 'Heat', year: 1995 }), showSummary()],
    library: { films: 1234, shows: 87, empty: false },
    ...overrides,
  };
}

export function emptyHomeResponse(): HomeResponse {
  return homeResponse({
    continue_watching: [],
    recently_added: [],
    library: { films: 0, shows: 0, empty: true },
  });
}

export function page<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return { items, page: 1, page_size: 60, total: items.length, ...overrides };
}

export function genre(overrides: Partial<GenreCount> = {}): GenreCount {
  return { id: 18, name: 'Drama', count: 120, ...overrides };
}

export function scanCounts(overrides: Partial<ScanCounts> = {}): ScanCounts {
  return {
    files_seen: 1012, new: 240, changed: 2, unchanged: 769, ignored: 31,
    unmatched: 4, missing: 3, restored: 0, removed: 1,
    ...overrides,
  };
}

export function scanStatus(overrides: Partial<ScanStatus> = {}): ScanStatus {
  return {
    id: 14,
    state: 'completed',
    trigger: 'manual',
    phase: 'done',
    started_at: T0,
    finished_at: '2026-10-21T18:10:00Z',
    progress: { processed: 1012, total: 1012 },
    counts: scanCounts(),
    errors: [],
    error: null,
    unmatched_count: 4,
    scope: null,
    watcher: watcherStatus(),
    ...overrides,
  };
}

export function scanScope(overrides: Partial<ScanScope> = {}): ScanScope {
  return { root: '/media/películas', path: '/media/películas/A', recursive: true, ...overrides };
}

export function watcherStatus(overrides: Partial<WatcherStatus> = {}): WatcherStatus {
  return { state: 'active', unavailable_roots: [], detail: null, ...overrides };
}

export function idleScanStatus(overrides: Partial<ScanStatus> = {}): ScanStatus {
  return scanStatus({
    id: null, state: 'idle', trigger: null, phase: null, started_at: null, finished_at: null,
    progress: { processed: null, total: null }, counts: null, unmatched_count: 0,
    ...overrides,
  });
}

export function libraryFolder(overrides: Partial<LibraryFolder> = {}): LibraryFolder {
  return { path: '/media/Films', status: 'available', title_count: 412, ...overrides };
}

/** GET /api/admin/libraries: two film folders (one gone), one show folder, browsable /media. */
export function librariesResponse(overrides: Partial<LibrariesResponse> = {}): LibrariesResponse {
  return {
    films: [libraryFolder(), libraryFolder({ path: '/media/Films4K', status: 'not_found', title_count: 37 })],
    shows: [libraryFolder({ path: '/media/TV', title_count: 58 })],
    browse_root: { path: '/media', available: true },
    ignored_env: [],
    missing_grace_days: 7,
    ...overrides,
  };
}

/** An empty plan (a save that only adds harmless folders). */
export function librariesPlan(overrides: Partial<LibrariesPlan> = {}): LibrariesPlan {
  return { removed: [], covered: [], moved: [], relayout: [], ...overrides };
}

/** GET /api/admin/libraries/browse at the root (/media). */
export function browseResponse(overrides: Partial<BrowseResponse> = {}): BrowseResponse {
  return {
    path: '/media',
    parent: null,
    entries: [
      { name: 'Disco2', path: '/media/Disco2' },
      { name: 'Films', path: '/media/Films' },
      { name: 'TV', path: '/media/TV' },
    ],
    ...overrides,
  };
}

export function unmatchedEntry(overrides: Partial<UnmatchedEntry> = {}): UnmatchedEntry {
  return {
    id: 5,
    library: 'shows',
    reason: 'low_confidence',
    detail: '2 exact-title candidates: 2316, 2996',
    target: { type: 'show', id: 7, title: 'The Office', year: null },
    parsed: { kind: 'episode', show_title: 'The Office', show_year: null, season: 2, episodes: [3] },
    created_at: T0,
    ...overrides,
  };
}

export function unmatchedFileEntry(overrides: Partial<UnmatchedEntry> = {}): UnmatchedEntry {
  return unmatchedEntry({
    id: 6,
    library: 'films',
    reason: 'multi_part',
    detail: null,
    target: { type: 'media_file', id: 91, relative_path: 'Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi' },
    parsed: { kind: 'film', title: 'Kill Bill Vol 1', year: 2003, part: 1 },
    ...overrides,
  });
}

export function tmdbSearchResult(overrides: Partial<TmdbSearchResult> = {}): TmdbSearchResult {
  return {
    tmdb_id: 438631,
    title: 'Dune',
    original_title: 'Dune',
    year: 2021,
    overview: 'Paul Atreides, un joven brillante…',
    poster: image('tmdb-dune'),
    ...overrides,
  };
}

export function tmdbSeasons(overrides: Partial<TmdbSeasonsResponse> = {}): TmdbSeasonsResponse {
  return {
    tmdb_id: 2316,
    name: 'The Office',
    year: 2005,
    seasons: [
      { season_number: 1, name: 'Temporada 1', episode_count: 6, air_date: '2005-03-24' },
      { season_number: 2, name: 'Temporada 2', episode_count: 22, air_date: '2005-09-20' },
      { season_number: 0, name: 'Especiales', episode_count: 30, air_date: null },
    ],
    ...overrides,
  };
}

export function tmdbSeason(overrides: Partial<TmdbSeasonResponse> = {}): TmdbSeasonResponse {
  return {
    tmdb_id: 2316,
    season_number: 2,
    name: 'Temporada 2',
    episodes: [
      { episode_number: 1, title: 'The Dundies', air_date: '2005-09-20' },
      { episode_number: 3, title: 'Office Olympics', air_date: '2005-10-04' },
    ],
    ...overrides,
  };
}

export function playbackDirect(overrides: Partial<PlaybackDirect> = {}): PlaybackDirect {
  return {
    mode: 'direct',
    url: '/api/media-files/91/stream',
    audio_stream_index: 1,
    duration_seconds: 9360.5,
    subtitle_tracks: [subtitleTrack()],
    ...overrides,
  };
}

export function playbackHls(overrides: Partial<PlaybackHls> = {}): PlaybackHls {
  const sessionId = overrides.session_id ?? 'sess-1';
  return {
    mode: 'hls',
    url: `/api/hls/${sessionId}/index.m3u8`,
    session_id: sessionId,
    audio_stream_index: 1,
    audio_action: 'copy',
    duration_seconds: 9360.5,
    subtitle_tracks: [subtitleTrack()],
    ...overrides,
  };
}

export function playbackUnsupported(overrides: Partial<PlaybackUnsupported> = {}): PlaybackUnsupported {
  return {
    mode: 'unsupported',
    reason: 'video_codec_unsupported',
    detail: 'Video codec hevc is not supported by this client',
    ...overrides,
  };
}

/** Desktop Chrome capabilities (no HEVC). */
export function clientCaps(overrides: Partial<ClientCaps> = {}): ClientCaps {
  return {
    containers: ['mp4', 'webm'],
    video_codecs: ['h264', 'vp9', 'av1'],
    audio_codecs: ['aac', 'mp3', 'opus', 'flac', 'vorbis'],
    max_audio_channels: 6,
    hls: true,
    can_select_audio_track: false,
    ...overrides,
  };
}

export const WEBVTT = 'WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHola\n';
