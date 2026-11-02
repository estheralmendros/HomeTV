// Fix-match dialog: TMDB search through the server proxy, one result chosen, then
// "Aplicar" sends the request of the mode (film/show match, or resolve for a file entry; shows
// files add season + episodes). Lazy admin chunk: FixMatchButton (detail pages) loads it on
// demand, UnmatchedPage renders it directly. Errors stay inside the dialog.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { matchFilm, matchShow, resolveUnmatched } from '@/api/admin';
import { errorMessage, isApiError } from '@/api/errors';
import { infoQuery } from '@/api/queries';
import { runInvalidation } from '@/api/queryClient';
import { queryKeys } from '@/api/queryKeys';
import type { TmdbSearchResult, TmdbType } from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { Artwork } from '@/components/Artwork';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';
import { useToast } from '@/components/Toast';
import { S } from '@/strings.es';
import { tmdbSearchQuery, type TmdbSearchParams } from './adminQueries';
import { fixMatchRequest, searchPrefill, type FixMatchRequest, type FixMatchSelection } from './entryToDialogMode';
import { EpisodePicker, type EpisodeChoice } from './EpisodePicker';
import styles from './FixMatchDialog.module.css';
import { fixMatchName, type FixMatchDialogProps, type MatchedTarget } from './fixMatchTypes';

const YEAR = /^\d{4}$/;

interface Applied {
  target: MatchedTarget;
  /** "Dune (2021)" for the toast. */
  name: string;
}

async function send(request: FixMatchRequest, chosen: TmdbSearchResult): Promise<Applied> {
  switch (request.kind) {
    case 'matchFilm': {
      const film = await matchFilm(request.id, request.body.tmdb_id);
      return { target: { type: 'film', id: film.id }, name: S.common.titleWithYear(film.title, film.year) };
    }
    case 'matchShow': {
      const show = await matchShow(request.id, request.body.tmdb_id);
      return { target: { type: 'show', id: show.id }, name: S.common.titleWithYear(show.title, show.year) };
    }
    case 'resolve': {
      const { target } = await resolveUnmatched(request.entryId, request.body);
      return { target, name: S.common.titleWithYear(chosen.title, chosen.year) };
    }
  }
}

export function FixMatchDialog({ mode, onClose, onMatched }: FixMatchDialogProps) {
  const id = useId();
  const runAdmin = useAdminAction();
  const queryClient = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const { data: info } = useQuery(infoQuery());
  const tmdbDisabled = info?.tmdb_configured === false;

  // The mode object may be rebuilt by the parent on every render: prefill once.
  const [prefill] = useState(() => searchPrefill(mode));
  const [type, setType] = useState<TmdbType>(prefill.type);
  const [q, setQ] = useState(prefill.q);
  const [year, setYear] = useState(prefill.year);
  const [search, setSearch] = useState<TmdbSearchParams | null>(null);
  const [selected, setSelected] = useState<TmdbSearchResult | null>(null);
  const [episodeChoice, setEpisodeChoice] = useState<EpisodeChoice>({ status: 'incomplete' });
  const [showChoiceErrors, setShowChoiceErrors] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [blocked, setBlocked] = useState(false);
  const [applying, setApplying] = useState(false);

  const results = useQuery({
    ...tmdbSearchQuery(runAdmin, search ?? { type, q: '', year: null }),
    enabled: search !== null,
  });
  const needsEpisodes = mode.kind === 'file' && mode.library === 'shows';
  const parsed = mode.kind === 'file' ? mode.parsed : null;

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    const query = q.trim();
    if (!query) return;
    const next: TmdbSearchParams = { type, q: query, year: YEAR.test(year.trim()) ? Number(year.trim()) : null };
    setSelected(null);
    setApplyError(null);
    if (search && JSON.stringify(search) === JSON.stringify(next)) void results.refetch();
    else setSearch(next);
  };

  const refetchUnmatched = () => void queryClient.invalidateQueries({ queryKey: queryKeys.admin.unmatchedAll() });

  const onApply = async () => {
    if (!selected) return;
    const selection: FixMatchSelection = { tmdbId: selected.tmdb_id };
    if (needsEpisodes) {
      if (episodeChoice.status !== 'ready') {
        setShowChoiceErrors(true);
        return;
      }
      selection.seasonNumber = episodeChoice.seasonNumber;
      selection.episodeNumbers = episodeChoice.episodeNumbers;
    }
    const request = fixMatchRequest(mode, selection);
    setApplying(true);
    setApplyError(null);
    try {
      const applied = await runAdmin(() => send(request, selected), S.fixMatch.action);
      void runInvalidation(queryClient, { type: 'catalogChanged' });
      const { target } = applied;
      if (request.kind === 'resolve') {
        const message = mode.kind === 'file' && mode.reason === 'multi_part' ? S.fixMatch.matchedParts(applied.name) : S.fixMatch.matched(applied.name);
        const path = target.type === 'film' ? `/films/${target.id}` : `/shows/${target.id}`;
        toast.show(message, { action: { label: S.fixMatch.open, onClick: () => navigate(path) }, durationMs: 8000 });
      } else {
        toast.show(S.fixMatch.matched(applied.name));
      }
      onMatched?.(target);
      onClose();
    } catch (err) {
      setApplying(false);
      if (isAdminActionCancelled(err)) return;
      if (request.kind === 'resolve' && isApiError(err, 'not_found')) {
        // Already resolved (e.g. as a sibling part of another entry).
        refetchUnmatched();
        toast.show(S.fixMatch.alreadyResolved);
        onClose();
        return;
      }
      if (isApiError(err, 'not_resolvable')) setBlocked(true);
      if (isApiError(err, 'not_resolvable') || isApiError(err, 'not_a_file_entry')) refetchUnmatched();
      setApplyError(errorMessage(err));
    }
  };

  const items = results.data?.results;
  const applyDisabled =
    !selected || applying || blocked || (needsEpisodes && episodeChoice.status === 'incomplete');

  return (
    <Modal title={S.fixMatch.title(fixMatchName(mode))} onClose={onClose} className={styles.dialog}>
      {tmdbDisabled && (
        <p className={styles.error} role="alert">
          {S.fixMatch.notConfigured}
        </p>
      )}
      <form className={styles.search} onSubmit={onSearch}>
        <fieldset className={styles.searchFields} disabled={tmdbDisabled}>
          <div className={styles.field}>
            <label htmlFor={`${id}-type`}>{S.fixMatch.type}</label>
            <select id={`${id}-type`} className={styles.input} value={type} onChange={(e) => setType(e.target.value as TmdbType)}>
              <option value="movie">{S.fixMatch.types.movie}</option>
              <option value="tv">{S.fixMatch.types.tv}</option>
            </select>
          </div>
          <div className={`${styles.field} ${styles.grow}`}>
            <label htmlFor={`${id}-q`}>{S.fixMatch.query}</label>
            <input id={`${id}-q`} className={styles.input} value={q} maxLength={100} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className={styles.field}>
            <label htmlFor={`${id}-year`}>{S.fixMatch.year}</label>
            <input
              id={`${id}-year`}
              className={`${styles.input} ${styles.year}`}
              inputMode="numeric"
              maxLength={4}
              value={year}
              onChange={(e) => setYear(e.target.value.replace(/\D/g, '').slice(0, 4))}
            />
          </div>
          <button type="submit" className="button" disabled={tmdbDisabled || q.trim() === ''}>
            {S.fixMatch.search}
          </button>
        </fieldset>
      </form>

      {search !== null &&
        (results.isFetching && !items ? (
          <Spinner />
        ) : results.isError ? (
          !isAdminActionCancelled(results.error) && (
            <p className={styles.error} role="alert">
              {errorMessage(results.error)}
            </p>
          )
        ) : items && items.length === 0 ? (
          <p className={styles.muted}>{S.fixMatch.noResults}</p>
        ) : items ? (
          <fieldset className={styles.results}>
            <legend className="visually-hidden">{S.fixMatch.results}</legend>
            {items.map((item) => (
              <label key={item.tmdb_id} className={styles.result}>
                <input
                  type="radio"
                  name={`${id}-result`}
                  checked={selected?.tmdb_id === item.tmdb_id}
                  aria-label={S.common.titleWithYear(item.title, item.year)}
                  onChange={() => {
                    setSelected(item);
                    setEpisodeChoice({ status: 'incomplete' });
                    setShowChoiceErrors(false);
                    setApplyError(null);
                  }}
                />
                <Artwork image={item.poster} title={item.title} kind="poster" className={styles.poster} />
                <span className={styles.resultText}>
                  <span className={styles.resultTitle}>{S.common.titleWithYear(item.title, item.year)}</span>
                  {item.original_title && item.original_title !== item.title && (
                    <span className={styles.muted}>{item.original_title}</span>
                  )}
                  {item.overview && <span className={styles.overview}>{item.overview}</span>}
                </span>
              </label>
            ))}
          </fieldset>
        ) : null)}

      {needsEpisodes && selected && (
        <EpisodePicker
          key={selected.tmdb_id}
          tmdbId={selected.tmdb_id}
          parsed={parsed}
          onChange={setEpisodeChoice}
          showErrors={showChoiceErrors}
        />
      )}

      {applyError && (
        <p className={styles.error} role="alert">
          {applyError}
        </p>
      )}

      <div className={styles.actions}>
        <button type="button" className="button" onClick={onClose}>
          {S.common.cancel}
        </button>
        <button type="button" className="button button-primary" disabled={applyDisabled} onClick={() => void onApply()}>
          {S.common.apply}
        </button>
      </div>
    </Modal>
  );
}
