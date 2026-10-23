// Hand-written from prd.md § REST API Reference (Rev 9). Field names are 1:1 with the server
// (snake_case). Re-diff against server/hometv/api/schemas.py when prd.md changes.

/** ISO 8601 UTC timestamp, e.g. "2026-10-21T18:04:05Z". */
export type Timestamp = string;
/** Calendar date, e.g. "2021-09-15". */
export type IsoDate = string;

// --- Shared --------------------------------------------------------------------------------

export interface Page<T> {
  items: T[];
  page: number;
  page_size: number;
  total: number;
}

/** `/api/artwork/...` paths; clients append `?token=` (urls.withToken). */
export interface Image {
  small: string;
  large: string;
}

export interface Progress {
  position_seconds: number;
  duration_seconds: number;
  watched: boolean;
  watched_at: Timestamp | null;
  media_file_id: number | null;
  updated_at: Timestamp;
}

// --- System & profiles ---------------------------------------------------------------------

export interface Avatar {
  id: string;
  color: string;
}

export interface Info {
  name: string;
  version: string;
  needs_bootstrap: boolean;
  tmdb_configured: boolean;
  /** Spanish TMDB notice (Rev 9), shown as received. */
  tmdb_attribution: string;
  avatars: Avatar[];
}

export interface Profile {
  id: number;
  name: string;
  avatar_id: string;
  is_admin: boolean;
  created_at: Timestamp;
}

export interface ProfilesResponse {
  items: Profile[];
}

export interface BootstrapRequest {
  name: string;
  avatar_id: string;
  pin: string;
}

export interface SessionResponse {
  profile: Profile;
  token: string;
  expires_at: Timestamp;
}

export interface UnlockResponse {
  admin_token: string;
  expires_at: Timestamp;
}

export interface ProfileCreateRequest {
  name: string;
  avatar_id: string;
  is_admin: boolean;
  pin?: string;
}

/** PATCH body: only the fields that change are sent. */
export interface ProfileUpdateRequest {
  name?: string;
  avatar_id?: string;
  is_admin?: boolean;
  pin?: string;
}

// --- Media files ---------------------------------------------------------------------------

export type HdrFormat = 'hdr10' | 'hlg' | 'dolby_vision';

export interface VideoInfo {
  codec: string;
  width: number | null;
  height: number | null;
  hdr: HdrFormat | null;
  frame_rate: number | null;
}

export interface AudioTrack {
  index: number;
  codec: string;
  channels: number | null;
  channel_layout: string | null;
  language: string;
  title: string | null;
  default: boolean;
}

export interface SubtitleTrack {
  id: string;
  source: 'embedded' | 'external';
  kind: 'text' | 'image';
  codec: string;
  language: string;
  title: string | null;
  forced: boolean;
  hearing_impaired: boolean;
  default: boolean;
  /** null for image tracks. */
  url: string | null;
}

/** A "version" of a film or episode. */
export interface MediaFile {
  id: number;
  file_name: string;
  size_bytes: number;
  container: string | null;
  duration_seconds: number | null;
  /** Server-built label; "Desconocido" when the probe failed (Rev 9). */
  label: string;
  video: VideoInfo | null;
  audio_tracks: AudioTrack[];
  subtitle_tracks: SubtitleTrack[];
  /** 1, 2, … for parts of a split film; null for a standalone full version. */
  part_number: number | null;
  probe_error: string | null;
  added_at: Timestamp;
}

// --- Catalog -------------------------------------------------------------------------------

export type MatchStatus = 'matched' | 'unmatched' | 'low_confidence' | (string & {});

export interface FilmSummary {
  type: 'film';
  id: number;
  title: string;
  year: number | null;
  poster: Image | null;
  backdrop: Image | null;
  overview: string | null;
  match_status: MatchStatus;
  added_at: Timestamp;
  progress: Progress | null;
}

export interface ShowSummary {
  type: 'show';
  id: number;
  title: string;
  year: number | null;
  poster: Image | null;
  backdrop: Image | null;
  overview: string | null;
  match_status: MatchStatus;
  added_at: Timestamp;
  season_count: number;
  episode_count: number;
  unwatched_episode_count: number;
}

export interface EpisodeSummary {
  type: 'episode';
  id: number;
  show_id: number;
  show_title: string;
  season_id: number;
  season_number: number;
  episode_number: number;
  title: string | null;
  overview: string | null;
  still: Image | null;
  air_date: IsoDate | null;
  runtime_minutes: number | null;
  duration_seconds: number | null;
  show_poster: Image | null;
  show_backdrop: Image | null;
  progress: Progress | null;
}

/** Continue Watching / Next Up episode entry: same version order as GET /api/episodes/{id}. */
export type HomeEpisode = EpisodeSummary & { media_files: MediaFile[]; show_backdrop: Image | null };

export interface LibraryCounts {
  films: number;
  shows: number;
  empty: boolean;
}

export interface HomeResponse {
  continue_watching: Array<FilmSummary | HomeEpisode>;
  next_up: HomeEpisode[];
  recently_added: Array<FilmSummary | ShowSummary>;
  library: LibraryCounts;
}

export interface GenreRef {
  id: number;
  name: string;
}

export interface GenreCount extends GenreRef {
  count: number;
}

export interface GenresResponse {
  items: GenreCount[];
}

export interface CastMember {
  person_id: number;
  name: string;
  character: string | null;
  profile_image: Image | null;
}

export interface FilmDetail {
  type: 'film';
  id: number;
  title: string;
  original_title: string | null;
  year: number | null;
  release_date: IsoDate | null;
  overview: string | null;
  tagline: string | null;
  runtime_minutes: number | null;
  vote_average: number | null;
  genres: GenreRef[];
  poster: Image | null;
  backdrop: Image | null;
  match_status: MatchStatus;
  tmdb_id: number | null;
  cast: CastMember[];
  media_files: MediaFile[];
  progress: Progress | null;
  added_at: Timestamp;
}

export interface ShowSeason {
  id: number;
  season_number: number;
  name: string | null;
  poster: Image | null;
  air_date: IsoDate | null;
  episode_count: number;
  unwatched_episode_count: number;
}

export interface ShowDetail {
  type: 'show';
  id: number;
  title: string;
  original_title: string | null;
  year: number | null;
  first_air_date: IsoDate | null;
  /** TMDB value ("Ended", "Returning Series", …), mapped by the catalog. */
  status: string | null;
  overview: string | null;
  vote_average: number | null;
  genres: GenreRef[];
  poster: Image | null;
  backdrop: Image | null;
  match_status: MatchStatus;
  tmdb_id: number | null;
  cast: CastMember[];
  /** Ascending; specials (0) last. */
  seasons: ShowSeason[];
  next_episode: EpisodeSummary | null;
  unwatched_episode_count: number;
  added_at: Timestamp;
}

export interface SeasonDetail {
  id: number;
  show_id: number;
  show_title: string;
  season_number: number;
  name: string | null;
  overview: string | null;
  poster: Image | null;
  air_date: IsoDate | null;
  episodes: EpisodeSummary[];
}

export interface EpisodeDetail extends EpisodeSummary {
  media_files: MediaFile[];
  previous_episode_id: number | null;
  next_episode_id: number | null;
}

export interface SearchResponse {
  films: FilmSummary[];
  shows: ShowSummary[];
}

export type LibraryType = 'films' | 'shows';
export type SortField = 'title' | 'year' | 'added';
export type SortOrder = 'asc' | 'desc';

/** Query params of GET /api/films and GET /api/shows. */
export interface LibraryQuery {
  page?: number;
  page_size?: number;
  q?: string;
  sort?: SortField;
  order?: SortOrder;
  genre?: number;
  year_from?: number;
  year_to?: number;
}

// --- Watch state ---------------------------------------------------------------------------

export type ItemType = 'film' | 'episode';

export interface ProgressRequest {
  item_type: ItemType;
  item_id: number;
  media_file_id: number;
  position_seconds: number;
  duration_seconds?: number | null;
}

export type WatchedTarget = 'film' | 'episode' | 'season' | 'show';

export interface WatchedRequest {
  target: WatchedTarget;
  id: number;
  watched: boolean;
}

export interface WatchedResponse {
  updated: number;
}

// --- Playback ------------------------------------------------------------------------------

export interface ClientCaps {
  containers: string[];
  video_codecs: string[];
  audio_codecs: string[];
  max_audio_channels: number;
  hls: boolean;
  can_select_audio_track: boolean;
}

export interface PlaybackRequest {
  client_caps: ClientCaps;
  audio_stream_index: number | null;
  start_position_seconds: number;
  previous_session_id: string | null;
}

export type UnsupportedReason =
  | 'probe_failed'
  | 'no_video_stream'
  | 'video_codec_unsupported'
  | 'hls_not_supported'
  | 'duration_unknown'
  | 'keyframes_too_sparse';

export interface PlaybackDirect {
  mode: 'direct';
  url: string;
  audio_stream_index: number | null;
  duration_seconds: number | null;
  subtitle_tracks: SubtitleTrack[];
}

export interface PlaybackHls {
  mode: 'hls';
  url: string;
  session_id: string;
  audio_stream_index: number | null;
  audio_action: 'copy' | 'aac_stereo' | 'aac_5_1' | 'none';
  duration_seconds: number | null;
  subtitle_tracks: SubtitleTrack[];
}

export interface PlaybackUnsupported {
  mode: 'unsupported';
  /** Known reasons are typed; an unknown string renders the generic §9.1 row. */
  reason: UnsupportedReason | (string & {});
  /** English diagnostic: only inside "Detalles técnicos". */
  detail: string;
}

export type PlaybackResponse = PlaybackDirect | PlaybackHls | PlaybackUnsupported;

// --- Admin ---------------------------------------------------------------------------------

export type ScanState = 'idle' | 'running' | 'completed' | 'failed';
export type ScanTrigger = 'startup' | 'scheduled' | 'manual';
export type ScanPhase = 'walking' | 'probing' | 'enriching' | 'done';

export interface ScanCounts {
  files_seen: number;
  new: number;
  changed: number;
  unchanged: number;
  ignored: number;
  unmatched: number;
  missing: number;
  restored: number;
  /** Purged after the grace period. */
  removed: number;
}

export interface ScanStatus {
  id: number | null;
  state: ScanState;
  trigger: ScanTrigger | null;
  phase: ScanPhase | null;
  started_at: Timestamp | null;
  finished_at: Timestamp | null;
  progress: { processed: number | null; total: number | null };
  counts: ScanCounts | null;
  /** Raw server log lines (not translated). */
  errors: string[];
  error: string | null;
  unmatched_count: number;
}

export type UnmatchedReason =
  | 'no_title'
  | 'no_season'
  | 'no_episode_number'
  | 'multi_part'
  | 'probe_failed'
  | 'no_tmdb_results'
  | 'low_confidence';

export type UnmatchedTarget =
  | { type: 'film'; id: number; title: string; year: number | null }
  | { type: 'show'; id: number; title: string; year: number | null }
  | { type: 'media_file'; id: number; relative_path: string };

export type UnmatchedParsed =
  | { kind: 'episode'; show_title: string; show_year: number | null; season: number; episodes: number[] }
  | { kind: 'film'; title: string; year: number | null; part: number | null };

export interface UnmatchedEntry {
  id: number;
  library: LibraryType;
  reason: UnmatchedReason | (string & {});
  /** Raw server diagnostic (not translated). */
  detail: string | null;
  target: UnmatchedTarget;
  parsed: UnmatchedParsed | null;
  created_at: Timestamp;
}

export interface UnmatchedQuery {
  library?: LibraryType;
  reason?: UnmatchedReason;
  page?: number;
  page_size?: number;
}

export type TmdbType = 'movie' | 'tv';

export interface TmdbSearchResult {
  tmdb_id: number;
  title: string;
  original_title: string | null;
  year: number | null;
  overview: string | null;
  poster: Image | null;
}

export interface TmdbSearchResponse {
  results: TmdbSearchResult[];
}

export interface TmdbSeasonSummary {
  season_number: number;
  name: string | null;
  episode_count: number;
  air_date: IsoDate | null;
}

export interface TmdbSeasonsResponse {
  tmdb_id: number;
  name: string;
  year: number | null;
  seasons: TmdbSeasonSummary[];
}

export interface TmdbEpisodeSummary {
  episode_number: number;
  title: string | null;
  air_date: IsoDate | null;
}

export interface TmdbSeasonResponse {
  tmdb_id: number;
  season_number: number;
  name: string | null;
  episodes: TmdbEpisodeSummary[];
}

export interface ResolveRequest {
  tmdb_id: number;
  season_number?: number;
  episode_numbers?: number[];
}

export interface ResolveResponse {
  target: { type: 'film' | 'show'; id: number };
}
