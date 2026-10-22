// Layout check in a real browser (jsdom has no layout): the ring around the selected avatar is a
// circle concentric with the avatar and wraps it, in the first-run form and in the narrow "Editar
// perfil" dialog. Run against a server whose admin PIN is HOMETV_E2E_PIN (default 1234), e.g. a
// throwaway server with an empty data dir (see smoke.spec.ts for the env vars).
import { expect, test, type Locator, type Page } from '@playwright/test';

const PIN = process.env.HOMETV_E2E_PIN ?? '1234';
const PROFILE = process.env.HOMETV_E2E_PROFILE;
// AvatarPicker option: 2 px padding + 3 px border on each side of the avatar.
const RING_INSET = 5;

async function expectRingAroundSelectedAvatar(scope: Page | Locator) {
  const option = scope.locator('label:has(input[type="radio"][name="avatar_id"]:checked)');
  const ring = await option.boundingBox();
  const avatar = await option.locator('span[aria-hidden="true"]').boundingBox();
  if (!ring || !avatar) throw new Error('selected avatar option not rendered');
  // A circle, not an oval.
  expect(Math.abs(ring.width - ring.height)).toBeLessThanOrEqual(1);
  // Concentric with the avatar.
  expect(Math.abs(ring.x + ring.width / 2 - (avatar.x + avatar.width / 2))).toBeLessThanOrEqual(1);
  expect(Math.abs(ring.y + ring.height / 2 - (avatar.y + avatar.height / 2))).toBeLessThanOrEqual(1);
  // Wraps the avatar with its padding and border, without overlapping it.
  expect(ring.width).toBeGreaterThanOrEqual(avatar.width + 2 * RING_INSET - 1);
  expect(ring.width).toBeLessThanOrEqual(avatar.width + 2 * RING_INSET + 1);
}

test('the selected-avatar ring is a circle around the avatar (setup form and edit dialog)', async ({ page }) => {
  await page.goto('/');
  const setup = page.getByRole('button', { name: 'Crear perfil' });
  const picker = page.getByRole('heading', { name: '¿Quién está viendo?' });
  await expect(setup.or(picker)).toBeVisible();

  if (await setup.isVisible()) {
    await page.getByLabel('Nombre').fill('E2E');
    // The radio is visually hidden; click its visible option (the label).
    await page.locator('label', { has: page.getByRole('radio', { name: 'Avatar 2' }) }).click();
    await expectRingAroundSelectedAvatar(page);
    await page.getByLabel('PIN de administrador').fill(PIN);
    await page.getByLabel('Confirmar PIN').fill(PIN);
    await setup.click();
  } else {
    const profiles = page.getByRole('main').getByRole('listitem').getByRole('button');
    await (PROFILE ? profiles.filter({ hasText: PROFILE }).first() : profiles.first()).click();
  }
  await expect(page).toHaveURL(/\/$/);

  await page.goto('/admin/profiles');
  await expect(page.getByRole('heading', { name: 'Introduce el PIN de administrador' })).toBeVisible();
  await page.keyboard.type(PIN);
  await page.getByRole('button', { name: /^Editar / }).first().click();
  const dialog = page.getByRole('dialog', { name: 'Editar perfil' });
  await expect(dialog).toBeVisible();
  await expectRingAroundSelectedAvatar(dialog);
});
