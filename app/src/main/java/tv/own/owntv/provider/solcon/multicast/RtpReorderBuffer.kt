package tv.own.owntv.provider.solcon.multicast

/**
 * Bounded RTP sequence repair buffer.
 *
 * It keeps only future packets. Once the bound is reached it deliberately advances to the nearest
 * buffered sequence and accounts for the gap as lost instead of letting a multicast session grow
 * memory without limit.
 */
class RtpReorderBuffer(
    private val maxBuffered: Int = DEFAULT_MAX_BUFFERED,
) {
    init {
        require(maxBuffered > 0)
    }

    private val future = mutableMapOf<Int, RtpPacket.Parsed>()
    private var expectedSequence: Int? = null

    var missingPackets: Long = 0
        private set

    var discardedPackets: Long = 0
        private set

    val bufferedCount: Int
        get() = future.size

    fun offer(packet: RtpPacket.Parsed): List<RtpPacket.Parsed> {
        val sequence = packet.sequence
        if (sequence == null) return listOf(packet)

        val expected = expectedSequence
        if (expected == null) {
            expectedSequence = RtpPacket.nextSequence(sequence)
            return listOf(packet)
        }

        return when (RtpPacket.compareSequence(sequence, expected)) {
            0 -> emitAndDrain(packet)
            -1 -> {
                discardedPackets++
                emptyList()
            }
            else -> bufferFuture(packet, expected)
        }
    }

    fun clear() {
        future.clear()
        expectedSequence = null
    }

    private fun bufferFuture(packet: RtpPacket.Parsed, expected: Int): List<RtpPacket.Parsed> {
        val sequence = packet.sequence ?: return listOf(packet)
        if (future.putIfAbsent(sequence, packet) != null) {
            discardedPackets++
            return emptyList()
        }
        if (future.size < maxBuffered) return emptyList()

        val nearestSequence = future.keys.minBy { forwardDistance(expected, it) }
        val nearest = future.remove(nearestSequence) ?: return emptyList()
        missingPackets += forwardDistance(expected, nearestSequence).toLong()
        expectedSequence = nearestSequence
        return emitAndDrain(nearest)
    }

    private fun emitAndDrain(first: RtpPacket.Parsed): List<RtpPacket.Parsed> {
        val output = ArrayList<RtpPacket.Parsed>()
        var current = first
        while (true) {
            output += current
            val sequence = current.sequence ?: break
            val next = RtpPacket.nextSequence(sequence)
            expectedSequence = next
            current = future.remove(next) ?: break
        }
        return output
    }

    private fun forwardDistance(from: Int, to: Int): Int = (to - from) and 0xFFFF

    companion object {
        const val DEFAULT_MAX_BUFFERED = 64
    }
}
