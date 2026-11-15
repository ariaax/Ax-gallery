package com.iris.gallery.data

import com.iris.gallery.data.RestoredDatesCodec.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoredDatesCodecTest {
    private val day = 86_400_000L

    @Test fun roundTripKeepsPathsWithBarsAndSpaces() {
        val e = Entry(1_000L, 2_000L, "/storage/emulated/0/DCIM/a|b (1).jpg")
        assertEquals(e, RestoredDatesCodec.decode(RestoredDatesCodec.encode(e)))
    }

    @Test fun brokenLinesAreIgnored() {
        assertNull(RestoredDatesCodec.decode("nonsense"))
        assertNull(RestoredDatesCodec.decode("1|x|/p"))
        assertNull(RestoredDatesCodec.decode("1|2|"))
    }

    @Test fun newestEntryPerPathWins() {
        val out = RestoredDatesCodec.prune(listOf(Entry(10, 111, "/p"), Entry(20, 222, "/p")), now = 30)
        assertEquals(listOf(Entry(20, 222, "/p")), out)
    }

    @Test fun oldEntriesExpire() {
        val now = 200 * day
        val out = RestoredDatesCodec.prune(listOf(Entry(now - 91 * day, 1, "/old"), Entry(now - 5 * day, 2, "/new")), now)
        assertEquals(listOf("/new"), out.map { it.path })
    }

    @Test fun listIsCapped() {
        val many = (1..RestoredDatesCodec.MAX_ENTRIES + 50).map { Entry(it.toLong(), 1, "/p$it") }
        assertEquals(RestoredDatesCodec.MAX_ENTRIES, RestoredDatesCodec.prune(many, now = 1000).size)
    }
}
