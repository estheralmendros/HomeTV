// Optional smoke test (prd2 Testing Decisions), Chromium only, not part of `npm test`.
// Runs against a real server seeded with a library (prd.md's integration clips, or the demo
// library of specs/HOMETV-1/hometv-app/create-environment.sh):
//   HOMETV_E2E_URL=http://127.0.0.1:8000 npm run e2e
// Optional: HOMETV_E2E_FILM (title of an H.264/AAC MP4 film, default "h264_aac", the integration
// clip; the demo library has "Dune"), HOMETV_E2E_PROFILE (profile to pick when no bootstrap is
// needed; default: the first one), HOMETV_E2E_PIN (admin PIN used when the server needs a
// bootstrap, default 1234).
import { expect, test, type Page } from '@playwright/test';

const FILM = process.env.HOMETV_E2E_FILM ?? 'h264_aac';
const PROFILE = process.env.HOMETV_E2E_PROFILE;
const PIN = process.env.HOMETV_E2E_PIN ?? '1234';

async function bootstrapOrPickProfile(page: Page) {
  await page.goto('/');
  const setup = page.getByRole('button', { name: 'Crear perfil' });
  const picker = page.getByRole('heading', { name: '¿Quién está viendo?' });
  await expect(setup.or(picker)).toBeVisible();
  if (await setup.isVisible()) {
    await page.getByLabel('Nombre').fill('E2E');
    // The radio is visually hidden; click its visible option (the label).
    await page.locator('label', { has: page.getByRole('radio', { name: 'Avatar 1' }) }).click();
    await page.getByLabel('PIN de administrador').fill(PIN);
    await page.getByLabel('Confirmar PIN').fill(PIN);
    await setup.click();
  } else {
    const profiles = page.getByRole('main').getByRole('listitem').getByRole('button');
    await (PROFILE ? profiles.filter({ hasText: PROFILE }).first() : profiles.first()).click();
  }
  await expect(page).toHaveURL(/\/$/);
}

const currentTime = (page: Page) => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime);

test('profile → films grid → play a direct film → back → Continue Watching', async ({ page }) => {
  await bootstrapOrPickProfile(page);

  await page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link', { name: 'Películas' }).click();
  await expect(page).toHaveURL(/\/films/);
  const posters = page.getByRole('main').getByRole('list').getByRole('link');
  await expect(posters.first()).toBeVisible();

  await page.getByRole('main').getByRole('link', { name: new RegExp(FILM) }).first().click();
  await expect(page).toHaveURL(/\/films\/\d+/);
  // A first run sees "Reproducir"; a later one (progress ≈ 35 s left by this test) sees "Reanudar
  // desde 0:35" and "Ver desde el principio". The ▶ glyph is aria-hidden, but tolerate it.
  await page.getByRole('link', { name: /^▶?\uFE0E?\s*(Ver desde el principio|Reproducir)/ }).first().click();
  await expect(page).toHaveURL(/\/watch\/film\/\d+/);
  await expect.poll(() => currentTime(page), { timeout: 10_000 }).toBeGreaterThan(2);

  // Continue Watching needs ≥ 30 s of progress (prd.md §9): seek, let the player track it, leave.
  await page.locator('video').evaluate((v: HTMLVideoElement) => {
    v.currentTime = 35;
  });
  await expect.poll(() => currentTime(page)).toBeGreaterThan(35);
  await page.getByRole('button', { name: 'Volver' }).first().click();
  await expect(page).not.toHaveURL(/\/watch\//);

  await page.goto('/');
  const continueWatching = page.locator('section', { has: page.getByRole('heading', { name: 'Seguir viendo' }) });
  await expect(continueWatching.getByRole('link', { name: new RegExp(FILM) }).first()).toBeVisible();
});
