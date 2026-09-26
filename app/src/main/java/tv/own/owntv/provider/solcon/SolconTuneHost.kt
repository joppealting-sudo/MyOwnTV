package tv.own.owntv.provider.solcon

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.timeshift.TimeshiftManager
import tv.own.owntv.player.LiveTuneController

/**
 * Core's live tuner host with Solcon's two needs added, so every path the tuner drives — the preview
 * pane, full screen, Multiview tiles, engine handovers and reconnects — gets them from one place:
 *
 * - the Solcon TV+ playlist mints each stream just in time, the way a Stalker portal does;
 * - a multicast channel always opens on mpv, the only engine here that reads `rtp://` and `udp://`.
 *
 * Everything else is [delegate]'s answer.
 */
class SolconTuneHost(
    private val delegate: LiveTuneController.Host,
    private val solcon: SolconPlayback,
) : LiveTuneController.Host by delegate {

    override fun needsResolve(source: SourceEntity?): Boolean =
        solcon.ownsSource(source) || delegate.needsResolve(source)

    override suspend fun resolve(source: SourceEntity, cmd: String): String? =
        if (solcon.ownsSource(source)) solcon.resolveUrl(cmd) else delegate.resolve(source, cmd)

    override suspend fun enginePin(channel: ChannelEntity): Boolean? =
        if (SolconPlayback.isMulticast(channel.streamUrl)) true else delegate.enginePin(channel)

    // Spelled out rather than left to `by`: the interface gives these defaults, and the delegate's own
    // answers — the timeshift buffers above all — must never be swapped for them.
    override fun nowMs(): Long = delegate.nowMs()
    override fun onEngineStarted() = delegate.onEngineStarted()
    override fun onBackToLiveEdge() = delegate.onBackToLiveEdge()
    override val timeshift: TimeshiftManager? get() = delegate.timeshift
    override suspend fun timeshiftWindowMinutes(): Int? = delegate.timeshiftWindowMinutes()
    override suspend fun maxVideoHeight(): Int? = delegate.maxVideoHeight()
}
