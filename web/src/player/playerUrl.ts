// Player URLs. `file` names the version; `t` is a one-shot
// start instruction (t=0 means from the start). Cards and episode rows send neither, so the
// player runs selectVersion on fresh progress.
export type PlayerItemType = 'film' | 'episode';

export function playerUrl(type: PlayerItemType, id: number, { file, t }: { file?: number | null; t?: number | null } = {}): string {
  const params = new URLSearchParams();
  if (file != null) params.set('file', String(file));
  if (t != null) params.set('t', String(t));
  const search = params.toString();
  return `/watch/${type}/${id}${search ? `?${search}` : ''}`;
}
