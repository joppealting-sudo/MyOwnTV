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

        // java.net.URI accepts the IPTV `scheme://@group:port` spelling and exposes the numeric group
        // as host, so no provider-specific textual rewrite is needed before parsing.
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Decision(Transport.OTHER, trimmed)
        val scheme = uri.scheme?.lowercase() ?: return Decision(Transport.OTHER, trimmed)
        val rtpScheme = Transport.RTP_MULTICAST.name.substringBefore('_').lowercase()
        val udpScheme = Transport.UDP_MULTICAST.name.substringBefore('_').lowercase()
        val transport = when (scheme) {
            rtpScheme -> Transport.RTP_MULTICAST
            udpScheme -> Transport.UDP_MULTICAST
            else -> return Decision(Transport.OTHER, trimmed)
        }

        val host = uri.host ?: return Decision(Transport.OTHER, trimmed)
        val port = uri.port
        if (port !in 1..65535 || !isIpv4Multicast(host)) {
            return Decision(Transport.OTHER, trimmed)
        }

        val normalized = buildString {
            append(scheme)
            append(':')
            append('/')
            append('/')
            append(host)
            append(':')
            append(port)
        }
        return Decision(transport = transport, normalizedUrl = normalized)
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
