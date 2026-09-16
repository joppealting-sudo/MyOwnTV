package tv.own.owntv.provider.solcon

import java.net.URI

/**
 * Pure transport classifier for the small Solcon integration seam.
 *
 * Only numeric IPv4 multicast destinations are accepted. Host names are intentionally never resolved
 * here: classification must not perform network I/O or accidentally redirect an ordinary stream into
 * the multicast engine. TV+ synthetic URLs are accepted only for the exact live-channel shape.
 */
object SolconStreamPolicy {
    enum class Transport {
        RTP_MULTICAST,
        UDP_MULTICAST,
        TVPLUS_PROVIDER,
        OTHER,
    }

    data class Decision(
        val transport: Transport,
        val normalizedUrl: String,
    ) {
        val isMulticast: Boolean
            get() = transport == Transport.RTP_MULTICAST || transport == Transport.UDP_MULTICAST

        val isTvPlus: Boolean
            get() = transport == Transport.TVPLUS_PROVIDER
    }

    private val tvPlusLive = Regex("^solcon-tvplus://live/([0-9]+)$", RegexOption.IGNORE_CASE)

    fun classify(url: String): Decision {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Decision(Transport.OTHER, trimmed)

        if (tvPlusLive.matches(trimmed)) {
            return Decision(Transport.TVPLUS_PROVIDER, trimmed)
        }

        // java.net.URI accepts the IPTV `scheme://@group:port` spelling and exposes the numeric group
        // as host, so no provider-specific textual rewrite is needed before parsing.
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Decision(Transport.OTHER, trimmed)
        val scheme = uri.scheme?.lowercase() ?: return Decision(Transport.OTHER, trimmed)
        val transport = when (scheme) {
            "rtp" -> Transport.RTP_MULTICAST
            "udp" -> Transport.UDP_MULTICAST
            else -> return Decision(Transport.OTHER, trimmed)
        }

        val host = uri.host ?: return Decision(Transport.OTHER, trimmed)
        val port = uri.port
        if (port !in 1..65535 || !isIpv4Multicast(host)) {
            return Decision(Transport.OTHER, trimmed)
        }

        val normalized = "$scheme://$host:$port"
        return Decision(transport = transport, normalizedUrl = normalized)
    }

    fun tvPlusLiveId(url: String): String? =
        tvPlusLive.matchEntire(url.trim())?.groupValues?.getOrNull(1)

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
