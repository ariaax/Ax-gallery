package com.iris.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStoreTest {
    @Test fun repeatsOnlyCountUp() {
        val s = DiagnosticsStore()
        repeat(500) { s.record("UNSUPPORTED", "image/heic .heic", "d", "a$it.heic", 1000L + it) }
        assertEquals(1, s.size)
        val e = s.all().single()
        assertEquals(500, e.count)
        assertEquals(3, e.samples.size) // only the first few file names are kept
        assertEquals(1499L, e.lastMs)
        assertEquals(1000L, e.firstMs)
    }

    @Test fun differentKindsAreSeparate() {
        val s = DiagnosticsStore()
        s.record("CRASH", "k", "d", null, 1)
        s.record("ERROR", "k", "d", null, 2)
        assertEquals(2, s.size)
    }

    @Test fun capDropsTheOldestProblem() {
        val s = DiagnosticsStore(maxEntries = 3)
        for (i in 1..5) s.record("E", "k$i", "d", null, i.toLong())
        assertEquals(3, s.size)
        assertEquals(setOf("k3", "k4", "k5"), s.all().map { it.key }.toSet())
    }

    @Test fun encodeDecodeKeepsEverythingIncludingNewlinesAndTabs() {
        val s = DiagnosticsStore()
        s.record("CRASH", "Boom: x\ty", "line1\nline2\\end", "name\twith tab.jpg", 42L)
        s.record("CRASH", "Boom: x\ty", "ignored", null, 50L)
        val t = DiagnosticsStore().also { it.decode(s.encode()) }
        val e = t.all().single()
        assertEquals("Boom: x\ty", e.key)
        assertEquals("line1\nline2\\end", e.detail)
        assertEquals(2, e.count)
        assertEquals(42L, e.firstMs); assertEquals(50L, e.lastMs)
        assertEquals(listOf("name\twith tab.jpg"), e.samples)
    }

    @Test fun garbageLinesAreIgnored() {
        val t = DiagnosticsStore().also { it.decode("junk\n\n1\t2\n") }
        assertEquals(0, t.size)
    }

    @Test fun reportListsNewestFirstWithHeader() {
        val s = DiagnosticsStore()
        s.record("A", "old", "", null, 1L)
        s.record("B", "new", "boom\nmore", "x.mp4", 9L)
        val r = s.render("HEADER") { "t$it" }
        assertTrue(r.startsWith("HEADER"))
        assertTrue(r.indexOf("[B]") < r.indexOf("[A]"))
        assertTrue(r.contains("e.g. x.mp4"))
        assertTrue(r.contains("  more"))
    }
}
