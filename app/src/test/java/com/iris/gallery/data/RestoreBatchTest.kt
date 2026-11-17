package com.iris.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreBatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Item(val key: String, val path: String)

    private fun merge(existing: List<Item>, restored: List<Item>) = mergeRestored(existing, restored, { it.path }, { it.key })

    @Test fun restoredItemsAreAddedOnce() {
        val existing = listOf(Item("a", "/p/a.jpg"))
        val restored = listOf(Item("b", "/p/b.jpg"), Item("c", "/p/c.jpg"))
        assertEquals(listOf("a", "b", "c"), merge(existing, restored).map { it.key })
    }

    @Test fun mediaStoreRowThatArrivedFirstWins() {
        val existing = listOf(Item("ms-1", "/p/b.jpg"))
        val merged = merge(existing, listOf(Item("-5", "/p/b.jpg")))
        assertSame(existing, merged)
    }

    @Test fun sameKeyIsNotDuplicated() {
        assertEquals(1, merge(listOf(Item("k", "/p/a.jpg")), listOf(Item("k", "/p/other.jpg"))).size)
    }

    @Test fun duplicatesInsideTheRestoredBatchAreDropped() {
        val merged = merge(emptyList(), listOf(Item("a", "/p/a.jpg"), Item("a", "/p/a.jpg")))
        assertEquals(1, merged.size)
    }

    @Test fun emptyRestoreReturnsTheSameList() {
        val existing = listOf(Item("a", "/p/a.jpg"))
        assertSame(existing, merge(existing, emptyList()))
    }

    // The batch sizes the user cares about: 9, 20 and 50 restored photos next to a large library.
    private fun runBatch(n: Int) {
        val library = List(20_000) { Item("m$it", "/lib/$it.jpg") }
        val restored = List(n) { Item("r$it", "/lib/restored/$it.jpg") }
        val t0 = System.nanoTime()
        val merged = merge(library, restored)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(library.size + n, merged.size)
        assertTrue("merging $n restored items took ${ms} ms", ms < 1_000)
    }

    @Test fun merge9() = runBatch(9)
    @Test fun merge20() = runBatch(20)
    @Test fun merge50() = runBatch(50)

    private fun copyBatch(n: Int) {
        val trash = tmp.newFolder("trash$n")
        val dest = tmp.newFolder("dest$n")
        val payload = ByteArray(300_000) { (it % 253).toByte() }
        val sources = List(n) { i -> File(trash, "t$i.jpg").also { it.writeBytes(payload + i.toByte()) } }
        val t0 = System.nanoTime()
        val results = sources.mapIndexed { i, src -> TrashFiles.copyVerified(src, File(dest, "r$i.jpg")) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(results.all { it })
        sources.forEachIndexed { i, src -> assertTrue(TrashFiles.looksIdentical(src, File(dest, "r$i.jpg"), 0L)) }
        assertFalse(dest.listFiles()!!.any { it.name.endsWith(".part") })
        assertTrue("verified copy of $n files took ${ms} ms", ms < 20_000)
    }

    @Test fun verifiedCopy9() = copyBatch(9)
    @Test fun verifiedCopy20() = copyBatch(20)
    @Test fun verifiedCopy50() = copyBatch(50)
}
