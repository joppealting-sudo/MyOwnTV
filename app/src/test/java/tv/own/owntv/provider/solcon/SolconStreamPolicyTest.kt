package tv.own.owntv.provider.solcon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolconStreamPolicyTest {
    @Test
    fun `rtp multicast with at-prefix is normalized and classified`() {
        val decision = SolconStreamPolicy.classify("rtp://@224.0.251.124:8248")

        assertEquals(SolconStreamPolicy.Transport.RTP_MULTICAST, decision.transport)
        assertEquals("rtp://224.0.251.124:8248", decision.normalizedUrl)
        assertTrue(decision.isMulticast)
    }

    @Test
    fun `udp multicast is classified`() {
        val decision = SolconStreamPolicy.classify("udp://239.255.1.2:5000")

        assertEquals(SolconStreamPolicy.Transport.UDP_MULTICAST, decision.transport)
        assertEquals("udp://239.255.1.2:5000", decision.normalizedUrl)
        assertTrue(decision.isMulticast)
    }

    @Test
    fun `lower multicast boundary is accepted`() {
        assertTrue(SolconStreamPolicy.classify("rtp://224.0.0.0:1").isMulticast)
    }

    @Test
    fun `upper multicast boundary is accepted`() {
        assertTrue(SolconStreamPolicy.classify("udp://239.255.255.255:65535").isMulticast)
    }

    @Test
    fun `unicast rtp is not treated as provider multicast`() {
        val decision = SolconStreamPolicy.classify("rtp://192.168.1.10:5000")

        assertEquals(SolconStreamPolicy.Transport.OTHER, decision.transport)
        assertFalse(decision.isMulticast)
    }

    @Test
    fun `http hls remains on existing path`() {
        val decision = SolconStreamPolicy.classify("https://example.test/live/index.m3u8?token=secret")

        assertEquals(SolconStreamPolicy.Transport.OTHER, decision.transport)
        assertFalse(decision.isMulticast)
    }

    @Test
    fun `rtp without a port fails closed`() {
        assertFalse(SolconStreamPolicy.classify("rtp://224.0.0.1").isMulticast)
    }

    @Test
    fun `invalid port fails closed`() {
        assertFalse(SolconStreamPolicy.classify("udp://224.0.0.1:70000").isMulticast)
    }

    @Test
    fun `hostname is never resolved just to classify multicast`() {
        assertFalse(SolconStreamPolicy.classify("rtp://multicast.example.test:5000").isMulticast)
    }

    @Test
    fun `malformed url fails closed without throwing`() {
        val decision = SolconStreamPolicy.classify("rtp://@not an address:wat")

        assertEquals(SolconStreamPolicy.Transport.OTHER, decision.transport)
        assertFalse(decision.isMulticast)
    }
}
