import { describe, expect, it } from 'vitest';
import * as f from '../../../tests/fixtures';
import { scanFinishedToast, scopeLabel } from './scanSummary';

describe('scanFinishedToast', () => {
  it('lists the non-zero counts with "modificados" for changed, and the missing line', () => {
    expect(scanFinishedToast(f.scanStatus({ counts: f.scanCounts({ changed: 0, restored: 3 }) }))).toBe(
      'Escaneo terminado: 240 nuevos, 3 recuperados, 1 eliminado, 4 sin identificar\nAhora faltan 3 archivos: ¿hay algún disco desconectado?',
    );
  });

  it('singulars, and no missing line without missing files', () => {
    const counts = f.scanCounts({ new: 1, changed: 1, restored: 1, removed: 2, unmatched: 1, missing: 0 });
    expect(scanFinishedToast(f.scanStatus({ counts }))).toBe(
      'Escaneo terminado: 1 nuevo, 1 modificado, 1 recuperado, 2 eliminados, 1 sin identificar',
    );
  });

  it('"Sin cambios" when all are 0; one missing file', () => {
    const counts = f.scanCounts({ new: 0, changed: 0, restored: 0, removed: 0, unmatched: 0, missing: 1 });
    expect(scanFinishedToast(f.scanStatus({ counts }))).toBe(
      'Escaneo terminado: Sin cambios\nAhora falta 1 archivo: ¿hay algún disco desconectado?',
    );
    expect(scanFinishedToast(f.scanStatus({ counts: null }))).toBe('Escaneo terminado: Sin cambios');
  });

  it('a failed run', () => {
    expect(scanFinishedToast(f.scanStatus({ state: 'failed' }))).toBe('El escaneo ha fallado.');
  });
});

describe('scopeLabel', () => {
  it.each([
    ['a top-level folder', f.scanScope(), 'películas/A'],
    ['a show folder', f.scanScope({ root: '/media/series', path: '/media/series/Breaking Bad' }), 'series/Breaking Bad'],
    ['a nested relative path', f.scanScope({ root: '/media/series', path: '/media/series/Lost/Temporada 1' }), 'series/Lost/Temporada 1'],
    ['loose files of a root', f.scanScope({ root: '/media/MKV', path: '/media/MKV', recursive: false }), 'MKV (archivos sueltos)'],
    ['a whole root', f.scanScope({ root: '/media/series', path: '/media/series', recursive: true }), 'series (toda la carpeta)'],
    ['a root with a trailing slash', f.scanScope({ root: '/media/series/', path: '/media/series/Lost' }), 'series/Lost'],
  ])('%s', (_name, scope, label) => {
    expect(scopeLabel(scope)).toBe(label);
  });
});
