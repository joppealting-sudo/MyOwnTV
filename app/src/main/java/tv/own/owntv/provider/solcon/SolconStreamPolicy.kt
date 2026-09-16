package tv.own.owntv.provider.solcon

import java.net.URI

/**
 * Pure transport classifier for the small Solcon integration seam.
 *
 * Only numeric IPv4 multicast destinations are accepted. Host names are intentionally never resolved
 * here: classification must not perform network I/O or accidentally redirect an ordinary stream into
 * the multicast engine.
 */
object SolconStreamPolicy {
    enum class Transport {
        RTP_MULTICAST,
        UDP_MULTICAST,
        OTHER,
    }

    data class Decision(
        val transport: Transport,
        val normalizedUrl: String,
    ) {
        val isMulticast: Boolean
            get() = transport == Transport.RTP_MULTICAST || transport == Transport.UDP_MULTICAST
    }

    fun classify(url: String): Decision {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Decision(Transport.OTHER, trimmed)

        // Some IPTV playlists use ffmpeg/VLC's `scheme://@group:port` spelling. java.net.URI treats
        // that `@` as authority syntax, so normalize only this multicast-specific marker first.
        val candidate = when {
            trimmed.startsWith("rtp://@", ignoreCase = true) -> "rtp://${trimmed.substring(7)}"
            trimmed.startsWith("udp://@", ignoreCase = true) -> "udp://${trimmed.substring(7)}"
            else -> trimmed
        }

        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return Decision(Transport.OTHER, trimmed)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "rtp" && scheme != "udp") return Decision(Transport.OTHER, trimmed)

        val host = uri.host ?: return Decision(Transport.OTHER, trimmed)
        val port = uri.port
        if (port !in 1..65535 || !isIpv4Multicast(host)) {
            return Decision(Transport.OTHER, trimmed)
        }

        val normalized = "$scheme://$host:$port"
        return Decision(
            transport = if (scheme == "rtp") Transport.RTP_MULTICAST else Transport.UDP_MULTICAST,
            normalizedUrl = normalized,
        )
    }

    private fun isIpv4Multicast(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] in 224..239
    }
}
