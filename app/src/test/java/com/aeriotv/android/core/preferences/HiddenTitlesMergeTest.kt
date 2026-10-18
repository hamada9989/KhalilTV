package com.aeriotv.android.core.preferences

import com.aeriotv.android.core.preferences.HiddenTitlesStore.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Last-writer-wins merge for hidden on-demand titles (DriveSync parity with the watchlist). */
class HiddenTitlesMergeTest {

    @Test
    fun unknownRemoteRowIsInserted() {
        val merged = HiddenTitlesStore.mergeEntries(
            local = emptyList(),
            remote = listOf(Entry(key = "m:a", playlistId = "host.tv", hiddenAt = 10)),
        )
        assertEquals(1, merged.size)
        assertTrue(merged.first().isLive)
    }

    @Test
    fun laterRemoteTombstoneWins() {
        val merged = HiddenTitlesStore.mergeEntries(
            local = listOf(Entry(key = "m:a", playlistId = "host.tv", hiddenAt = 10)),
            remote = listOf(Entry(key = "m:a", playlistId = "host.tv", hiddenAt = 10, unhiddenAt = 20)),
        )
        assertEquals(1, merged.size)
        assertEquals(20L, merged.first().unhiddenAt)
    }

    @Test
    fun olderRemoteTombstoneLosesToNewerLocalHide() {
        val merged = HiddenTitlesStore.mergeEntries(
            local = listOf(Entry(key = "m:a", playlistId = "host.tv", hiddenAt = 30)),
            remote = listOf(Entry(key = "m:a", playlistId = "host.tv", hiddenAt = 10, unhiddenAt = 20)),
        )
        assertEquals(1, merged.size)
        assertNull(merged.first().unhiddenAt)
        assertEquals(30L, merged.first().hiddenAt)
    }

    @Test
    fun playlistScopeIsPartOfIdentity() {
        val merged = HiddenTitlesStore.mergeEntries(
            local = listOf(Entry(key = "m:a", playlistId = "one.tv", hiddenAt = 10)),
            remote = listOf(Entry(key = "m:a", playlistId = "two.tv", hiddenAt = 5)),
        )
        assertEquals(2, merged.size)
        assertEquals(setOf("one.tv", "two.tv"), merged.mapNotNull { it.playlistId }.toSet())
    }

    @Test
    fun emptyRemoteLeavesLocalAlone() {
        val local = listOf(Entry(key = "m:a", playlistId = null, hiddenAt = 10))
        assertEquals(local, HiddenTitlesStore.mergeEntries(local, emptyList()))
    }
}
