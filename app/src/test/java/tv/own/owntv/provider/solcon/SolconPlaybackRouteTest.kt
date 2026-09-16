package tv.own.owntv.provider.solcon

import org.junit.Assert.assertEquals
import org.junit.Test

class SolconPlaybackRouteTest {
    @Test
    fun `multicast uses Media3 by default`() {
        assertEquals(
            SolconPlaybackRoute.Target.MEDIA3_MULTICAST,
            SolconPlaybackRoute.decide("rtp://@224.0.251.124:8248", forceMpv = false),
        )
    }

    @Test
    fun `forced compatibility mode sends multicast to mpv`() {
        assertEquals(
            SolconPlaybackRoute.Target.MPV_MULTICAST,
            SolconPlaybackRoute.decide("udp://239.1.2.3:5000", forceMpv = true),
        )
    }

    @Test
    fun `ordinary hls stays on existing OwnTV ladder`() {
        assertEquals(
            SolconPlaybackRoute.Target.EXISTING,
            SolconPlaybackRoute.decide("https://example.test/live/index.m3u8", forceMpv = false),
        )
    }

    @Test
    fun `unicast rtp stays on existing path`() {
        assertEquals(
            SolconPlaybackRoute.Target.EXISTING,
            SolconPlaybackRoute.decide("rtp://192.168.1.20:5000", forceMpv = false),
        )
    }
}
