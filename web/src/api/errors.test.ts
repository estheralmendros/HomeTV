import { describe, expect, it, vi } from 'vitest';
import { ApiError, errorMessage, isApiError, logApiError, validationFields } from './errors';

describe('ApiError', () => {
  it('keeps status, code, details and retryAfter; message is the envelope text', () => {
    const err = new ApiError({ status: 404, code: 'not_found', message: 'Film 42 not found', details: { id: 42 } });
    expect(err).toBeInstanceOf(Error);
    expect(err.name).toBe('ApiError');
    expect(err).toMatchObject({ status: 404, code: 'not_found', details: { id: 42 }, retryAfter: null });
    expect(err.message).toBe('Film 42 not found');
  });

  it('isApiError narrows by code', () => {
    const err = new ApiError({ status: 401, code: 'admin_locked' });
    expect(isApiError(err)).toBe(true);
    expect(isApiError(err, 'admin_locked')).toBe(true);
    expect(isApiError(err, 'unauthorized')).toBe(false);
    expect(isApiError(new Error('x'))).toBe(false);
  });
});

describe('errorMessage', () => {
  it.each([
    ['not_found', 'Este contenido no está disponible. Puede que se haya eliminado o que su disco esté desconectado.'],
    ['forbidden', 'Solo para administradores.'],
    ['too_many_sessions', 'El servidor ya está convirtiendo el número máximo de vídeos. Detén la reproducción en otro dispositivo y vuelve a intentarlo.'],
    ['invalid_pin', 'PIN incorrecto.'],
    ['admin_locked', 'Introduce tu PIN de administrador para continuar.'],
    ['scan_in_progress', 'Ya hay un escaneo en curso.'],
    ['name_taken', 'Ya existe un perfil con ese nombre.'],
    ['profile_limit_reached', 'Se ha alcanzado el máximo de 12 perfiles.'],
    ['file_missing', 'Falta el archivo de vídeo en el disco (¿está conectado el disco?).'],
    ['preparing', 'Preparando el vídeo…'],
    ['session_not_found', 'La sesión de reproducción ha terminado.'],
    ['validation_error', 'Revisa los datos introducidos.'],
  ])('%s → catalog text', (code, text) => {
    expect(errorMessage(new ApiError({ status: 400, code, message: 'English diagnostic' }))).toBe(text);
  });

  it('uses the generic text for unknown codes and non-API errors', () => {
    expect(errorMessage(new ApiError({ status: 418, code: 'http_418' }))).toBe('Se ha producido un error inesperado (http_418).');
    expect(errorMessage(new TypeError('boom'))).toBe('Se ha producido un error inesperado (unknown).');
  });

  it('formats too_many_attempts with retryAfter', () => {
    expect(errorMessage(new ApiError({ status: 429, code: 'too_many_attempts', retryAfter: 30 }))).toBe(
      'Demasiados PIN incorrectos. Vuelve a intentarlo en 30 s.',
    );
  });
});

describe('validationFields', () => {
  it('returns the field names from details.errors[].loc, never the msg', () => {
    const err = new ApiError({
      status: 422,
      code: 'validation_error',
      details: { errors: [{ loc: ['body', 'name'], msg: 'too long' }, { loc: ['body', 'pin'], msg: 'bad' }, { loc: 'x' }] },
    });
    expect(validationFields(err)).toEqual(['name', 'pin']);
    expect(validationFields(new ApiError({ status: 422, code: 'validation_error' }))).toEqual([]);
  });
});

describe('logApiError', () => {
  it('sends the English envelope message to console.warn only', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    logApiError(new ApiError({ status: 500, code: 'transcoder_failed', message: 'ffmpeg died', details: { detail: 'x' } }));
    expect(warn).toHaveBeenCalledWith('[api] transcoder_failed (500): ffmpeg died', { detail: 'x' });
  });
});
