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
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The OwnTV tables the Solcon playlist lives in, as the few operations a sync needs. The app keeps them in
 * Room ([RoomSolconCatalogStore]); tests keep them in memory, so a whole sync can be checked on the JVM.
 */
interface SolconCatalogStore {
    /** The profile in use; negative before there is one. */
    suspend fun activeProfileId(): Long

    suspend fun sourceByUrl(url: String): SourceEntity?

    suspend fun insertSource(source: SourceEntity): Long

    suspend fun updateSource(source: SourceEntity)

    suspend fun linkSource(profileId: Long, sourceId: Long)

    suspend fun markSynced(sourceId: Long, atMs: Long)

    suspend fun liveCategories(sourceId: Long, remoteIds: List<String>): List<CategoryEntity>

    suspend fun insertCategories(rows: List<CategoryEntity>)

    suspend fun updateCategories(rows: List<CategoryEntity>)

    suspend fun channelsByRemoteId(sourceId: Long, remoteIds: List<String>): List<ChannelEntity>

    suspend fun channelRemoteIds(sourceId: Long): List<String>

    /** Inserts [rows]; a row carrying an existing id replaces that row and keeps its id. */
    suspend fun upsertChannels(rows: List<ChannelEntity>)

    suspend fun deleteChannels(sourceId: Long, remoteIds: List<String>)

    /** The source's guide channels become exactly [rows]. */
    suspend fun replaceGuideChannels(sourceId: Long, rows: List<EpgChannelEntity>)

    /** The programmes of [epgChannelIds], and only theirs, become [rows]. */
    suspend fun replaceProgrammes(sourceId: Long, epgChannelIds: List<String>, rows: List<EpgProgrammeEntity>)

    /** Drops the source's programmes that ended by [fromMs] or start at [toMs] or later. */
    suspend fun pruneProgrammes(sourceId: Long, fromMs: Long, toMs: Long)
}

class RoomSolconCatalogStore(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
) : SolconCatalogStore {
    override suspend fun activeProfileId(): Long = settings.activeProfileIdNow()

    override suspend fun sourceByUrl(url: String): SourceEntity? = sourceDao.getAllOnce().firstOrNull { it.url == url }

    override suspend fun insertSource(source: SourceEntity): Long = sourceDao.insert(source)

    override suspend fun updateSource(source: SourceEntity) = sourceDao.update(source)

    override suspend fun linkSource(profileId: Long, sourceId: Long) =
        sourceDao.link(ProfileSourceCrossRef(profileId = profileId, sourceId = sourceId))

    override suspend fun markSynced(sourceId: Long, atMs: Long) = sourceDao.markSynced(sourceId, atMs)

    override suspend fun liveCategories(sourceId: Long, remoteIds: List<String>): List<CategoryEntity> =
        categoryDao.findByRemoteIds(sourceId, MediaType.LIVE, remoteIds)

    override suspend fun insertCategories(rows: List<CategoryEntity>) {
        categoryDao.insertAll(rows)
    }

    override suspend fun updateCategories(rows: List<CategoryEntity>) = categoryDao.updateAll(rows)

    override suspend fun channelsByRemoteId(sourceId: Long, remoteIds: List<String>): List<ChannelEntity> =
        channelDao.findByRemoteIds(sourceId, remoteIds)

    override suspend fun channelRemoteIds(sourceId: Long): List<String> = channelDao.remoteIdsForSource(sourceId)

    override suspend fun upsertChannels(rows: List<ChannelEntity>) = channelDao.upsertAll(rows)

    override suspend fun deleteChannels(sourceId: Long, remoteIds: List<String>) = channelDao.deleteByRemoteIds(sourceId, remoteIds)

    override suspend fun replaceGuideChannels(sourceId: Long, rows: List<EpgChannelEntity>) {
        epgDao.clearChannelsForSource(sourceId)
        epgDao.upsertChannels(rows)
    }

    override suspend fun replaceProgrammes(sourceId: Long, epgChannelIds: List<String>, rows: List<EpgProgrammeEntity>) {
        epgDao.deleteProgrammesForChannels(sourceId, epgChannelIds)
        if (rows.isNotEmpty()) epgDao.upsertProgrammes(rows)
    }

    override suspend fun pruneProgrammes(sourceId: Long, fromMs: Long, toMs: Long) = epgDao.pruneOutsideWindow(sourceId, fromMs, toMs)
}
