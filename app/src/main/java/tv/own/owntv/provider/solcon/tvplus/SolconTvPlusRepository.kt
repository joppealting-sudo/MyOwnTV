package tv.own.owntv.provider.solcon.tvplus

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.drm.DrmConfig
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.provider.solcon.SolconStreamPolicy
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.FailureDetail
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.Step

/**
 * Solcon TV+ as an ordinary OwnTV playlist: its channels, categories and guide live in the normal tables,
 * linked to the profile like any other source, so Live TV, the Guide, favourites and history need nothing
 * of their own. Signed playback links stay ephemeral and are never written to Room.
 */
class SolconTvPlusRepository(
    private val client: SolconTvPlusApi,
    private val store: SolconCatalogStore,
    private val diagnostics: SolconDiagnostics,
) {
    class EmptyCatalogException : IllegalStateException("Solcon TV+ returned no subscribed channels")

    class NoProfileException : IllegalStateException("No profile to add Solcon TV+ to")

    data class SyncSummary(
        val channels: Int,
        val radioChannels: Int,
        val programmes: Int,
        val epgComplete: Boolean,
    )

    sealed interface ResolvedPlayback {
        data class Ready(
            val url: String,
            val httpHeaders: String?,
            val drmConfig: String?,
        ) : ResolvedPlayback
        data class Unsupported(
            val category: ErrorCategory,
            val reason: String,
        ) : ResolvedPlayback
        data class Failed(
            val category: ErrorCategory,
            val reason: String,
        ) : ResolvedPlayback
    }

    val diagnosticsState = diagnostics.state

    /** One sync at a time: a background refresh and a press of "Refresh" must not interleave writes. */
    private val syncLock = Mutex()

    init {
        diagnostics.setSessionAuthenticated(client.isLoggedIn)
    }

    fun isLoggedIn(): Boolean = client.isLoggedIn

    suspend fun login(subscriptionNumber: String, pin: String): SolconTvPlusClient.LoginResult {
        val result = client.login(subscriptionNumber, pin)
        when (result) {
            is SolconTvPlusClient.LoginResult.Success -> {
                diagnostics.setSessionAuthenticated(true)
                client.lastDiscoveryResult?.let(diagnostics::recordDiscovery)
            }
            is SolconTvPlusClient.LoginResult.Failure -> {
                diagnostics.setSessionAuthenticated(false)
                diagnostics.recordError(result.reason.toDiagnosticsCategory(), result.detail)
            }
        }
        return result
    }

    fun logout() {
        client.logout()
        diagnostics.setSessionAuthenticated(false)
    }

    /**
     * Fetch the subscribed channels and their guide into OwnTV, as the Solcon playlist of [profileId] (the
     * active profile when null). Re-syncing keeps each channel's row — and with it favourites and history —
     * by its provider id. A guide request that fails keeps what that part of the guide had before.
     */
    suspend fun sync(
        sourceName: String,
        tvCategoryName: String,
        radioCategoryName: String,
        profileId: Long? = null,
    ): Result<SyncSummary> = syncLock.withLock {
        syncLocked(sourceName, tvCategoryName, radioCategoryName, addTo = { profileId?.takeIf { it >= 0 } ?: activeProfileOrThrow() })
    }

    /**
     * Refresh the Solcon playlist already added, in the background, adding it to no profile it is not
     * already on. Null when there is nothing to refresh — never added, or signed out.
     */
    suspend fun refreshInBackground(sourceName: String, tvCategoryName: String, radioCategoryName: String): Result<SyncSummary>? =
        syncLock.withLock {
            if (!client.isLoggedIn || existingSourceId() == null) return@withLock null
            syncLocked(sourceName, tvCategoryName, radioCategoryName, addTo = { null })
        }

    private suspend fun activeProfileOrThrow(): Long =
        store.activeProfileId().takeIf { it >= 0 } ?: throw NoProfileException()

    private suspend fun syncLocked(
        sourceName: String,
        tvCategoryName: String,
        radioCategoryName: String,
        addTo: suspend () -> Long?,
    ): Result<SyncSummary> {
        val result = runCatching {
            val channels = client.liveChannels().getOrThrow()
            if (channels.isEmpty()) throw EmptyCatalogException()
            val sourceId = ensureSource(addTo(), sourceName)
            val categoryIds = ensureCategories(sourceId, tvCategoryName, radioCategoryName)

            val existing = store.channelsByRemoteId(sourceId, channels.map { it.id })
                .mapNotNull { row -> row.remoteId?.let { it to row.id } }
                .toMap()
            store.upsertChannels(
                channelRows(channels, sourceId, tvCategoryId = categoryIds.getValue(TV_CATEGORY), radioCategoryId = categoryIds.getValue(RADIO_CATEGORY), existingIds = existing),
            )
            val stale = store.channelRemoteIds(sourceId).toSet() - channels.map { it.id }.toSet()
            if (stale.isNotEmpty()) store.deleteChannels(sourceId, stale.toList())

            store.replaceGuideChannels(
                sourceId,
                channels.map { item ->
                    EpgChannelEntity(sourceId = sourceId, epgChannelId = epgKey(item), displayName = item.name, iconUrl = item.logoUrl)
                },
            )
            val guide = syncGuide(sourceId, channels)
            store.markSynced(sourceId, System.currentTimeMillis())
            SyncSummary(
                channels = channels.size,
                radioChannels = channels.count { it.radio },
                programmes = guide.programmes,
                epgComplete = guide.complete,
            )
        }
        result.onSuccess { summary ->
            diagnostics.recordSync(
                tvChannels = summary.channels - summary.radioChannels,
                radioChannels = summary.radioChannels,
                programmes = summary.programmes,
                epgComplete = summary.epgComplete,
            )
        }.onFailure { failure ->
            diagnostics.recordError(
                when (failure) {
                    is EmptyCatalogException -> ErrorCategory.EMPTY_CATALOG
                    is SolconTvPlusClient.NotAuthenticatedException -> ErrorCategory.NOT_AUTHENTICATED
                    is SolconTvPlusClient.HttpStatusException, is java.io.IOException -> ErrorCategory.NETWORK
                    else -> ErrorCategory.PROTOCOL
                },
                FailureDetail(Step.CHANNELS, (failure as? SolconTvPlusClient.HttpStatusException)?.status),
            )
        }
        return result
    }

    private class GuideResult(val programmes: Int, val complete: Boolean)

    private suspend fun syncGuide(sourceId: Long, channels: List<SolconTvPlusProtocol.LiveChannel>): GuideResult {
        val now = System.currentTimeMillis()
        val start = now - EPG_BACK_MS
        val end = now + EPG_FORWARD_MS
        var programmes = 0
        var complete = true
        for (batch in channels.chunked(EPG_CHANNEL_BATCH)) {
            val entries = client.epg(start, end, batch.map { it.id }).getOrElse {
                complete = false
                continue
            }
            val rows = programmeRows(entries, sourceId, channels)
            // Only this batch's channels are replaced, and only once their new guide is in hand.
            store.replaceProgrammes(sourceId, batch.map { epgKey(it) }, rows)
            programmes += rows.size
        }
        store.pruneProgrammes(sourceId, start, end)
        return GuideResult(programmes, complete)
    }

    /** The stream behind a channel's `solcon-tvplus://live/<id>` [reference], asked for right now. */
    suspend fun resolveLive(reference: String): ResolvedPlayback {
        val live = SolconStreamPolicy.tvPlusLive(reference) ?: run {
            diagnostics.recordError(ErrorCategory.PROTOCOL, FailureDetail(Step.PLAYBACK))
            return ResolvedPlayback.Failed(ErrorCategory.PROTOCOL, "Invalid Solcon TV+ channel reference")
        }
        var answer = client.resolveLivePlayback(live.channelId, live.assetId)
        // The asset is read from the channel list by best guess; when Solcon refuses it, ask by the channel alone.
        if (live.assetId != null && answer.getOrNull() is SolconTvPlusProtocol.Playback.Error) {
            answer = client.resolveLivePlayback(live.channelId, null)
        }
        return answer.fold(
            onSuccess = { playback ->
                when (playback) {
                    is SolconTvPlusProtocol.Playback.Clear -> {
                        diagnostics.recordPlaybackRoute(
                            if (SolconStreamPolicy.classify(playback.url).isMulticast) {
                                SolconDiagnostics.PlaybackRoute.MULTICAST
                            } else {
                                SolconDiagnostics.PlaybackRoute.CLEAR_HTTP
                            },
                        )
                        ResolvedPlayback.Ready(
                            url = playback.url,
                            httpHeaders = StreamHeaders.encode(playback.streamHeaders),
                            drmConfig = null,
                        )
                    }
                    is SolconTvPlusProtocol.Playback.Widevine -> {
                        diagnostics.recordPlaybackRoute(SolconDiagnostics.PlaybackRoute.WIDEVINE)
                        ResolvedPlayback.Ready(
                            url = playback.url,
                            httpHeaders = StreamHeaders.encode(playback.streamHeaders),
                            drmConfig = DrmConfig.encode(
                                DrmConfig(
                                    scheme = DrmConfig.Scheme.WIDEVINE,
                                    licenseUrl = playback.licenseUrl,
                                    headers = playback.licenseHeaders,
                                ),
                            ),
                        )
                    }
                    is SolconTvPlusProtocol.Playback.UnsupportedProtected -> {
                        diagnostics.recordError(ErrorCategory.PROTECTED_UNSUPPORTED, FailureDetail(Step.PLAYBACK))
                        ResolvedPlayback.Unsupported(ErrorCategory.PROTECTED_UNSUPPORTED, playback.reason)
                    }
                    is SolconTvPlusProtocol.Playback.Error -> {
                        diagnostics.recordError(ErrorCategory.PLAYBACK, FailureDetail(Step.PLAYBACK, providerCode = playback.code))
                        ResolvedPlayback.Failed(ErrorCategory.PLAYBACK, playback.reason)
                    }
                }
            },
            onFailure = { failure ->
                val category = if (failure is SolconTvPlusClient.NotAuthenticatedException) {
                    ErrorCategory.NOT_AUTHENTICATED
                } else {
                    ErrorCategory.NETWORK
                }
                diagnostics.recordError(
                    category,
                    FailureDetail(Step.PLAYBACK, (failure as? SolconTvPlusClient.HttpStatusException)?.status),
                )
                ResolvedPlayback.Failed(category, "Solcon TV+ playback request failed")
            },
        )
    }

    suspend fun existingSourceId(): Long? = store.sourceByUrl(SOURCE_URL)?.id

    /** The Solcon playlist's id, created on first use and added to [profileId] when one is given. */
    private suspend fun ensureSource(profileId: Long?, sourceName: String): Long {
        val existing = store.sourceByUrl(SOURCE_URL)
        val id = if (existing != null) {
            if (existing.name != sourceName) store.updateSource(existing.copy(name = sourceName))
            existing.id
        } else {
            store.insertSource(
                SourceEntity(
                    name = sourceName,
                    type = SourceType.M3U,
                    url = SOURCE_URL,
                    syncLive = true,
                    syncMovies = false,
                    syncSeries = false,
                ),
            )
        }
        if (profileId != null) store.linkSource(profileId, id)
        return id
    }

    private suspend fun ensureCategories(sourceId: Long, tvName: String, radioName: String): Map<String, Long> {
        val desired = listOf(
            CategoryEntity(sourceId = sourceId, mediaType = MediaType.LIVE, name = tvName, remoteId = TV_CATEGORY, sortOrder = 0),
            CategoryEntity(sourceId = sourceId, mediaType = MediaType.LIVE, name = radioName, remoteId = RADIO_CATEGORY, sortOrder = 1),
        )
        val existing = store.liveCategories(sourceId, desired.mapNotNull { it.remoteId }).associateBy { it.remoteId }
        val toUpdate = desired.mapNotNull { row -> existing[row.remoteId]?.let { row.copy(id = it.id) } }
        val toInsert = desired.filter { it.remoteId !in existing }
        if (toUpdate.isNotEmpty()) store.updateCategories(toUpdate)
        if (toInsert.isNotEmpty()) store.insertCategories(toInsert)
        return store.liveCategories(sourceId, desired.mapNotNull { it.remoteId })
            .associate { requireNotNull(it.remoteId) to it.id }
    }

    private fun SolconTvPlusClient.FailureReason.toDiagnosticsCategory(): ErrorCategory =
        when (this) {
            SolconTvPlusClient.FailureReason.INVALID_CREDENTIALS -> ErrorCategory.INVALID_CREDENTIALS
            SolconTvPlusClient.FailureReason.DEVICE_LIMIT -> ErrorCategory.DEVICE_LIMIT
            SolconTvPlusClient.FailureReason.ACCOUNT_BLOCKED -> ErrorCategory.ACCOUNT_BLOCKED
            SolconTvPlusClient.FailureReason.REJECTED -> ErrorCategory.LOGIN_REJECTED
            SolconTvPlusClient.FailureReason.NOT_AUTHENTICATED -> ErrorCategory.NOT_AUTHENTICATED
            SolconTvPlusClient.FailureReason.NETWORK -> ErrorCategory.NETWORK
            SolconTvPlusClient.FailureReason.PROTOCOL -> ErrorCategory.PROTOCOL
        }

    companion object {
        const val SOURCE_URL = "solcon-tvplus://account"
        const val TVPLUS_LIVE_PREFIX = "solcon-tvplus://live/"
        private const val TV_CATEGORY = "solcon-tv"
        private const val RADIO_CATEGORY = "solcon-radio"
        private const val EPG_CHANNEL_BATCH = 10
        private const val EPG_BACK_MS = 7L * 24L * 60L * 60L * 1000L
        private const val EPG_FORWARD_MS = 2L * 24L * 60L * 60L * 1000L
        private val SAFE_ASSET = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /**
         * The stable reference a channel is stored under, never a signed link: `solcon-tvplus://live/<id>`,
         * plus `/<asset>` when Solcon lists a separate stream asset for the channel.
         */
        internal fun liveReference(channel: SolconTvPlusProtocol.LiveChannel): String {
            val asset = channel.assetId?.trim()?.takeIf { it != channel.id && SAFE_ASSET.matches(it) }
            return if (asset == null) "$TVPLUS_LIVE_PREFIX${channel.id}" else "$TVPLUS_LIVE_PREFIX${channel.id}/$asset"
        }

        /** The key a channel's guide is filed under: the provider's guide id, or its channel id. */
        internal fun epgKey(channel: SolconTvPlusProtocol.LiveChannel): String =
            (channel.epgId ?: channel.id).trim().lowercase()

        /**
         * The OwnTV rows for Solcon's [channels]. [existingIds] maps a provider id to the row it already has,
         * so a re-sync updates that row in place and favourites and history stay attached to it.
         */
        internal fun channelRows(
            channels: List<SolconTvPlusProtocol.LiveChannel>,
            sourceId: Long,
            tvCategoryId: Long,
            radioCategoryId: Long,
            existingIds: Map<String, Long>,
        ): List<ChannelEntity> = channels.map { item ->
            ChannelEntity(
                id = existingIds[item.id] ?: 0,
                sourceId = sourceId,
                categoryId = if (item.radio) radioCategoryId else tvCategoryId,
                name = item.name,
                logoUrl = item.logoUrl,
                streamUrl = liveReference(item),
                epgChannelId = epgKey(item),
                number = item.number,
                remoteId = item.id,
                sortOrder = item.order,
                // Solcon's replay is its own request flow, not an archive address OwnTV can build, so the
                // channel does not offer catch-up it cannot play.
                catchup = false,
            )
        }

        /** Guide rows for [entries], each filed under its channel's [epgKey]; unknown or empty slots dropped. */
        internal fun programmeRows(
            entries: List<SolconTvPlusProtocol.EpgEntry>,
            sourceId: Long,
            channels: List<SolconTvPlusProtocol.LiveChannel>,
        ): List<EpgProgrammeEntity> {
            val byRemote = channels.associateBy { it.id }
            val byEpg = channels.associateBy { epgKey(it) }
            return entries.mapNotNull { entry ->
                val channel = byRemote[entry.channelId] ?: byEpg[entry.channelId.trim().lowercase()] ?: return@mapNotNull null
                if (entry.endMs <= entry.startMs) return@mapNotNull null
                EpgProgrammeEntity(
                    sourceId = sourceId,
                    epgChannelId = epgKey(channel),
                    startMs = entry.startMs,
                    stopMs = entry.endMs,
                    title = entry.title,
                    description = entry.description,
                )
            }
        }
    }
}
