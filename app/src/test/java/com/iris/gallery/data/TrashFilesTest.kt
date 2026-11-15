package com.iris.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Only temporary files are used; nothing here touches real media. */
class TrashFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = File(tmp.root, name).apply { writeText(text) }

    @Test fun freePathIsReturnedAsIs() {
        val f = File(tmp.root, "a.jpg")
        assertEquals(f, TrashFiles.uniqueDestination(f))
    }

    @Test fun existingFileIsNeverOverwritten() {
        val existing = file("a.jpg", "other photo")
        val dest = TrashFiles.uniqueDestination(existing)
        assertNotEquals(existing, dest)
        assertEquals("a (1).jpg", dest.name)
        assertEquals("other photo", existing.readText())
    }

    @Test fun namesCountUpAndKeepTheExtension() {
        file("a.jpg", "1"); file("a (1).jpg", "2")
        assertEquals("a (2).jpg", TrashFiles.uniqueDestination(File(tmp.root, "a.jpg")).name)
    }

    @Test fun verifiedCopyMatchesTheSource() {
        val src = file("src.mp4", "video bytes")
        val dst = File(tmp.root, "dst.mp4")
        assertTrue(TrashFiles.copyVerified(src, dst))
        assertEquals("video bytes", dst.readText())
        assertTrue(src.exists())
    }

    @Test fun copyRefusesToReplaceAnExistingFileAndLeavesItAlone() {
        val src = file("src.jpg", "new")
        val dst = file("dst.jpg", "keep me")
        assertFalse(TrashFiles.copyVerified(src, dst))
        assertEquals("keep me", dst.readText())
    }

    @Test fun copyOfAMissingSourceFailsWithoutCreatingAnything() {
        val dst = File(tmp.root, "dst.jpg")
        assertFalse(TrashFiles.copyVerified(File(tmp.root, "missing.jpg"), dst))
        assertFalse(dst.exists())
    }

    @Test fun identicalFileIsRecognisedByLengthAndTime() {
        val src = file("t.jpg", "abc")
        val dst = file("d.jpg", "abc").apply { setLastModified(1_700_000_000_000L) }
        assertTrue(TrashFiles.looksIdentical(src, dst, 1_700_000_000_500L))
        assertFalse(TrashFiles.looksIdentical(src, dst, 1_600_000_000_000L))
        assertFalse(TrashFiles.looksIdentical(file("t2.jpg", "abcd"), dst, 1_700_000_000_000L))
    }
}
