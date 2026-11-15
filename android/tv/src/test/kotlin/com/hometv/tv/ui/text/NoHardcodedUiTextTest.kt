package com.hometv.tv.ui.text

import com.hometv.tv.testing.NoHardcodedUiText
import java.io.File
import org.junit.jupiter.api.Test

/** No UI string literal in `tv/src/main/kotlin` (the scan lives in `:testing`). */
class NoHardcodedUiTextTest {
    @Test
    fun `no hard-coded UI text in tv main sources`() {
        NoHardcodedUiText.assertClean(File("src/main/kotlin"))
    }

    @Test
    fun `the scanner catches the forbidden forms`() {
        NoHardcodedUiText.assertScannerCatchesForbiddenForms()
    }
}
