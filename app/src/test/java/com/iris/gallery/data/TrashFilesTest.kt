package com.iris.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

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
    @Test fun sameLengthDifferentContentIsNotIdentical() {
        val src = file("source.jpg", "AAAA")
        val dst = file("destination.jpg", "BBBB").apply { setLastModified(src.lastModified()) }
        assertFalse(TrashFiles.looksIdentical(src, dst, src.lastModified()))
    }

    @Test fun streamCopyRejectsWrongExpectedSizeAndRemovesPartial() {
        val dst = File(tmp.root, "stream.jpg")
        assertFalse(TrashFiles.copyStreamVerified(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dst, 4))
        assertFalse(dst.exists())
        assertFalse(File(tmp.root, "stream.jpg.part").exists())
    }

    @Test fun interruptedStreamCopyLeavesNoPublishedOrPartialFile() {
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ < 128) 7 else throw IOException("injected read failure")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (count >= 128) throw IOException("injected read failure")
                val amount = minOf(length, 128 - count)
                java.util.Arrays.fill(buffer, offset, offset + amount, 7.toByte())
                count += amount
                return amount
            }
        }
        val dst = File(tmp.root, "interrupted.jpg")
        assertFalse(TrashFiles.copyStreamVerified(broken, dst, 1024))
        assertFalse(dst.exists())
        assertFalse(File(tmp.root, "interrupted.jpg.part").exists())
    }

    @Test fun bulkDeleteAttemptsEveryItemAfterFailure() {
        val visited = mutableListOf<Int>()
        val result = TrashFiles.deleteIndependently(listOf(1, 2, 3)) { value ->
            visited += value
            value != 1
        }
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(listOf(false, true, true), result.map { it.second })
    }

    @Test fun rollbackDiscardsBackupOnlyWhenOriginalBytesMatch() {
        val original = file("original.jpg", "original bytes")
        val matchingBackup = file("backup.jpg", "original bytes")
        val differentBackup = file("different.jpg", "different bytes")
        assertTrue(TrashFiles.canDiscardBackup(original.absolutePath, matchingBackup))
        assertFalse(TrashFiles.canDiscardBackup(original.absolutePath, differentBackup))
        assertFalse(TrashFiles.canDiscardBackup("", matchingBackup))
        assertFalse(TrashFiles.canDiscardBackup(File(tmp.root, "missing.jpg").absolutePath, matchingBackup))
    }

}
