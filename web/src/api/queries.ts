// Query option builders shared by screens (key + fetcher + staleTime in one place).
import { queryOptions } from '@tanstack/react-query';
import { getEpisode, getFilm, getGenres, getHome, getSeason, getShow, search } from './catalog';
import { getPerson } from './people';
import { getMe, listProfiles } from './profiles';
import { queryKeys, staleTimes } from './queryKeys';
import { getInfo } from './system';
import type { LibraryType } from './types';

export const infoQuery = () =>
  queryOptions({ queryKey: queryKeys.info(), queryFn: ({ signal }) => getInfo(signal), staleTime: staleTimes.info });

export const profilesQuery = () =>
  queryOptions({
    queryKey: queryKeys.profiles(),
    queryFn: ({ signal }) => listProfiles(signal),
    staleTime: staleTimes.profiles,
  });

export const meQuery = () =>
  queryOptions({ queryKey: queryKeys.me(), queryFn: ({ signal }) => getMe(signal), staleTime: staleTimes.me });

export const homeQuery = () =>
  queryOptions({ queryKey: queryKeys.home(), queryFn: ({ signal }) => getHome(signal), staleTime: staleTimes.home });

export const genresQuery = (type: LibraryType) =>
  queryOptions({
    queryKey: queryKeys.genres(type),
    queryFn: ({ signal }) => getGenres(type, signal),
    staleTime: staleTimes.genres,
  });

export const searchQuery = (q: string) =>
  queryOptions({ queryKey: queryKeys.search(q), queryFn: ({ signal }) => search(q, signal), staleTime: staleTimes.search });

export const filmQuery = (id: number) =>
  queryOptions({ queryKey: queryKeys.film(id), queryFn: ({ signal }) => getFilm(id, signal), staleTime: staleTimes.film });

export const showQuery = (id: number) =>
  queryOptions({ queryKey: queryKeys.show(id), queryFn: ({ signal }) => getShow(id, signal), staleTime: staleTimes.show });

export const seasonQuery = (showId: number, seasonNumber: number) =>
  queryOptions({
    queryKey: queryKeys.season(showId, seasonNumber),
    queryFn: ({ signal }) => getSeason(showId, seasonNumber, signal),
    staleTime: staleTimes.season,
  });

export const episodeQuery = (id: number) =>
  queryOptions({
    queryKey: queryKeys.episode(id),
    queryFn: ({ signal }) => getEpisode(id, signal),
    staleTime: staleTimes.episode,
  });

// networkMode 'always' (the only query that overrides the app-wide 'online', see createQueryClient):
// offline, the fetch fails and the page shows the connection error with "Reintentar" (prd2 States)
// instead of a query paused on its skeleton forever. A failed refetch of a cached person keeps it
// on screen (PersonPage shows the error only without data).
export const personQuery = (id: number) =>
  queryOptions({
    queryKey: queryKeys.person(id),
    queryFn: ({ signal }) => getPerson(id, signal),
    staleTime: staleTimes.person,
    networkMode: 'always',
  });
