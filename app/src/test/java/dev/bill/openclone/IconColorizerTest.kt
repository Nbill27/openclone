package dev.bill.openclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconColorizerTest {

    @Test
    fun `ORIGINAL option leaves every pixel untouched`() {
        val original = intArrayOf(
            0x00000000,
            0xFFFFFFFF.toInt(),
            0xFF1877F2.toInt(),
            0xFF121212.toInt(),
        )
        val copy = original.copyOf()
        IconColorizer.recolorArgbPixels(copy, IconColorOption.ORIGINAL, cloneNumber = 1)
        assertTrue(original.contentEquals(copy))
    }

    @Test
    fun `AUTO recolors Facebook blue to distinct colors across clones while keeping white logo and transparency intact`() {
        val fbBlue = 0xFF1877F2.toInt()
        val pureWhite = 0xFFFFFFFF.toInt()
        val transparent = 0x00000000

        val producedColors = mutableSetOf<Int>()
        for (cloneNum in 1..6) {
            val pixels = IntArray(20) { idx ->
                when {
                    idx == 0 -> transparent
                    idx in 1..4 -> pureWhite
                    else -> fbBlue
                }
            }
            IconColorizer.recolorArgbPixels(pixels, IconColorOption.AUTO, cloneNumber = cloneNum)

            assertEquals("Transparent pixel must stay transparent", transparent, pixels[0])
            for (i in 1..4) {
                assertEquals("White foreground glyph must stay pure white", pureWhite, pixels[i])
            }
            val recoloredBg = pixels[10]
            assertEquals("Opaque alpha must be preserved", 0xFF, recoloredBg ushr 24)
            assertFalse("Clone $cloneNum background should differ from original Facebook blue", recoloredBg == fbBlue)
            assertTrue("Clone $cloneNum should get a unique color, got duplicate ${Integer.toHexString(recoloredBg)}", producedColors.add(recoloredBg))
        }
    }

    @Test
    fun `named color presets shift dominant hue toward target color`() {
        val fbBlue = 0xFF1877F2.toInt()

        val emeraldPixels = IntArray(16) { fbBlue }
        IconColorizer.recolorArgbPixels(emeraldPixels, IconColorOption.EMERALD, cloneNumber = 1)
        val emerald = emeraldPixels[0]
        val er = (emerald shr 16) and 0xFF
        val eg = (emerald shr 8) and 0xFF
        val eb = emerald and 0xFF
        assertTrue("Emerald preset should be green-dominant (R=$er, G=$eg, B=$eb)", eg > er && eg > eb)

        val crimsonPixels = IntArray(16) { fbBlue }
        IconColorizer.recolorArgbPixels(crimsonPixels, IconColorOption.CRIMSON, cloneNumber = 1)
        val crimson = crimsonPixels[0]
        val cr = (crimson shr 16) and 0xFF
        val cg = (crimson shr 8) and 0xFF
        val cb = crimson and 0xFF
        assertTrue("Crimson preset should be red-dominant (R=$cr, G=$cg, B=$cb)", cr > cg && cr > cb)
    }

    @Test
    fun `monochrome dark icon gets tinted background while preserving white foreground`() {
        val darkBg = 0xFF141414.toInt()
        val whiteFg = 0xFFFFFFFF.toInt()
        val pixels = IntArray(20) { idx -> if (idx < 4) whiteFg else darkBg }

        IconColorizer.recolorArgbPixels(pixels, IconColorOption.PURPLE, cloneNumber = 1)

        assertEquals(whiteFg, pixels[0])
        val tinted = pixels[10]
        val r = (tinted shr 16) and 0xFF
        val g = (tinted shr 8) and 0xFF
        val b = (tinted and 0xFF)
        assertFalse("Monochrome dark background must be recolored", tinted == darkBg)
        assertTrue("Purple tint should boost blue/red over green (R=$r, G=$g, B=$b)", b > g && r > g)
    }
}
