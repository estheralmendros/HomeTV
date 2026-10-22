/** Joins truthy class names (CSS Module lookups may be undefined). */
export function cx(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ');
}
