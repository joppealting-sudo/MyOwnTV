package tv.own.owntv.provider.solcon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.player.LiveTuneController
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.PlayerFailureReason
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback

class SolconTuneHostTest {
    /** Core's host, reduced to recognisable answers so it is plain which one the decorator gave. */
    private class RecordingHost : LiveTuneController.Host {
        val resolved = mutableListOf<String>()
        var enginesStarted = 0
        var backToEdge = 0

        override suspend fun sourceOf(sourceId: Long): SourceEntity? = null
        override fun needsResolve(source: SourceEntity?): Boolean = source?.type == SourceType.STALKER
        override suspend fun resolve(source: SourceEntity, cmd: String): String {
            resolved += cmd
            return "http://portal.test/minted"
        }
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = false
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) = Unit
        override suspend fun globalPreference(): EnginePreference = EnginePreference.EXO_FIRST
        override suspend fun globalBudgetSecs(): Int = 30
        override fun meta(channel: ChannelEntity): MediaMeta = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) = Unit
        override fun nowMs(): Long = 123L
        override fun onEngineStarted() {
            enginesStarted++
        }
        override fun onBackToLiveEdge() {
            backToEdge++
        }
        override suspend fun timeshiftWindowMinutes(): Int = 42
        override suspend fun maxVideoHeight(): Int = 1080
    }

    private val core = RecordingHost()
    private val host = SolconTuneHost(
        delegate = core,
        solcon = SolconPlayback(
            resolver = { ResolvedPlayback.Ready("https://cdn.test/live.mpd", null, null) },
            multicastLock = {},
            nowMs = { 0L },
        ),
    )

    private val solconSource = SourceEntity(name = "Solcon TV+", type = SourceType.M3U, url = SolconTvPlusRepository.SOURCE_URL)
    private val m3uSource = SourceEntity(name = "m3u", type = SourceType.M3U, url = "http://h/p.m3u")
    private val portal = SourceEntity(name = "stk", type = SourceType.STALKER, url = "http://portal/c/")

    private fun channel(url: String) = ChannelEntity(id = 1, sourceId = 2, name = "NPO 1", streamUrl = url)

    @Test
    fun `the Solcon playlist is minted just in time, like a portal`() {
        assertTrue(host.needsResolve(solconSource))
        assertTrue(host.needsResolve(portal))
        assertFalse(host.needsResolve(m3uSource))
        assertFalse(host.needsResolve(null))
    }

    @Test
    fun `a TV+ reference resolves through Solcon, everything else through core`() = runBlocking {
        assertEquals("https://cdn.test/live.mpd", host.resolve(solconSource, "solcon-tvplus://live/7"))
        assertTrue(core.resolved.isEmpty())

        assertEquals("http://portal.test/minted", host.resolve(portal, "ffmpeg http://localhost/ch/1_"))
        assertEquals(listOf("ffmpeg http://localhost/ch/1_"), core.resolved)
    }

    @Test
    fun `multicast always opens on mpv, whatever the pin says`() = runBlocking {
        assertEquals(true, host.enginePin(channel("rtp://@239.1.1.1:5000")))
        assertEquals(true, host.enginePin(channel("udp://239.1.1.1:1234")))
        assertEquals(false, host.enginePin(channel("https://example.test/live.m3u8")))
        // Unicast RTP is not IPTV multicast and keeps the ordinary routing.
        assertEquals(false, host.enginePin(channel("rtp://192.168.1.10:5000")))
    }

    @Test
    fun `everything else is core's answer, defaults included`() = runBlocking {
        assertEquals(123L, host.nowMs())
        assertEquals(42, host.timeshiftWindowMinutes())
        assertEquals(1080, host.maxVideoHeight())
        assertNull(host.timeshift)
        host.onEngineStarted()
        host.onBackToLiveEdge()
        assertEquals(1, core.enginesStarted)
        assertEquals(1, core.backToEdge)
        assertEquals(EnginePreference.EXO_FIRST, host.globalPreference())
        assertEquals(30, host.globalBudgetSecs())
    }
}
