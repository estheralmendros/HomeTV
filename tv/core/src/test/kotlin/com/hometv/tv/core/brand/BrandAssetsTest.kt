package com.hometv.tv.core.brand

import java.io.File
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Brand: the banner and the launcher icon use the web app's logo (red ▶ + "HomeTV" on dark). */
class BrandAssetsTest {
    // Lives in :core because its tests run on a plain JVM (javax.imageio); the assets are the app's,
    // located through the `hometv.appRes` system property set by `core/build.gradle.kts`.
    private val res = File(requireNotNull(System.getProperty("hometv.appRes")) { "hometv.appRes is not set: run through Gradle (:core:test)" })

    private fun near(rgb: Int, r: Int, g: Int, b: Int, tolerance: Int = 24): Boolean {
        val (pr, pg, pb) = Triple(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
        return kotlin.math.abs(pr - r) <= tolerance && kotlin.math.abs(pg - g) <= tolerance && kotlin.math.abs(pb - b) <= tolerance
    }

    @Test
    fun `the banner is the web logo on the web background`() {
        val banner = ImageIO.read(File(res, "drawable-xhdpi/banner.png"))
        assertEquals(320 to 180, banner.width to banner.height)
        val pixels = (0 until banner.height).flatMap { y -> (0 until banner.width).map { x -> banner.getRGB(x, y) } }
        assertTrue(near(banner.getRGB(4, 4), 0x0B, 0x0D, 0x12, 6), "the corner is the web background #0B0D12")
        val red = pixels.count { near(it, 0xE5, 0x09, 0x14) }
        val white = pixels.count { near(it, 0xFF, 0xFF, 0xFF, 12) }
        val blue = pixels.count { near(it, 0x4F, 0xA3, 0xFF) }
        assertTrue(red > 200, "the red ▶ mark (#E50914): $red pixels")
        assertTrue(white > 800, "the white \"HomeTV\": $white pixels")
        assertEquals(0, blue, "no old blue mark (#4FA3FF)")
    }

    @Test
    fun `the launcher icon is the red play mark on dark`() {
        val foreground = File(res, "drawable/ic_launcher_foreground.xml").readText()
        assertTrue(foreground.contains("#FFE50914", ignoreCase = true), "red ▶ in the launcher foreground")
        assertTrue(!foreground.contains("#FF4FA3FF", ignoreCase = true), "no old blue circle")
        assertTrue(File(res, "values/colors.xml").readText().contains("""<color name="ic_launcher_background">#FF0B0D12</color>"""), "web background behind the icon")
    }
}
