package tv.own.owntv.provider.solcon.multicast

/**
 * Small RTP-v2 parser adapted from cgang/myiptv's MIT-licensed RTP transport.
 *
 * Unlike the original implementation, RTP extension sizes are interpreted per RFC 3550 as a count
 * of 32-bit words and every variable-length header field is bounds checked before use.
 */
object RtpPacket {
    data class Parsed(
        val data: ByteArray,
        val payloadOffset: Int,
        val payloadLimit: Int,
        val sequence: Int?,
        val isRtp: Boolean,
    ) {
        val payloadLength: Int
            get() = payloadLimit - payloadOffset

        fun payloadBytes(): ByteArray = data.copyOfRange(payloadOffset, payloadLimit)
    }

    fun parse(data: ByteArray, length: Int): Parsed {
        require(length in 1..data.size)

        // MPEG-TS sync byte. MyIPTV deliberately accepts a raw TS datagram even when the playlist
        // uses rtp://; this is useful for providers whose playlist notation is inconsistent.
        if ((data[0].toInt() and 0xFF) == 0x47) {
            return Parsed(data, 0, length, null, isRtp = false)
        }

        require(length >= RTP_FIXED_HEADER_BYTES)
        val first = data[0].toInt() and 0xFF
        val version = first ushr 6
        require(version == RTP_VERSION)

        val csrcCount = first and 0x0F
        var offset = RTP_FIXED_HEADER_BYTES + csrcCount * CSRC_BYTES
        require(offset <= length)

        if ((first and EXTENSION_BIT) != 0) {
            require(offset + RTP_EXTENSION_HEADER_BYTES <= length)
            val words = uint16(data, offset + 2)
            val extensionBytes = words * 4L
            val newOffset = offset.toLong() + RTP_EXTENSION_HEADER_BYTES + extensionBytes
            require(newOffset <= length.toLong())
            offset = newOffset.toInt()
        }

        var limit = length
        if ((first and PADDING_BIT) != 0) {
            val padding = data[length - 1].toInt() and 0xFF
            require(padding > 0)
            require(padding <= limit - offset)
            limit -= padding
        }

        require(offset <= limit)
        return Parsed(
            data = data,
            payloadOffset = offset,
            payloadLimit = limit,
            sequence = uint16(data, 2),
            isRtp = true,
        )
    }

    /** Compare unsigned 16-bit RTP sequence numbers while treating wraparound as forward progress. */
    fun compareSequence(a: Int, b: Int): Int {
        val left = a and 0xFFFF
        val right = b and 0xFFFF
        if (left == right) return 0
        val diff = (left - right) and 0xFFFF
        return if (diff in 1..0x7FFF) 1 else -1
    }

    fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFFFF

    private fun uint16(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size)
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private const val RTP_VERSION = 2
    private const val RTP_FIXED_HEADER_BYTES = 12
    private const val CSRC_BYTES = 4
    private const val RTP_EXTENSION_HEADER_BYTES = 4
    private const val EXTENSION_BIT = 0x10
    private const val PADDING_BIT = 0x20
}
