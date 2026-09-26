package tv.own.owntv.provider.solcon

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository

/**
 * What a Solcon channel needs before core's live tuner can play it.
 *
 * - A Solcon TV+ channel is stored as `solcon-tvplus://live/<id>`. Its stream, request headers and any
 *   Widevine licence are asked for just before it plays and never written to the database. [prepare]
 *   puts the headers and licence on the channel handed to the tuner; the tuner mints the address itself
 *   through [resolveUrl] (see [SolconTuneHost]) on every tune, engine handover and reconnect, so a link
 *   that expires is always fresh.
 * - Clear IPv4 multicast (`rtp://` / `udp://`, from TV+ or an imported M3U) needs no minting, but only mpv
 *   reads it, and on Wi-Fi only while a multicast lock is held.
 */
class SolconPlayback(
    private val resolver: LiveResolver,
    private val multicastLock: MulticastLock,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(repository: SolconTvPlusRepository, multicastLock: MulticastLock) :
        this(LiveResolver(repository::resolveLive), multicastLock)

    /** Asks Solcon TV+ for a channel's stream right now — [SolconTvPlusRepository.resolveLive]. */
    fun interface LiveResolver {
        suspend fun resolve(reference: String): SolconTvPlusRepository.ResolvedPlayback
    }

    sealed interface Prepared {
        /** Hand [channel] to the tuner: it may now carry request headers, a licence or a multicast address. */
        data class Ready(val channel: ChannelEntity) : Prepared

        /** Nothing can play; [category] says why, for the message on screen and the account's status. */
        data class Failed(val category: SolconDiagnostics.ErrorCategory) : Prepared
    }

    /** A stream minted by [prepare], handed to the tuner's own resolve that follows it moments later. */
    private class Minted(val url: String, val atMs: Long)

    private val minted = ConcurrentHashMap<String, Minted>()

    /** True for the Solcon TV+ playlist, whose channels hold references rather than addresses. */
    fun ownsSource(source: SourceEntity?): Boolean = source?.url == SolconTvPlusRepository.SOURCE_URL

    suspend fun prepare(channel: ChannelEntity): Prepared {
        if (isMulticast(channel.streamUrl)) return Prepared.Ready(onMulticast(channel, channel.streamUrl))
        if (!isTvPlus(channel.streamUrl)) return Prepared.Ready(channel)
        return when (val resolved = resolver.resolve(channel.streamUrl)) {
            is SolconTvPlusRepository.ResolvedPlayback.Ready -> Prepared.Ready(
                if (isMulticast(resolved.url)) {
                    onMulticast(channel, resolved.url)
                } else {
                    minted[channel.streamUrl] = Minted(resolved.url, nowMs())
                    channel.copy(
                        httpHeaders = resolved.httpHeaders ?: channel.httpHeaders,
                        drmConfig = resolved.drmConfig,
                    )
                },
            )
            is SolconTvPlusRepository.ResolvedPlayback.Unsupported -> Prepared.Failed(resolved.category)
            is SolconTvPlusRepository.ResolvedPlayback.Failed -> Prepared.Failed(resolved.category)
        }
    }

    /**
     * The tuner's resolve hook for the Solcon playlist: the stream behind a TV+ reference, or the address
     * itself for anything else. Null when TV+ refuses — the tuner then fails the attempt like any other.
     */
    suspend fun resolveUrl(streamUrl: String): String? {
        if (!isTvPlus(streamUrl)) return streamUrl
        minted[streamUrl]?.takeIf { nowMs() - it.atMs < FRESH_MS }?.let { return it.url }
        val resolved = resolver.resolve(streamUrl) as? SolconTvPlusRepository.ResolvedPlayback.Ready ?: return null
        if (!isMulticast(resolved.url)) minted[streamUrl] = Minted(resolved.url, nowMs())
        return resolved.url
    }

    private fun onMulticast(channel: ChannelEntity, url: String): ChannelEntity {
        multicastLock.hold()
        return channel.copy(streamUrl = multicastUrl(url))
    }

    companion object {
        /**
         * How long a stream minted for a tune is reused by the tuner's resolves that follow it. Long enough
         * to cover the tune's first attempt; an engine handover or reconnect after it asks TV+ again.
         */
        const val FRESH_MS = 20_000L

        fun isTvPlus(url: String): Boolean = SolconStreamPolicy.classify(url).isTvPlus

        fun isMulticast(url: String): Boolean = SolconStreamPolicy.classify(url).isMulticast

        /**
         * Core's recorder downloads the stored address over HTTP. A TV+ reference is not an address and
         * multicast is not HTTP, so Record is not offered for either rather than failing once it starts.
         */
        fun canRecord(url: String): Boolean = SolconStreamPolicy.classify(url).let { !it.isTvPlus && !it.isMulticast }

        /**
         * The address mpv opens. For raw UDP, FFmpeg stops the stream at the first receive-buffer overrun
         * (a hiccup while the decoder starts is enough); `overrun_nonfatal` makes that a dropped packet.
         */
        fun multicastUrl(url: String): String {
            val trimmed = url.trim()
            if (!trimmed.startsWith(UDP_SCHEME, ignoreCase = true)) return trimmed
            if (trimmed.contains(OVERRUN_OPTION, ignoreCase = true)) return trimmed
            val separator = if ('?' in trimmed) '&' else '?'
            return "$trimmed$separator$OVERRUN_OPTION=1"
        }

        private const val UDP_SCHEME = "udp://"
        private const val OVERRUN_OPTION = "overrun_nonfatal"
    }
}

/** Keeps multicast packets arriving over Wi-Fi, which Android filters out unless something holds a lock. */
fun interface MulticastLock {
    fun hold()
}
