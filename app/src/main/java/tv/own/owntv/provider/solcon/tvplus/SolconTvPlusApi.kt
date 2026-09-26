package tv.own.owntv.provider.solcon.tvplus

/**
 * What [SolconTvPlusRepository] asks of Solcon TV+: sign-in, the subscribed channels, their guide and a
 * channel's stream. [SolconTvPlusClient] asks Solcon; tests answer with a fake.
 */
interface SolconTvPlusApi {
    val isLoggedIn: Boolean
    val lastDiscoveryResult: SolconDiagnostics.DiscoveryResult?

    suspend fun login(subscriptionNumber: String, pin: String): SolconTvPlusClient.LoginResult

    suspend fun liveChannels(): Result<List<SolconTvPlusProtocol.LiveChannel>>

    suspend fun epg(startMs: Long, endMs: Long, channelIds: Collection<String>): Result<List<SolconTvPlusProtocol.EpgEntry>>

    /** The stream for [channelId]; [assetId] is the stream asset Solcon listed for the channel, if any. */
    suspend fun resolveLivePlayback(channelId: String, assetId: String?): Result<SolconTvPlusProtocol.Playback>

    fun logout()
}
