package com.castivio.feature.activation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.castivioMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How wide a form column is allowed to get, now that it is not asked per device.
 *
 * ## What this replaced, and why the old rule was worse than it looked
 *
 * `ActivationSurface` capped its scrolling column at
 * `if (device.isTv) Sizing.maxContentWidth / 2 else Sizing.maxContentWidth` — 720dp
 * against 1440. Read as a pair that looks like two considered numbers. It is not: 1440dp
 * is a width no handset and no tablet Castivio ships to ever reaches, so the `else`
 * branch was **no cap at all**, and a server address on a 1280dp tablet was a text field
 * 1232dp wide. The rule was really "720 on a set, unbounded on everything else", written
 * so that only half of it was visible.
 *
 * A measure belongs to the type, which is what `LegalScreen.measureFor` established in
 * phase 3 and what [formMeasure] borrows. The ceiling reproduces the television's 720
 * exactly; what moves is every surface the old `else` branch never bounded.
 */
class FormMeasureTest {

    private data class Surface(val name: String, val width: Dp, val height: Dp, val tv: Boolean) {
        val measure: Dp get() = formMeasure(castivioMetrics(width, height, tv))
        override fun toString() = name
    }

    private val surfaces = listOf(
        Surface("shortest phone 800x360", 800.dp, 360.dp, tv = false),
        Surface("narrow frame 568x360", 568.dp, 360.dp, tv = false),
        Surface("reference phone 873x393", 873.dp, 393.dp, tv = false),
        Surface("television 960x540", 960.dp, 540.dp, tv = true),
        Surface("reference 1280x720", 1280.dp, 720.dp, tv = false),
        Surface("tablet 1280x800", 1280.dp, 800.dp, tv = false),
        Surface("1080p set 1920x1080", 1920.dp, 1080.dp, tv = true),
        Surface("4K surface 3840x2160", 3840.dp, 2160.dp, tv = true),
    )

    /**
     * The television keeps the measure it was given, on every set it runs on.
     *
     * 720dp was the one half of the old pair that was a real decision, and it has to
     * survive the migration on a 1080p panel and a 4K one as well as on the 960dp frame
     * the drawing was made at — all three report a different surface and all three were
     * handed the same 720 before.
     */
    @Test
    fun `every television keeps the measure it was drawn with`() {
        for (surface in surfaces.filter { it.tv }) {
            assertEquals("$surface: the measure", 720f, surface.measure.value, 0.5f)
        }
    }

    /**
     * The reference sits on the ceiling, which is where the 720 comes from.
     *
     * Fifty-three characters of the reference's own 18dp body step is 960dp, and the
     * ceiling is 720. The share is not wrong and the ceiling is not arbitrary: 720 is
     * the measure the television was drawn with, and the reference is a geometry a
     * drawing is judged at rather than a surface a form is ever filled in on. So every
     * surface from the television upward is capped here, and only the handsets — whose
     * body step is on *its* floor — come out narrower.
     */
    @Test
    fun `the reference sits on the ceiling the television was drawn with`() {
        val m = castivioMetrics(CastivioReference.Width, CastivioReference.Height, isTv = false)
        assertEquals("the measure", 720f, formMeasure(m).value, 0.01f)
        assertTrue(
            "the reference is not capped",
            m.fsBody.value * 53.33f > 720f,
        )
    }

    /** Bounded at both ends, on every surface, which the rule it replaced was not. */
    @Test
    fun `the measure stays inside its bounds`() {
        for (surface in surfaces) {
            assertTrue("$surface: the measure is ${surface.measure}", surface.measure in 600.dp..720.dp)
        }
    }

    /**
     * **The measure never squeezes a surface narrower than itself.**
     *
     * A cap is only a cap: where the stage leaves less than the measure, the column takes
     * what there is. The claim worth asserting is the other direction — that the floor is
     * not so high it stops being reachable on the shortest frame the project ships to,
     * which is where a 600dp floor and a 568dp surface would quietly disagree.
     */
    @Test
    fun `a narrow surface is capped by the stage rather than by the measure`() {
        val narrow = Surface("narrow frame 568x360", 568.dp, 360.dp, tv = false)
        val stage = narrow.width - castivioMetrics(narrow.width, narrow.height, false).edge * 2
        assertTrue(
            "the stage leaves $stage and the measure asks for ${narrow.measure}",
            stage < narrow.measure,
        )
    }

    /** It only grows with the surface, and then it stops. */
    @Test
    fun `the measure only grows with the surface, and stops`() {
        val ladder = listOf(
            568.dp to 360.dp,
            800.dp to 360.dp,
            873.dp to 393.dp,
            960.dp to 540.dp,
            1280.dp to 800.dp,
            1920.dp to 1080.dp,
            3840.dp to 2160.dp,
        ).map { (w, h) -> formMeasure(castivioMetrics(w, h, isTv = false)) }

        for ((smaller, larger) in ladder.zipWithNext()) {
            assertTrue("the measure shrank: $smaller then $larger", larger >= smaller)
        }
        assertEquals("the 4K measure is unbounded", 720f, ladder.last().value, 0.01f)
    }

    /** Every surface between the shortest and the largest holds. */
    @Test
    fun `every surface between the shortest and the largest holds`() {
        var widest = 0.dp
        var widestAt = ""

        var height = 330.dp
        while (height <= 2160.dp) {
            for (aspect in listOf(16f / 9f, 1.85f, 2f, 2.2f, 2.4f)) {
                val width = height * aspect
                for (tv in listOf(false, true)) {
                    val m = castivioMetrics(width, height, tv)
                    val where = "${width.value.toInt()}x${height.value.toInt()} tv=$tv"
                    val measure = formMeasure(m)
                    val column = minOf(width - m.edge * 2, measure)

                    assertTrue("$where: the measure is $measure", measure in 600.dp..720.dp)
                    assertTrue("$where: the column is $column", column > 0.dp)
                    if (column > widest) {
                        widest = column
                        widestAt = where
                    }
                }
            }
            height += 1.dp
        }

        println("form sweep — widest column $widest at $widestAt")
        assertTrue("a form column reached $widest at $widestAt", widest <= 720.dp)
    }
}
