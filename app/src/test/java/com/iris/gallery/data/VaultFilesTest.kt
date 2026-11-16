package com.iris.gallery.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class VaultFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test fun successfulCopyPublishesFileAndLeavesNoTemp() {
        val dst = File(tmp.root, "a.jpg")
        val data = bytes(10_000)
        assertTrue(VaultFiles.copyStreamVerified(ByteArrayInputStream(data), dst, data.size.toLong()))
        assertArrayEquals(data, dst.readBytes())
        assertFalse(File(tmp.root, "a.jpg.part").exists())
    }

    @Test fun nullInputStreamFailsWithoutCreatingFiles() {
        val dst = File(tmp.root, "a.jpg")
        assertFalse(VaultFiles.copyStreamVerified(null, dst, 10))
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun interruptedCopyLeavesNothingBehind() {
        val dst = File(tmp.root, "a.jpg")
        val broken = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100) 1 else throw IOException("disk pulled")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n >= 100) throw IOException("disk pulled")
                val k = minOf(len, 100 - n); for (i in 0 until k) b[off + i] = 1; n += k; return k
            }
        }
        assertFalse(VaultFiles.copyStreamVerified(broken, dst, 1000))
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun sizeMismatchIsRejected() {
        val dst = File(tmp.root, "a.jpg")
        assertFalse(VaultFiles.copyStreamVerified(ByteArrayInputStream(bytes(500)), dst, 1000))
        assertFalse(dst.exists())
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun emptyCopyIsRejected() {
        val dst = File(tmp.root, "a.jpg")
        assertFalse(VaultFiles.copyStreamVerified(ByteArrayInputStream(ByteArray(0)), dst, 0))
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun existingDestinationIsNeverOverwritten() {
        val dst = tmp.newFile("a.jpg").also { it.writeText("keep me") }
        assertFalse(VaultFiles.copyStreamVerified(ByteArrayInputStream(bytes(50)), dst, 50))
        assertEquals("keep me", dst.readText())
    }

    @Test fun nullOutputStreamIsAFailedRestore() {
        val src = tmp.newFile("v.bin").also { it.writeBytes(bytes(100)) }
        assertFalse(VaultFiles.copyFileToStream(src, null))
    }

    @Test fun failingOutputStreamIsAFailedRestore() {
        val src = tmp.newFile("v.bin").also { it.writeBytes(bytes(100)) }
        val out = object : OutputStream() { override fun write(b: Int) = throw IOException("full") }
        assertFalse(VaultFiles.copyFileToStream(src, out))
        assertTrue(src.exists())
    }

    @Test fun fileToStreamCopiesAllBytes() {
        val data = bytes(4096)
        val src = tmp.newFile("v.bin").also { it.writeBytes(data) }
        val out = ByteArrayOutputStream()
        assertTrue(VaultFiles.copyFileToStream(src, out))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun identicalDetectionNeedsSameNonEmptyLength() {
        val a = tmp.newFile("a").also { it.writeBytes(bytes(10)) }
        val b = tmp.newFile("b").also { it.writeBytes(bytes(10)) }
        val c = tmp.newFile("c").also { it.writeBytes(bytes(11)) }
        assertTrue(VaultFiles.looksIdentical(a, b))
        assertFalse(VaultFiles.looksIdentical(a, c))
        assertFalse(VaultFiles.looksIdentical(a, File(tmp.root, "missing")))
    }

    @Test fun interruptedTransferPartialsAreCleaned() {
        File(tmp.root, "1_a.jpg.part").writeText("x")
        File(tmp.root, "2_b.jpg").writeText("keep")
        assertEquals(1, VaultFiles.cleanPartials(tmp.root))
        assertTrue(File(tmp.root, "2_b.jpg").exists())
    }
}
