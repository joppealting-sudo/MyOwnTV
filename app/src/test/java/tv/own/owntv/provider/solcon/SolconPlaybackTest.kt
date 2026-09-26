package tv.own.owntv.provider.solcon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback

class SolconPlaybackTest {
    private class CountingLock : MulticastLock {
        var holds = 0
        override fun hold() {
            holds++
        }
    }

    private var nowMs = 1_000L
    private val lock = CountingLock()
    private val answers = ArrayDeque<ResolvedPlayback>()
    private val asked = mutableListOf<String>()
    private val playback = SolconPlayback(
        resolver = { reference ->
            asked += reference
            answers.removeFirst()
        },
        multicastLock = lock,
        nowMs = { nowMs },
    )

    private fun channel(url: String) = ChannelEntity(id = 7, sourceId = 3, name = "NPO 1", streamUrl = url)

    private suspend fun ready(url: String): ChannelEntity =
        (playback.prepare(channel(url)) as SolconPlayback.Prepared.Ready).channel

    @Test
    fun `an ordinary channel passes through untouched`() = runBlocking {
        val original = channel("https://example.test/live/1.m3u8")
        val prepared = playback.prepare(original) as SolconPlayback.Prepared.Ready
        assertSame(original, prepared.channel)
        assertTrue(asked.isEmpty())
        assertEquals(0, lock.holds)
    }

    @Test
    fun `an imported multicast channel takes the Wi-Fi lock and needs no provider`() = runBlocking {
        assertEquals("rtp://@239.1.1.1:5000", ready("rtp://@239.1.1.1:5000").streamUrl)
        assertEquals(1, lock.holds)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `raw UDP survives a receive buffer overrun`() {
        assertEquals("udp://@239.1.1.1:1234?overrun_nonfatal=1", SolconPlayback.multicastUrl("udp://@239.1.1.1:1234"))
        assertEquals(
            "udp://239.1.1.1:1234?fifo_size=100000&overrun_nonfatal=1",
            SolconPlayback.multicastUrl("udp://239.1.1.1:1234?fifo_size=100000"),
        )
        assertEquals(
            "udp://239.1.1.1:1234?overrun_nonfatal=0",
            SolconPlayback.multicastUrl("udp://239.1.1.1:1234?overrun_nonfatal=0"),
        )
        assertEquals("rtp://@239.1.1.1:5000", SolconPlayback.multicastUrl(" rtp://@239.1.1.1:5000 "))
    }

    @Test
    fun `a clear TV+ stream keeps its reference and hands the tuner the minted link`() = runBlocking {
        answers += ResolvedPlayback.Ready("https://cdn.test/a.mpd?token=1", httpHeaders = "Referer: https://tv.test", drmConfig = null)
        val prepared = ready("solcon-tvplus://live/42")

        assertEquals("solcon-tvplus://live/42", prepared.streamUrl)
        assertEquals("Referer: https://tv.test", prepared.httpHeaders)
        assertNull(prepared.drmConfig)
        // The tuner's own resolve moments later reuses the link instead of asking TV+ a second time.
        assertEquals("https://cdn.test/a.mpd?token=1", playback.resolveUrl("solcon-tvplus://live/42"))
        assertEquals(listOf("solcon-tvplus://live/42"), asked)
    }

    @Test
    fun `a reconnect after the tune asks TV+ for a fresh link`() = runBlocking {
        answers += ResolvedPlayback.Ready("https://cdn.test/a.mpd?token=1", null, null)
        answers += ResolvedPlayback.Ready("https://cdn.test/a.mpd?token=2", null, null)
        ready("solcon-tvplus://live/42")

        nowMs += SolconPlayback.FRESH_MS
        assertEquals("https://cdn.test/a.mpd?token=2", playback.resolveUrl("solcon-tvplus://live/42"))
        assertEquals(2, asked.size)
    }

    @Test
    fun `a Widevine stream carries its licence to the tuner`() = runBlocking {
        answers += ResolvedPlayback.Ready("https://cdn.test/b.mpd", httpHeaders = null, drmConfig = "widevine|https://licence.test")
        val prepared = ready("solcon-tvplus://live/9")

        assertEquals("widevine|https://licence.test", prepared.drmConfig)
        assertEquals("solcon-tvplus://live/9", prepared.streamUrl)
    }

    @Test
    fun `TV+ answering with multicast plays the group address directly`() = runBlocking {
        answers += ResolvedPlayback.Ready("rtp://239.2.2.2:1234", null, null)
        val prepared = ready("solcon-tvplus://live/5")

        assertEquals("rtp://239.2.2.2:1234", prepared.streamUrl)
        assertEquals(1, lock.holds)
        // A group address is not minted, so the tuner passes it through without asking again.
        assertEquals("rtp://239.2.2.2:1234", playback.resolveUrl(prepared.streamUrl))
        assertEquals(1, asked.size)
    }

    @Test
    fun `a refusal says why and plays nothing`() = runBlocking {
        answers += ResolvedPlayback.Unsupported(ErrorCategory.PROTECTED_UNSUPPORTED, "proprietary DRM")
        answers += ResolvedPlayback.Failed(ErrorCategory.NOT_AUTHENTICATED, "signed out")

        assertEquals(
            SolconPlayback.Prepared.Failed(ErrorCategory.PROTECTED_UNSUPPORTED),
            playback.prepare(channel("solcon-tvplus://live/1")),
        )
        assertEquals(
            SolconPlayback.Prepared.Failed(ErrorCategory.NOT_AUTHENTICATED),
            playback.prepare(channel("solcon-tvplus://live/2")),
        )
    }

    @Test
    fun `the tuner's resolve fails the attempt when TV+ refuses`() = runBlocking {
        answers += ResolvedPlayback.Failed(ErrorCategory.NETWORK, "offline")
        assertNull(playback.resolveUrl("solcon-tvplus://live/3"))
    }

    @Test
    fun `anything that is not a TV+ reference resolves to itself`() = runBlocking {
        assertEquals("https://example.test/x.ts", playback.resolveUrl("https://example.test/x.ts"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `only the Solcon TV+ playlist is resolved here`() {
        assertTrue(playback.ownsSource(SourceEntity(name = "Solcon", type = SourceType.M3U, url = SolconTvPlusRepository.SOURCE_URL)))
        assertFalse(playback.ownsSource(SourceEntity(name = "m3u", type = SourceType.M3U, url = "http://h/p.m3u")))
        assertFalse(playback.ownsSource(null))
    }
}
