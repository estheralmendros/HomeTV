import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { ApiError, errorMessage } from '@/api/errors';
import { S } from '@/strings.es';

const SRC = join(__dirname);

/** Text that may appear literally in JSX: anything without letters (symbols such as ✓ ‹ › ⌫ ·, digits). */
const ALLOWED_TEXT = /^[^\p{L}]*$/u;
const TECHNICAL_TOKENS = new Set(['PIN', 'TMDB', 'SDH', 'HomeTV', 'OK']);
const CHECKED_PROPS = new Set(['aria-label', 'alt', 'title', 'placeholder']);

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return tsxFiles(path);
    return path.endsWith('.tsx') && !path.endsWith('.test.tsx') ? [path] : [];
  });
}

function isAllowed(text: string): boolean {
  const trimmed = text.trim();
  return trimmed === '' || ALLOWED_TEXT.test(trimmed) || TECHNICAL_TOKENS.has(trimmed);
}

/** Literal UI text in a .tsx source: JSX text, `{'literal'}` children and checked string props. */
export function findLiteralUiText(source: string, fileName = 'x.tsx'): string[] {
  const file = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const found: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isJsxText(node) && !isAllowed(node.text)) {
      found.push(node.text.trim());
    } else if (
      ts.isJsxExpression(node) &&
      node.expression &&
      (ts.isStringLiteral(node.expression) || ts.isNoSubstitutionTemplateLiteral(node.expression)) &&
      (ts.isJsxElement(node.parent) || ts.isJsxFragment(node.parent)) &&
      !isAllowed(node.expression.text)
    ) {
      found.push(node.expression.text);
    } else if (ts.isJsxAttribute(node) && CHECKED_PROPS.has(node.name.getText(file))) {
      const init = node.initializer;
      const literal =
        init && ts.isStringLiteral(init)
          ? init.text
          : init && ts.isJsxExpression(init) && init.expression && ts.isStringLiteral(init.expression)
            ? init.expression.text
            : null;
      if (literal !== null && !isAllowed(literal)) found.push(`${node.name.getText(file)}="${literal}"`);
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return found;
}

function uiModules(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return uiModules(path);
    return /\.tsx?$/.test(path) && !/\.test\.tsx?$/.test(path) && !path.endsWith('strings.es.ts') ? [path] : [];
  });
}

/** Every leaf of the catalog (a string or a function), as a dotted path. */
function catalogLeaves(node: unknown, prefix = ''): string[] {
  if (typeof node !== 'object' || node === null) return [prefix];
  return Object.entries(node).flatMap(([key, value]) => catalogLeaves(value, prefix ? `${prefix}.${key}` : key));
}

/**
 * Catalog paths a source reads: `S.a.b.c` chains (also `['c']`) and chains on local aliases
 * (`const T = S.admin.scan; … T.title`). A dynamic index (`S.errors.byCode[code]`) or a whole
 * object passed on yields its parent path; those parents must be in `DYNAMIC_PARENTS`.
 */
export function catalogPathsRead(source: string, fileName = 'x.tsx'): string[] {
  const file = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const aliases = new Map<string, string>([['S', '']]);
  const found: string[] = [];
  const pathOf = (node: ts.Expression): string | null => {
    if (ts.isIdentifier(node)) return aliases.get(node.text) ?? null;
    if (ts.isPropertyAccessExpression(node)) {
      const base = pathOf(node.expression);
      return base === null ? null : base ? `${base}.${node.name.text}` : node.name.text;
    }
    if (ts.isElementAccessExpression(node) && ts.isStringLiteral(node.argumentExpression)) {
      const base = pathOf(node.expression);
      return base === null ? null : base ? `${base}.${node.argumentExpression.text}` : node.argumentExpression.text;
    }
    return null;
  };
  const isChainLink = (node: ts.Node): boolean =>
    (ts.isPropertyAccessExpression(node.parent) || ts.isElementAccessExpression(node.parent)) &&
    node.parent.expression === node;
  const visit = (node: ts.Node): void => {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      const path = pathOf(node.initializer);
      if (path) {
        aliases.set(node.name.text, path);
        return;
      }
    }
    if ((ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) && !isChainLink(node)) {
      const path = pathOf(node);
      if (path) found.push(path);
      else if (ts.isElementAccessExpression(node)) {
        const base = pathOf(node.expression);
        if (base) found.push(base);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return found;
}

/** Catalog objects read with a computed key (`S.errors.byCode[code]`): every leaf below counts. */
const DYNAMIC_PARENTS = [
  'errors.byCode',
  'show.status',
  'library.noMatch',
  'watchlist.noMatch',
  'player.gone',
  'player.unsupported',
  'player.subtitleStyle.sizes',
  'player.subtitleStyle.colors',
  'player.subtitleStyle.edges',
  'player.subtitleStyle.edgeColors',
  'player.subtitleStyle.positions',
  'admin.scan.states',
  'admin.scan.triggers',
  'admin.scan.watcher.states',
  'admin.scan.phases',
  'admin.scan.counts',
  'admin.scan.countHints',
  'admin.unmatched.libraries',
  'admin.unmatched.reasons',
  'admin.libraries.sections',
  'admin.libraries.status',
  'admin.libraries.errors',
];

describe('UI string catalog', () => {
  it('detects literal JSX text and literal accessible props', () => {
    const src = `export const A = () => (<div title="Hola" aria-label={'Menú'}>
      Texto suelto {'otro'} {S.x} ✓ · <img alt="" /> <input placeholder="Buscar" /> HomeTV </div>);`;
    expect(findLiteralUiText(src)).toEqual(['title="Hola"', 'aria-label="Menú"', 'Texto suelto', 'otro', 'placeholder="Buscar"']);
  });

  it('has no literal UI text in src/**/*.tsx', () => {
    const offenders = tsxFiles(SRC).flatMap((path) =>
      findLiteralUiText(readFileSync(path, 'utf8'), path).map((text) => `${relative(SRC, path)}: ${text}`),
    );
    expect(offenders).toEqual([]);
  });

  it('finds catalog reads through S chains and local aliases', () => {
    const src = `const T = S.admin.scan; const E = T.errors;
      f(S.common.cancel, S.show.status[x], S['nav']['films'], T.title, E.pin(1), other.title);`;
    expect(catalogPathsRead(src)).toEqual(['common.cancel', 'show.status', 'nav.films', 'admin.scan.title', 'admin.scan.errors.pin']);
  });

  it('every S leaf is read somewhere in src/**/*.ts(x)', () => {
    const read = new Set(uiModules(SRC).flatMap((path) => catalogPathsRead(readFileSync(path, 'utf8'), path)));
    const unused = catalogLeaves(S).filter(
      (leaf) => !read.has(leaf) && !DYNAMIC_PARENTS.some((parent) => leaf.startsWith(`${parent}.`)),
    );
    expect(unused).toEqual([]);
  });

  it('has an S.errors entry for every error code the API returns', () => {
    const codes = [
      'network_error', 'not_found', 'forbidden', 'too_many_sessions', 'too_many_attempts', 'invalid_pin',
      'admin_locked', 'pin_required', 'pin_not_allowed', 'scan_in_progress', 'tmdb_unavailable',
      'tmdb_not_configured', 'tmdb_not_found', 'name_taken', 'profile_limit_reached', 'last_admin',
      'file_missing', 'invalid_audio_stream', 'preparing', 'hls_disk_full', 'transcoder_failed',
      'segment_timeout', 'session_not_found', 'subtitle_pending', 'subtitle_extraction_failed',
      'subtitle_timeout', 'not_resolvable', 'not_a_file_entry', 'media_file_not_linked',
      'profile_not_found', 'artwork_not_found', 'validation_error',
      // folder-selection-admin
      'invalid_library_folders', 'relinks_unconfirmed', 'outside_browse_root', 'folder_not_found',
      'folder_not_readable', 'browse_unavailable',
    ];
    for (const code of codes) {
      expect(S.errors.byCode, code).toHaveProperty(code);
    }
  });

  it('uses the exact Spanish texts', () => {
    expect(S.home.watchlist).toBe('Mi lista');
    expect(S.home.films).toBe('Películas');
    expect(S.home.shows).toBe('Series');
    expect(S.home.seeAll).toBe('Ver todo');
    expect(S.home.seeAllOf('Películas')).toBe('Ver todo: Películas');
    expect(errorMessage(new ApiError({ status: 0, code: 'network_error' }))).toBe(
      'No se puede conectar con el servidor.',
    );
    expect(errorMessage(new ApiError({ status: 409, code: 'last_admin' }))).toBe(
      'Tiene que haber al menos un perfil de administrador.',
    );
    expect(errorMessage(new ApiError({ status: 429, code: 'too_many_attempts', retryAfter: 42 }))).toBe(
      'Demasiados PIN incorrectos. Vuelve a intentarlo en 42 s.',
    );
  });

  it('never shows the envelope message for an unknown code', () => {
    const err = new ApiError({ status: 500, code: 'weird_thing', message: 'Internal English text' });
    expect(errorMessage(err)).toBe('Se ha producido un error inesperado (weird_thing).');
  });

  it('index.html declares <html lang="es"> and the default title', () => {
    const html = readFileSync(join(SRC, '..', 'index.html'), 'utf8');
    expect(html).toMatch(/<html lang="es">/);
    expect(html).toMatch(/<title>HomeTV<\/title>/);
  });

  it('has the ratings glossary texts', () => {
    expect(S.titles.activity).toBe('Actividad');
    expect(S.nav.activity).toBe('Actividad');
    expect(S.nav.search).toBe('Buscar');
    const R = S.ratings;
    expect([R.rate, R.change, R.mine, R.averageHeading, R.question, R.notNow, R.remove, R.saved]).toEqual([
      'Puntuar', 'Cambiar', 'Tu puntuación', 'Media de la familia', '¿Qué te ha parecido?', 'Ahora no', 'Quitar puntuación',
      'Puntuación guardada',
    ]);
    expect(R.saveError).toBe('No se ha podido guardar la puntuación.');
    expect(R.updateError).toBe('No se ha podido actualizar tu puntuación.');
    expect([R.none, R.inputLabel, R.less]).toEqual(['Sin puntuación', 'Puntuación', 'Ver menos']);
    expect(R.average(4, 3)).toBe('4,0 (3)');
    expect(R.average(3.5, 12)).toBe('3,5 (12)');
    expect(R.averageLabel(4, 3)).toBe('Media de la familia: 4,0 estrellas, 3 puntuaciones');
    expect([R.stars(4.5), R.stars(4), R.stars(1), R.stars(0.5)]).toEqual(['4,5 estrellas', '4 estrellas', '1 estrella', '0,5 estrellas']);
    expect(R.more(2)).toBe('y 2 más');
    const A = S.activity;
    expect(A.heading).toBe('Actividad');
    expect(A.row('Ana', 'Dune', R.stars(4.5), 'ayer')).toBe('Ana ha puntuado Dune con 4,5 estrellas, ayer');
    expect(A.ownRow('Dune', R.stars(4.5), 'ayer')).toBe('Has puntuado Dune con 4,5 estrellas, ayer');
    expect([A.ratedPrefix, A.ownPrefix, A.with]).toEqual(['ha puntuado', 'Has puntuado', 'con']);
    expect(A.empty).toBe('Todavía nadie ha puntuado nada. Cuando alguien puntúe una película o serie, aparecerá aquí.');
    expect([A.time.now, A.time.minutes(1), A.time.minutes(5), A.time.hours(1), A.time.hours(3), A.time.yesterday]).toEqual([
      'ahora mismo', 'hace 1 minuto', 'hace 5 minutos', 'hace 1 hora', 'hace 3 horas', 'ayer',
    ]);
    expect(S.cards.withRating('Dune (2021)', R.stars(4.5))).toBe('Dune (2021), tu puntuación: 4,5 estrellas');
  });

  it('builds titles and plurals in es-ES', () => {
    expect(S.titles.page('Películas')).toBe('Películas · HomeTV');
    expect(S.common.episodes(1)).toBe('1 episodio');
    expect(S.common.episodes(1234)).toBe('1.234 episodios');
    expect(S.show.remaining(9)).toBe('Quedan 9 min');
    expect(S.show.remaining(1)).toBe('Queda 1 min');
  });
});
