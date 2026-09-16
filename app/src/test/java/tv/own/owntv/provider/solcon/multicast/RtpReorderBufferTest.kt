package tv.own.owntv.provider.solcon.multicast

import org.junit.Assert.assertEquals
import org.junit.Test

class RtpReorderBufferTest {
    private fun packet(sequence: Int): RtpPacket.Parsed = RtpPacket.Parsed(
        data = byteArrayOf(0x47, (sequence and 0xFF).toByte()),
        payloadOffset = 0,
        payloadLimit = 2,
        sequence = sequence,
        isRtp = true,
    )

    private fun sequences(packets: List<RtpPacket.Parsed>): List<Int> = packets.map { it.sequence!! }

    @Test
    fun `in order packets are emitted immediately`() {
        val buffer = RtpReorderBuffer()

        assertEquals(listOf(10), sequences(buffer.offer(packet(10))))
        assertEquals(listOf(11), sequences(buffer.offer(packet(11))))
        assertEquals(listOf(12), sequences(buffer.offer(packet(12))))
    }

    @Test
    fun `small reordering is repaired before emission`() {
        val buffer = RtpReorderBuffer()

        assertEquals(listOf(10), sequences(buffer.offer(packet(10))))
        assertEquals(emptyList<Int>(), sequences(buffer.offer(packet(12))))
        assertEquals(listOf(11, 12), sequences(buffer.offer(packet(11))))
    }

    @Test
    fun `duplicate and late packets are discarded`() {
        val buffer = RtpReorderBuffer()

        assertEquals(listOf(100), sequences(buffer.offer(packet(100))))
        assertEquals(emptyList<Int>(), sequences(buffer.offer(packet(100))))
        assertEquals(emptyList<Int>(), sequences(buffer.offer(packet(99))))
        assertEquals(listOf(101), sequences(buffer.offer(packet(101))))
    }

    @Test
    fun `bounded buffer forces progress instead of growing without limit`() {
        val buffer = RtpReorderBuffer(maxBuffered = 3)

        assertEquals(listOf(10), sequences(buffer.offer(packet(10))))
        assertEquals(emptyList<Int>(), sequences(buffer.offer(packet(14))))
        assertEquals(emptyList<Int>(), sequences(buffer.offer(packet(15))))
        assertEquals(listOf(14, 15, 16), sequences(buffer.offer(packet(16))))
        assertEquals(0, buffer.bufferedCount)
        assertEquals(3L, buffer.missingPackets)
    }

    @Test
    fun `sequence wraparound preserves forward order`() {
        val buffer = RtpReorderBuffer()

        assertEquals(listOf(65535), sequences(buffer.offer(packet(65535))))
        assertEquals(emptyList<Int>(), sequences(buffer.offer(packet(1))))
        assertEquals(listOf(0, 1), sequences(buffer.offer(packet(0))))
    }
}
