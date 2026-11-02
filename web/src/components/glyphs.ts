// Monochrome UI glyphs. U+25B6 (play) and U+26A0 (warning) are Emoji=Yes: without
// the text-presentation selector U+FE0E, iPadOS may draw them from Apple Color Emoji. Built from
// code points so the invisible selector is explicit; `noEmoji.test.ts` rejects bare ones in src/.
const TEXT_PRESENTATION = 0xfe0e;
export const PLAY_GLYPH = String.fromCodePoint(0x25b6, TEXT_PRESENTATION);
export const WARNING_GLYPH = String.fromCodePoint(0x26a0, TEXT_PRESENTATION);
