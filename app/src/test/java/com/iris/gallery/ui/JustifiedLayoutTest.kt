package com.iris.gallery.ui

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JustifiedLayoutTest {
    private val width = 1080 - 8
    private val gap = 4
    private val target = 380

    private fun randomAspects(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        val pool = floatArrayOf(0.75f, 1.333f, 0.5625f, 1.777f, 1f, 2.0f, 0.66f)
        return FloatArray(n) { pool[r.nextInt(pool.size)] }
    }

    @Test fun fullRowsFillTheWidthExactly() {
        val l = JustifiedLayout.layoutGroup(randomAspects(40, 1), width, gap, target)
        for (row in 0 until l.rowCount - 1) {
            val from = l.rowStarts[row]; val to = l.rowStarts[row + 1]
            assertEquals("row $row", JustifiedLayout.SPAN_UNITS, (from until to).sumOf { l.spans[it] })
        }
    }

    @Test fun lastRowNeverOverflows() {
        for (n in 1..30) {
            val l = JustifiedLayout.layoutGroup(randomAspects(n, n), width, gap, target)
            val from = l.rowStarts.last()
            assertTrue("n=$n", (from until n).sumOf { l.spans[it] } <= JustifiedLayout.SPAN_UNITS)
        }
    }

    @Test fun everyPhotoIsPlacedOnceInOrder() {
        val l = JustifiedLayout.layoutGroup(randomAspects(57, 7), width, gap, target)
        assertEquals(57, l.spans.size)
        assertTrue(l.spans.all { it >= 6 })
        assertTrue((1 until l.rowStarts.size).all { l.rowStarts[it - 1] < l.rowStarts[it] })
        assertEquals(0, l.rowStarts.first())
    }

    @Test fun tilesKeepTheirProportionWithinAFewPercent() {
        val aspects = randomAspects(120, 3)
        val l = JustifiedLayout.layoutGroup(aspects, width, gap, target)
        var worst = 0f
        for (row in 0 until l.rowCount - 1) {
            for (k in l.rowStarts[row] until l.rowStarts[row + 1]) {
                val w = JustifiedLayout.widthOfSpan(l.spans[k], width, gap)
                val shown = w / l.heightsPx[k]
                val real = aspects[k].coerceIn(0.8f, 1.3f) // the layout deliberately limits extreme shapes
                worst = maxOf(worst, abs(shown - real) / real)
            }
        }
        assertTrue("worst crop $worst", worst < 0.12f)
    }

    @Test fun rowHeightsStayNearTarget() {
        val l = JustifiedLayout.layoutGroup(randomAspects(200, 11), width, gap, target)
        for (row in 0 until l.rowCount - 1) {
            assertTrue("row $row height ${l.rowHeightsPx[row]}", l.rowHeightsPx[row] in (target * 0.55).toInt()..(target * 1.6).toInt())
        }
    }

    @Test fun tilesComeInDifferentSizes() {
        val l = JustifiedLayout.layoutGroup(randomAspects(60, 5), width, gap, target)
        assertTrue(l.spans.toSet().size >= 4)
    }

    @Test fun shortLastRowIsNotStretched() {
        val l = JustifiedLayout.layoutGroup(floatArrayOf(0.75f), width, gap, target)
        assertEquals(1, l.rowCount)
        assertEquals(target, l.heightsPx[0])
        assertTrue(l.spans[0] < JustifiedLayout.SPAN_UNITS / 2)
    }

    @Test fun badAspectRatiosAreSafe() {
        val l = JustifiedLayout.layoutGroup(floatArrayOf(0f, -1f, Float.NaN.coerceAtLeast(0f), 50f, 0.0001f), width, gap, target)
        assertEquals(5, l.spans.size)
        assertTrue(l.heightsPx.all { it > 0 })
    }

    @Test fun tenThousandPhotosLayOutQuickly() {
        val aspects = randomAspects(10_000, 99)
        val t0 = System.nanoTime()
        // 10,000 photos split in days of ~25
        var rows = 0
        for (g in 0 until 400) {
            rows += JustifiedLayout.layoutGroup(aspects.copyOfRange(g * 25, g * 25 + 25), width, gap, target).rowCount
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("10k layout: $ms ms, $rows rows")
        assertTrue(ms < 1500)
    }
}
