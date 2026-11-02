// Season + episodes of a shows-library file entry in the fix-match dialog:
// dropdowns filled from the TMDB season lookups and prefilled from `parsed`; when a lookup fails,
// the error plus a plain season number input and an episodes text input (`3` or `16-17`).
import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import { errorMessage } from '@/api/errors';
import type { TmdbEpisodeSummary, TmdbSeasonSummary, UnmatchedParsed } from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { Spinner } from '@/components/Spinner';
import { formatDate, parseEpisodeList, seasonLabel } from '@/lib/format';
import { S } from '@/strings.es';
import { tmdbSeasonQuery, tmdbSeasonsQuery } from './adminQueries';
import styles from './FixMatchDialog.module.css';

export type EpisodeChoice =
  | { status: 'ready'; seasonNumber: number; episodeNumbers: number[] }
  /** Nothing chosen yet (dropdowns): Aplicar stays disabled. */
  | { status: 'incomplete' }
  /** Invalid manual input: shown when Aplicar is pressed. */
  | { status: 'invalid'; message: string };

export interface EpisodePickerProps {
  tmdbId: number;
  parsed: UnmatchedParsed | null;
  onChange: (choice: EpisodeChoice) => void;
  /** Show the manual-input validation message (after an Aplicar attempt). */
  showErrors: boolean;
}

const parsedEpisode = (parsed: UnmatchedParsed | null) => (parsed?.kind === 'episode' ? parsed : null);

/** The parsed season when TMDB lists it, else the first season. */
export function defaultSeason(seasons: TmdbSeasonSummary[], parsed: UnmatchedParsed | null): number | null {
  const wanted = parsedEpisode(parsed)?.season;
  if (wanted !== undefined && seasons.some((s) => s.season_number === wanted)) return wanted;
  return seasons[0]?.season_number ?? null;
}

/** The parsed episodes that exist in the selected season (only for the parsed season). */
export function defaultEpisodes(episodes: TmdbEpisodeSummary[], parsed: UnmatchedParsed | null, season: number): number[] {
  const p = parsedEpisode(parsed);
  if (!p || p.season !== season) return [];
  return p.episodes.filter((n) => episodes.some((e) => e.episode_number === n));
}

/** [16, 17] → "16-17", [3] → "3", [3, 5] → "3, 5" (the manual input's prefill). */
export function formatEpisodeList(numbers: number[]): string {
  const sorted = [...new Set(numbers)].sort((a, b) => a - b);
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (first === undefined || last === undefined) return '';
  if (sorted.length > 1 && last - first === sorted.length - 1) return `${first}-${last}`;
  return sorted.join(', ');
}

function episodeOptionLabel(e: TmdbEpisodeSummary): string {
  const parts = [`E${e.episode_number}`];
  if (e.title) parts.push(e.title);
  if (e.air_date) parts.push(formatDate(e.air_date));
  return S.fixMatch.episodeOption(parts);
}

function manualChoice(seasonText: string, episodesText: string): EpisodeChoice {
  const season = Number(seasonText);
  if (seasonText.trim() === '' || !Number.isInteger(season) || season < 0) {
    return { status: 'invalid', message: S.fixMatch.invalidSeason };
  }
  try {
    return { status: 'ready', seasonNumber: season, episodeNumbers: parseEpisodeList(episodesText) };
  } catch {
    return { status: 'invalid', message: S.fixMatch.invalidEpisodes };
  }
}

export function EpisodePicker({ tmdbId, parsed, onChange, showErrors }: EpisodePickerProps) {
  const runAdmin = useAdminAction();
  const id = useId();
  const seasons = useQuery(tmdbSeasonsQuery(runAdmin, tmdbId));
  const [seasonChoice, setSeasonChoice] = useState<number | null>(null);
  const [episodeChoice, setEpisodeChoice] = useState<number[] | null>(null);
  const seasonList = seasons.data?.seasons;
  const season = seasonChoice ?? (seasonList ? defaultSeason(seasonList, parsed) : null);
  const episodesQuery = useQuery({ ...tmdbSeasonQuery(runAdmin, tmdbId, season ?? 0), enabled: season !== null });
  const episodeList = episodesQuery.data?.episodes;
  const episodes = episodeChoice ?? (episodeList && season !== null ? defaultEpisodes(episodeList, parsed, season) : []);

  const lookupError = seasons.error ?? (season !== null ? episodesQuery.error : null);
  const fallback = lookupError !== null;
  const p = parsedEpisode(parsed);
  const [manualSeason, setManualSeason] = useState(p ? String(p.season) : '');
  const [manualEpisodes, setManualEpisodes] = useState(p ? formatEpisodeList(p.episodes) : '');

  const choice: EpisodeChoice = fallback
    ? manualChoice(manualSeason, manualEpisodes)
    : season !== null && episodes.length > 0
      ? { status: 'ready', seasonNumber: season, episodeNumbers: episodes }
      : { status: 'incomplete' };
  const choiceKey = JSON.stringify(choice);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  useEffect(() => {
    onChangeRef.current(JSON.parse(choiceKey) as EpisodeChoice);
  }, [choiceKey]);

  if (fallback) {
    return (
      <div className={styles.picker}>
        {!isAdminActionCancelled(lookupError) && (
          <p className={styles.error} role="alert">
            {errorMessage(lookupError)}
          </p>
        )}
        <div className={styles.field}>
          <label htmlFor={`${id}-season`}>{S.fixMatch.season}</label>
          <input
            id={`${id}-season`}
            className={styles.input}
            type="number"
            min={0}
            inputMode="numeric"
            value={manualSeason}
            onChange={(e) => setManualSeason(e.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label htmlFor={`${id}-episodes`}>{S.fixMatch.episodes}</label>
          <input
            id={`${id}-episodes`}
            className={styles.input}
            value={manualEpisodes}
            placeholder={S.fixMatch.episodesHint}
            onChange={(e) => setManualEpisodes(e.target.value)}
          />
        </div>
        {showErrors && choice.status === 'invalid' && (
          <p className={styles.error} role="alert">
            {choice.message}
          </p>
        )}
      </div>
    );
  }

  if (!seasonList) return <Spinner label={S.fixMatch.loadingSeasons} />;

  return (
    <div className={styles.picker}>
      <div className={styles.field}>
        <label htmlFor={`${id}-season`}>{S.fixMatch.season}</label>
        <select
          id={`${id}-season`}
          className={styles.input}
          value={season ?? ''}
          onChange={(e) => {
            setSeasonChoice(Number(e.target.value));
            setEpisodeChoice(null);
          }}
        >
          {seasonList.map((s) => (
            <option key={s.season_number} value={s.season_number}>
              {S.fixMatch.seasonOption(seasonLabel(s.season_number, s.name), S.common.episodes(s.episode_count))}
            </option>
          ))}
        </select>
      </div>
      <div className={styles.field}>
        <label htmlFor={`${id}-episodes`}>{S.fixMatch.episodes}</label>
        {episodeList ? (
          <select
            id={`${id}-episodes`}
            className={styles.input}
            multiple
            size={Math.min(8, Math.max(3, episodeList.length))}
            value={episodes.map(String)}
            onChange={(e) => setEpisodeChoice([...e.target.selectedOptions].map((o) => Number(o.value)))}
          >
            {episodeList.map((e) => (
              <option key={e.episode_number} value={e.episode_number}>
                {episodeOptionLabel(e)}
              </option>
            ))}
          </select>
        ) : (
          <Spinner />
        )}
      </div>
    </div>
  );
}

