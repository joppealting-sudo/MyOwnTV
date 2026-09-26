package tv.own.owntv.provider.solcon.tvplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusProtocol.EpgEntry
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusProtocol.LiveChannel
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.Companion.channelRows
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.Companion.programmeRows

class SolconCatalogMappingTest {
    private val npo1 = LiveChannel(
        id = "101", name = "NPO 1", number = 1, epgId = "NPO1.nl", logoUrl = "https://img.test/1.png",
        order = 1, radio = false, assetId = null, catchupDays = 7,
    )
    private val radio1 = LiveChannel(
        id = "901", name = "NPO Radio 1", number = 801, epgId = null, logoUrl = null,
        order = 901, radio = true, assetId = null,
    )

    @Test
    fun `channels keep Solcon's name, number, logo and order, and play through a reference`() {
        val rows = channelRows(listOf(npo1, radio1), sourceId = 5, tvCategoryId = 10, radioCategoryId = 11, existingIds = emptyMap())

        val tv = rows[0]
        assertEquals("NPO 1", tv.name)
        assertEquals(1, tv.number)
        assertEquals("https://img.test/1.png", tv.logoUrl)
        assertEquals(1, tv.sortOrder)
        assertEquals("101", tv.remoteId)
        assertEquals(5L, tv.sourceId)
        assertEquals("solcon-tvplus://live/101", tv.streamUrl)
        assertEquals("npo1.nl", tv.epgChannelId)
    }

    @Test
    fun `radio lands in its own category, next to TV`() {
        val rows = channelRows(listOf(npo1, radio1), sourceId = 5, tvCategoryId = 10, radioCategoryId = 11, existingIds = emptyMap())
        assertEquals(10L, rows[0].categoryId)
        assertEquals(11L, rows[1].categoryId)
        // No guide id of its own: the guide is filed under the channel id.
        assertEquals("901", rows[1].epgChannelId)
    }

    @Test
    fun `a re-sync updates each channel's existing row, so favourites and history stay attached`() {
        val rows = channelRows(listOf(npo1, radio1), sourceId = 5, tvCategoryId = 10, radioCategoryId = 11, existingIds = mapOf("101" to 42L))
        assertEquals(42L, rows[0].id)
        assertEquals(0L, rows[1].id) // new channel: a new row
    }

    @Test
    fun `catch-up is not offered until OwnTV can play Solcon's replay`() {
        val rows = channelRows(listOf(npo1), sourceId = 5, tvCategoryId = 10, radioCategoryId = 11, existingIds = emptyMap())
        assertFalse(rows[0].catchup)
    }

    @Test
    fun `guide entries find their channel by provider id or guide id`() {
        val entries = listOf(
            EpgEntry("a", channelId = "101", title = "Journaal", description = "Nieuws", startMs = 1_000, endMs = 2_000, assetId = null),
            EpgEntry("b", channelId = "NPO1.NL", title = "Weer", description = null, startMs = 2_000, endMs = 3_000, assetId = null),
            EpgEntry("c", channelId = "901", title = "Radio show", description = null, startMs = 1_000, endMs = 5_000, assetId = null),
        )
        val rows = programmeRows(entries, sourceId = 5, channels = listOf(npo1, radio1))

        assertEquals(listOf("npo1.nl", "npo1.nl", "901"), rows.map { it.epgChannelId })
        assertEquals("Journaal", rows[0].title)
        assertEquals("Nieuws", rows[0].description)
        assertTrue(rows.all { it.sourceId == 5L })
    }

    @Test
    fun `entries for unknown channels or with no running time are dropped`() {
        val entries = listOf(
            EpgEntry("a", channelId = "999", title = "Elsewhere", description = null, startMs = 1_000, endMs = 2_000, assetId = null),
            EpgEntry("b", channelId = "101", title = "Broken", description = null, startMs = 2_000, endMs = 2_000, assetId = null),
        )
        assertTrue(programmeRows(entries, sourceId = 5, channels = listOf(npo1)).isEmpty())
    }
}
