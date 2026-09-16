package tv.own.owntv.provider.solcon.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketTest {
    @Test
    fun `raw mpeg ts datagram is passed through unchanged`() {
        val data = byteArrayOf(0x47, 0x40, 0x00, 0x10, 0x11, 0x22)

        val parsed = RtpPacket.parse(data, data.size)

        assertFalse(parsed.isRtp)
        assertEquals(null, parsed.sequence)
        assertArrayEquals(data, parsed.payloadBytes())
    }

    @Test
    fun `basic rtp v2 header is stripped`() {
        val data = byteArrayOf(
            0x80.toByte(), 33, 0x12, 0x34, // V2, MPEG-TS PT, sequence
            0, 0, 0, 1,                   // timestamp
            0, 0, 0, 2,                   // SSRC
            0x47, 0x40, 0x00, 0x10,
        )

        val parsed = RtpPacket.parse(data, data.size)

        assertTrue(parsed.isRtp)
        assertEquals(0x1234, parsed.sequence)
        assertArrayEquals(byteArrayOf(0x47, 0x40, 0x00, 0x10), parsed.payloadBytes())
    }

    @Test
    fun `csrc identifiers are skipped`() {
        val data = byteArrayOf(
            0x82.toByte(), 33, 0, 7,
            0, 0, 0, 1,
            0, 0, 0, 2,
            1, 2, 3, 4,
            5, 6, 7, 8,
            0x47, 0x01, 0x02,
        )

        val parsed = RtpPacket.parse(data, data.size)

        assertArrayEquals(byteArrayOf(0x47, 0x01, 0x02), parsed.payloadBytes())
    }

    @Test
    fun `extension length is measured in 32 bit words`() {
        val data = byteArrayOf(
            0x90.toByte(), 33, 0, 8,
            0, 0, 0, 1,
            0, 0, 0, 2,
            0x10, 0x00, 0x00, 0x02, // extension id + TWO 32-bit words
            1, 2, 3, 4,
            5, 6, 7, 8,
            0x47, 0x11, 0x22,
        )

        val parsed = RtpPacket.parse(data, data.size)

        assertArrayEquals(byteArrayOf(0x47, 0x11, 0x22), parsed.payloadBytes())
    }

    @Test
    fun `padding bytes are excluded from payload`() {
        val data = byteArrayOf(
            0xA0.toByte(), 33, 0, 9,
            0, 0, 0, 1,
            0, 0, 0, 2,
            0x47, 0x33, 0x44,
            0, 0, 3,
        )

        val parsed = RtpPacket.parse(data, data.size)

        assertArrayEquals(byteArrayOf(0x47, 0x33, 0x44), parsed.payloadBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated csrc header is rejected`() {
        val data = byteArrayOf(
            0x8F.toByte(), 33, 0, 1,
            0, 0, 0, 1,
            0, 0, 0, 2,
        )
        RtpPacket.parse(data, data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated extension is rejected`() {
        val data = byteArrayOf(
            0x90.toByte(), 33, 0, 1,
            0, 0, 0, 1,
            0, 0, 0, 2,
            0x10, 0x00, 0x00, 0x02,
            1, 2, 3, 4,
        )
        RtpPacket.parse(data, data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid padding is rejected`() {
        val data = byteArrayOf(
            0xA0.toByte(), 33, 0, 1,
            0, 0, 0, 1,
            0, 0, 0, 2,
            0x47, 20,
        )
        RtpPacket.parse(data, data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported rtp version is rejected`() {
        val data = byteArrayOf(
            0x40, 33, 0, 1,
            0, 0, 0, 1,
            0, 0, 0, 2,
            0x47,
        )
        RtpPacket.parse(data, data.size)
    }

    @Test
    fun `sequence comparison handles wraparound`() {
        assertTrue(RtpPacket.compareSequence(0, 65535) > 0)
        assertTrue(RtpPacket.compareSequence(65535, 0) < 0)
        assertEquals(0, RtpPacket.compareSequence(42, 42))
    }
}
