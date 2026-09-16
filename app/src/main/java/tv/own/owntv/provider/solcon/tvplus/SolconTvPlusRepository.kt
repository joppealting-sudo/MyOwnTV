package tv.own.owntv.provider.solcon.tvplus

import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.drm.DrmConfig
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.provider.solcon.SolconStreamPolicy

/** OwnTV-facing provider layer. Signed playback links stay ephemeral and are never written to Room. */
class SolconTvPlusRepository(
    private val client: SolconTvPlusClient,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    private val settings: SettingsRepository,
) {
    class EmptyCatalogException : IllegalStateException("Solcon TV+ returned no subscribed channels")

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
        data class Unsupported(val reason: String) : ResolvedPlayback
        data class Failed(val reason: String) : ResolvedPlayback
    }

    fun isLoggedIn(): Boolean = client.isLoggedIn

    suspend fun login(subscriptionNumber: String, pin: String): SolconTvPlusClient.LoginResult =
        client.login(subscriptionNumber, pin)

    fun logout() = client.logout()

    suspend fun sync(
        sourceName: String,
        tvCategoryName: String,
        radioCategoryName: String,
    ): Result<SyncSummary> = runCatching {
        val channels = client.liveChannels().getOrThrow()
        if (channels.isEmpty()) throw EmptyCatalogException()
        val profileId = settings.activeProfileIdNow()
        val sourceId = ensureSource(profileId, sourceName)
        val categoryIds = ensureCategories(sourceId, tvCategoryName, radioCategoryName)

        val existing = channelDao.findByRemoteIds(sourceId, channels.map { it.id }).associateBy { it.remoteId }
        val rows = channels.map { item ->
            val epgKey = (item.epgId ?: item.id).trim().lowercase()
            ChannelEntity(
                id = existing[item.id]?.id ?: 0,
                sourceId = sourceId,
                categoryId = if (item.radio) categoryIds.getValue(RADIO_CATEGORY) else categoryIds.getValue(TV_CATEGORY),
                name = item.name,
                logoUrl = item.logoUrl,
                streamUrl = "$TVPLUS_LIVE_PREFIX${item.id}",
                epgChannelId = epgKey,
                number = item.number,
                remoteId = item.id,
                sortOrder = item.order,
                catchup = item.catchupDays > 0,
                catchupDays = item.catchupDays,
            )
        }
        channelDao.upsertAll(rows)
        val stale = channelDao.remoteIdsForSource(sourceId).toSet() - channels.map { it.id }.toSet()
        if (stale.isNotEmpty()) channelDao.deleteByRemoteIds(sourceId, stale.toList())

        val epgChannels = channels.map { item ->
            EpgChannelEntity(
                sourceId = sourceId,
                epgChannelId = (item.epgId ?: item.id).trim().lowercase(),
                displayName = item.name,
                iconUrl = item.logoUrl,
            )
        }
        epgDao.clearSource(sourceId)
        epgDao.clearChannelsForSource(sourceId)
        epgDao.upsertChannels(epgChannels)

        val byRemote = channels.associateBy { it.id }
        val byEpg = channels.associateBy { (it.epgId ?: it.id).trim().lowercase() }
        val start = System.currentTimeMillis() - EPG_BACK_MS
        val end = System.currentTimeMillis() + EPG_FORWARD_MS
        var programmes = 0
        var epgComplete = true
        for (batch in channels.map { it.id }.chunked(EPG_CHANNEL_BATCH)) {
            val entries = client.epg(start, end, batch).getOrElse {
                epgComplete = false
                emptyList()
            }
            if (entries.isEmpty()) continue
            val programmeRows = entries.mapNotNull { entry ->
                val channel = byRemote[entry.channelId] ?: byEpg[entry.channelId.trim().lowercase()] ?: return@mapNotNull null
                val epgKey = (channel.epgId ?: channel.id).trim().lowercase()
                if (entry.endMs <= entry.startMs) return@mapNotNull null
                EpgProgrammeEntity(
                    sourceId = sourceId,
                    epgChannelId = epgKey,
                    startMs = entry.startMs,
                    stopMs = entry.endMs,
                    title = entry.title,
                    description = entry.description,
                )
            }
            if (programmeRows.isNotEmpty()) {
                epgDao.upsertProgrammes(programmeRows)
                programmes += programmeRows.size
            }
        }
        sourceDao.markSynced(sourceId, System.currentTimeMillis())
        SyncSummary(
            channels = channels.size,
            radioChannels = channels.count { it.radio },
            programmes = programmes,
            epgComplete = epgComplete,
        )
    }

    suspend fun resolveLive(channel: ChannelEntity): ResolvedPlayback {
        val id = SolconStreamPolicy.tvPlusLiveId(channel.streamUrl)
            ?: return ResolvedPlayback.Failed("Invalid Solcon TV+ channel reference")
        return client.resolveLivePlayback(id).fold(
            onSuccess = { playback ->
                when (playback) {
                    is SolconTvPlusProtocol.Playback.Clear -> ResolvedPlayback.Ready(
                        url = playback.url,
                        httpHeaders = StreamHeaders.encode(playback.streamHeaders),
                        drmConfig = null,
                    )
                    is SolconTvPlusProtocol.Playback.Widevine -> ResolvedPlayback.Ready(
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
                    is SolconTvPlusProtocol.Playback.UnsupportedProtected ->
                        ResolvedPlayback.Unsupported(playback.reason)
                    is SolconTvPlusProtocol.Playback.Error -> ResolvedPlayback.Failed(playback.reason)
                }
            },
            onFailure = { ResolvedPlayback.Failed(it.message ?: "Solcon TV+ playback request failed") },
        )
    }

    suspend fun existingSourceId(): Long? =
        sourceDao.getAllOnce().firstOrNull { it.url == SOURCE_URL }?.id

    private suspend fun ensureSource(profileId: Long, sourceName: String): Long {
        val existing = sourceDao.getAllOnce().firstOrNull { it.url == SOURCE_URL }
        val id = if (existing != null) {
            if (existing.name != sourceName) sourceDao.update(existing.copy(name = sourceName))
            existing.id
        } else {
            sourceDao.insert(
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
        sourceDao.link(ProfileSourceCrossRef(profileId = profileId, sourceId = id))
        return id
    }

    private suspend fun ensureCategories(sourceId: Long, tvName: String, radioName: String): Map<String, Long> {
        val desired = listOf(
            CategoryEntity(sourceId = sourceId, mediaType = MediaType.LIVE, name = tvName, remoteId = TV_CATEGORY, sortOrder = 0),
            CategoryEntity(sourceId = sourceId, mediaType = MediaType.LIVE, name = radioName, remoteId = RADIO_CATEGORY, sortOrder = 1),
        )
        val existing = categoryDao.findByRemoteIds(sourceId, MediaType.LIVE, desired.mapNotNull { it.remoteId }).associateBy { it.remoteId }
        val toUpdate = desired.mapNotNull { row -> existing[row.remoteId]?.let { row.copy(id = it.id) } }
        val toInsert = desired.filter { it.remoteId !in existing }
        if (toUpdate.isNotEmpty()) categoryDao.updateAll(toUpdate)
        if (toInsert.isNotEmpty()) categoryDao.insertAll(toInsert)
        return categoryDao.findByRemoteIds(sourceId, MediaType.LIVE, desired.mapNotNull { it.remoteId })
            .associate { requireNotNull(it.remoteId) to it.id }
    }

    companion object {
        const val SOURCE_URL = "solcon-tvplus://account"
        const val TVPLUS_LIVE_PREFIX = "solcon-tvplus://live/"
        private const val TV_CATEGORY = "solcon-tv"
        private const val RADIO_CATEGORY = "solcon-radio"
        private const val EPG_CHANNEL_BATCH = 10
        private const val EPG_BACK_MS = 7L * 24L * 60L * 60L * 1000L
        private const val EPG_FORWARD_MS = 2L * 24L * 60L * 60L * 1000L
    }
}