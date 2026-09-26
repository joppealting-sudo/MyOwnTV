package tv.own.owntv.provider.solcon.tvplus

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.drm.DrmConfig
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.FailureDetail
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.PlaybackRoute
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.Step
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusProtocol.EpgEntry
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusProtocol.LiveChannel
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusProtocol.Playback
import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback

/** A whole sync, sign-out and tune against an in-memory playlist, as far as it can be checked without Solcon. */
class SolconTvPlusRepositoryTest {
    /** Room's tables as lists. Upserts behave like the channel DAO's REPLACE: a clash drops the old row first. */
    private class MemoryStore : SolconCatalogStore {
        var activeProfile = 1L
        val sources = mutableListOf<SourceEntity>()
        val links = mutableSetOf<Pair<Long, Long>>()
        val categories = mutableListOf<CategoryEntity>()
        val channels = mutableListOf<ChannelEntity>()
        val guideChannels = mutableListOf<EpgChannelEntity>()
        val programmes = mutableListOf<EpgProgrammeEntity>()
        private var nextId = 100L

        override suspend fun activeProfileId() = activeProfile

        override suspend fun sourceByUrl(url: String) = sources.firstOrNull { it.url == url }

        override suspend fun insertSource(source: SourceEntity): Long = nextId++.also { sources += source.copy(id = it) }

        override suspend fun updateSource(source: SourceEntity) {
            sources.replaceAll { if (it.id == source.id) source else it }
        }

        override suspend fun linkSource(profileId: Long, sourceId: Long) {
            links += profileId to sourceId
        }

        override suspend fun markSynced(sourceId: Long, atMs: Long) {
            sources.replaceAll { if (it.id == sourceId) it.copy(lastSyncAt = atMs) else it }
        }

        override suspend fun liveCategories(sourceId: Long, remoteIds: List<String>) =
            categories.filter { it.sourceId == sourceId && it.mediaType == MediaType.LIVE && it.remoteId in remoteIds }

        override suspend fun insertCategories(rows: List<CategoryEntity>) {
            rows.forEach { categories += it.copy(id = nextId++) }
        }

        override suspend fun updateCategories(rows: List<CategoryEntity>) {
            rows.forEach { row -> categories.replaceAll { if (it.id == row.id) row else it } }
        }

        override suspend fun channelsByRemoteId(sourceId: Long, remoteIds: List<String>) =
            channels.filter { it.sourceId == sourceId && it.remoteId in remoteIds }

        override suspend fun channelRemoteIds(sourceId: Long) = channels.filter { it.sourceId == sourceId }.mapNotNull { it.remoteId }

        override suspend fun upsertChannels(rows: List<ChannelEntity>) {
            rows.forEach { row ->
                channels.removeAll { (row.id != 0L && it.id == row.id) || (it.sourceId == row.sourceId && it.remoteId == row.remoteId) }
                channels += if (row.id == 0L) row.copy(id = nextId++) else row
            }
        }

        override suspend fun deleteChannels(sourceId: Long, remoteIds: List<String>) {
            channels.removeAll { it.sourceId == sourceId && it.remoteId in remoteIds }
        }

        override suspend fun replaceGuideChannels(sourceId: Long, rows: List<EpgChannelEntity>) {
            guideChannels.removeAll { it.sourceId == sourceId }
            guideChannels += rows
        }

        override suspend fun replaceProgrammes(sourceId: Long, epgChannelIds: List<String>, rows: List<EpgProgrammeEntity>) {
            programmes.removeAll { it.sourceId == sourceId && it.epgChannelId in epgChannelIds }
            programmes += rows
        }

        override suspend fun pruneProgrammes(sourceId: Long, fromMs: Long, toMs: Long) {
            programmes.removeAll { it.sourceId == sourceId && (it.stopMs <= fromMs || it.startMs >= toMs) }
        }
    }

    /** Solcon as a few answers set by each test. */
    private class FakeSolcon : SolconTvPlusApi {
        override var isLoggedIn = true
        override val lastDiscoveryResult: SolconDiagnostics.DiscoveryResult? = null
        var channels: Result<List<LiveChannel>> = Result.success(emptyList())
        val guide = mutableMapOf<String, List<EpgEntry>>()

        /** A guide request that includes one of these channels fails as a whole, like a real batch would. */
        var guideDownFor = emptySet<String>()
        var playback: (channelId: String, assetId: String?) -> Playback = { _, _ -> error("no playback answer set") }
        val playbackAsked = mutableListOf<Pair<String, String?>>()

        override suspend fun login(subscriptionNumber: String, pin: String): SolconTvPlusClient.LoginResult = error("not used")

        override suspend fun liveChannels() =
            if (isLoggedIn) channels else Result.failure(SolconTvPlusClient.NotAuthenticatedException())

        override suspend fun epg(startMs: Long, endMs: Long, channelIds: Collection<String>): Result<List<EpgEntry>> =
            if (channelIds.any { it in guideDownFor }) {
                Result.failure(IOException("guide unavailable"))
            } else {
                Result.success(channelIds.flatMap { guide[it].orEmpty() })
            }

        override suspend fun resolveLivePlayback(channelId: String, assetId: String?): Result<Playback> {
            playbackAsked += channelId to assetId
            if (!isLoggedIn) return Result.failure(SolconTvPlusClient.NotAuthenticatedException())
            return Result.success(playback(channelId, assetId))
        }

        override fun logout() {
            isLoggedIn = false
        }
    }

    private val store = MemoryStore()
    private val solcon = FakeSolcon()
    private val diagnostics = SolconDiagnostics.inMemory()
    private val repository = SolconTvPlusRepository(solcon, store, diagnostics)
    private val now = System.currentTimeMillis()

    private fun tv(id: String, name: String = "Channel $id", assetId: String? = null) =
        LiveChannel(id = id, name = name, number = id.toInt(), epgId = null, logoUrl = "https://img.test/$id.png", order = id.toInt(), radio = false, assetId = assetId)

    private fun radio(id: String) =
        LiveChannel(id = id, name = "Radio $id", number = id.toInt(), epgId = null, logoUrl = null, order = id.toInt(), radio = true, assetId = null)

    private fun show(channelId: String, title: String, startInMinutes: Long) = EpgEntry(
        id = "$channelId-$title", channelId = channelId, title = title, description = null,
        startMs = now + startInMinutes * 60_000, endMs = now + (startInMinutes + 30) * 60_000, assetId = null,
    )

    private suspend fun sync(profileId: Long? = null) = repository.sync("Solcon TV+", "Solcon TV", "Radio", profileId)

    private fun solconSourceId() = store.sources.single { it.url == SolconTvPlusRepository.SOURCE_URL }.id

    private fun channel(remoteId: String) = store.channels.single { it.sourceId == solconSourceId() && it.remoteId == remoteId }

    @Test
    fun `a first sync adds the playlist to the profile, with TV and radio in their own categories and the guide filled`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1", "NPO 1"), radio("901")))
        solcon.guide["1"] = listOf(show("1", "Journaal", 0), show("1", "Nieuwsuur", 30))

        val summary = sync(profileId = 5).getOrThrow()

        val source = store.sources.single()
        assertEquals(SolconTvPlusRepository.SOURCE_URL, source.url)
        assertEquals("Solcon TV+", source.name)
        assertTrue(source.syncLive)
        assertFalse(source.syncMovies || source.syncSeries)
        assertNotNull(source.lastSyncAt)
        assertEquals(setOf(5L to source.id), store.links)

        val categoryOf = store.categories.associate { it.id to it.name }
        assertEquals("Solcon TV", categoryOf[channel("1").categoryId])
        assertEquals("Radio", categoryOf[channel("901").categoryId])
        assertEquals("NPO 1", channel("1").name)
        assertEquals("solcon-tvplus://live/1", channel("1").streamUrl)

        assertEquals(2, store.guideChannels.size)
        assertEquals(listOf("Journaal", "Nieuwsuur"), store.programmes.sortedBy { it.startMs }.map { it.title })
        assertEquals(SolconTvPlusRepository.SyncSummary(channels = 2, radioChannels = 1, programmes = 2, epgComplete = true), summary)
        with(diagnostics.state.value) {
            assertEquals(1, tvChannels)
            assertEquals(1, radioChannels)
            assertEquals(true, epgComplete)
        }
    }

    @Test
    fun `without a profile it joins the active one, and with no profile at all nothing is written`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1")))
        store.activeProfile = 3
        sync().getOrThrow()
        assertEquals(setOf(3L to solconSourceId()), store.links)

        val fresh = MemoryStore().apply { activeProfile = -1 }
        val failure = SolconTvPlusRepository(solcon, fresh, SolconDiagnostics.inMemory())
            .sync("Solcon TV+", "Solcon TV", "Radio")
            .exceptionOrNull()
        assertTrue(failure is SolconTvPlusRepository.NoProfileException)
        assertTrue(fresh.sources.isEmpty() && fresh.channels.isEmpty())
    }

    @Test
    fun `a re-sync keeps every channel's row and removes only this playlist's channels that are gone`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1", "NPO 1"), tv("2"), tv("3")))
        sync().getOrThrow()
        val firstIds = listOf("1", "2").associateWith { channel(it).id }
        // Another playlist's channel that happens to share a provider id.
        store.channels += ChannelEntity(id = 7, sourceId = 999, name = "Elsewhere", streamUrl = "https://example.test/3", remoteId = "3")

        solcon.channels = Result.success(listOf(tv("1", "NPO 1 HD"), tv("2"), tv("4")))
        sync().getOrThrow()

        assertEquals(firstIds, listOf("1", "2").associateWith { channel(it).id })
        assertEquals("NPO 1 HD", channel("1").name)
        assertTrue(store.channels.none { it.sourceId == solconSourceId() && it.remoteId == "3" })
        assertTrue(store.channels.any { it.sourceId == 999L && it.remoteId == "3" })
        assertEquals(setOf("1", "2", "4"), store.channels.filter { it.sourceId == solconSourceId() }.mapNotNull { it.remoteId }.toSet())
        assertEquals(1, store.sources.size)
        assertEquals(2, store.categories.size)
    }

    @Test
    fun `a guide request that fails keeps that part of the guide and marks it partial`() = runBlocking {
        // Twelve channels: the guide is asked for in two requests, channels 1-10 and 11-12.
        val lineup = (1..12).map { tv("$it") }
        solcon.channels = Result.success(lineup)
        lineup.forEach { solcon.guide[it.id] = listOf(show(it.id, "Old", 0)) }
        sync().getOrThrow()

        lineup.forEach { solcon.guide[it.id] = listOf(show(it.id, "New", 0)) }
        solcon.guideDownFor = setOf("11")
        val summary = sync().getOrThrow()

        val titleFor = store.programmes.associate { it.epgChannelId to it.title }
        assertEquals("New", titleFor["1"])
        assertEquals("New", titleFor["10"])
        assertEquals("Old", titleFor["11"])
        assertEquals("Old", titleFor["12"])
        assertFalse(summary.epgComplete)
        assertEquals(false, diagnostics.state.value.epgComplete)
    }

    @Test
    fun `a failed channel list keeps the last good playlist and says where it failed`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1"), tv("2")))
        sync().getOrThrow()

        solcon.channels = Result.failure(SolconTvPlusClient.HttpStatusException(503))
        assertTrue(sync().isFailure)

        assertEquals(2, store.channels.size)
        assertEquals(ErrorCategory.NETWORK, diagnostics.state.value.lastError)
        assertEquals(FailureDetail(Step.CHANNELS, httpStatus = 503), diagnostics.state.value.lastFailure)
    }

    @Test
    fun `an empty channel list is refused instead of emptying the playlist`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1")))
        sync().getOrThrow()

        solcon.channels = Result.success(emptyList())
        assertTrue(sync().exceptionOrNull() is SolconTvPlusRepository.EmptyCatalogException)

        assertEquals(1, store.channels.size)
        assertEquals(ErrorCategory.EMPTY_CATALOG, diagnostics.state.value.lastError)
    }

    @Test
    fun `a background refresh needs a playlist and a session, and adds the playlist to no other profile`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1")))
        assertNull(repository.refreshInBackground("Solcon TV+", "Solcon TV", "Radio"))

        sync(profileId = 5).getOrThrow()
        store.activeProfile = 8
        assertTrue(repository.refreshInBackground("Solcon TV+", "Solcon TV", "Radio")!!.isSuccess)
        assertEquals(setOf(5L to solconSourceId()), store.links)

        repository.logout()
        assertNull(repository.refreshInBackground("Solcon TV+", "Solcon TV", "Radio"))
    }

    @Test
    fun `signing out keeps the playlist, and its channels then ask to sign in again`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1")))
        sync().getOrThrow()

        repository.logout()

        assertEquals(1, store.sources.size)
        assertEquals(1, store.channels.size)
        assertFalse(diagnostics.state.value.sessionAuthenticated)
        val answer = repository.resolveLive(channel("1").streamUrl)
        assertEquals(ErrorCategory.NOT_AUTHENTICATED, (answer as ResolvedPlayback.Failed).category)
    }

    @Test
    fun `a channel's own stream asset is asked for, and left out when Solcon refuses it`() = runBlocking {
        solcon.channels = Result.success(listOf(tv("1", assetId = "A-77")))
        sync().getOrThrow()
        assertEquals("solcon-tvplus://live/1/A-77", channel("1").streamUrl)

        solcon.playback = { _, assetId ->
            if (assetId != null) Playback.Error("unknown asset", code = "ASSET_NOT_FOUND") else Playback.Clear("https://cdn.test/1.mpd")
        }
        val answer = repository.resolveLive(channel("1").streamUrl)

        assertEquals("https://cdn.test/1.mpd", (answer as ResolvedPlayback.Ready).url)
        assertEquals(listOf("1" to "A-77", "1" to null), solcon.playbackAsked)
    }

    @Test
    fun `each kind of stream Solcon hands out becomes the matching player input`() = runBlocking {
        solcon.playback = { channelId, _ ->
            when (channelId) {
                "1" -> Playback.Clear("https://cdn.test/1.m3u8", mapOf("Referer" to "https://tv.test"))
                "2" -> Playback.Clear("rtp://@239.1.1.1:5000")
                "3" -> Playback.Widevine("https://cdn.test/3.mpd", "https://license.test/wv", licenseHeaders = mapOf("X-Session" to "s"))
                else -> Playback.UnsupportedProtected("PlayReady only")
            }
        }

        val clear = repository.resolveLive("solcon-tvplus://live/1") as ResolvedPlayback.Ready
        assertEquals("https://cdn.test/1.m3u8", clear.url)
        assertEquals(StreamHeaders.encode(mapOf("Referer" to "https://tv.test")), clear.httpHeaders)
        assertNull(clear.drmConfig)
        assertEquals(PlaybackRoute.CLEAR_HTTP, diagnostics.state.value.lastPlaybackRoute)

        assertTrue(repository.resolveLive("solcon-tvplus://live/2") is ResolvedPlayback.Ready)
        assertEquals(PlaybackRoute.MULTICAST, diagnostics.state.value.lastPlaybackRoute)

        val protectedStream = repository.resolveLive("solcon-tvplus://live/3") as ResolvedPlayback.Ready
        val drm = DrmConfig.decode(protectedStream.drmConfig)!!
        assertEquals(DrmConfig.Scheme.WIDEVINE, drm.scheme)
        assertEquals("https://license.test/wv", drm.licenseUrl)
        assertEquals(mapOf("X-Session" to "s"), drm.headers)
        assertEquals(PlaybackRoute.WIDEVINE, diagnostics.state.value.lastPlaybackRoute)

        val unsupported = repository.resolveLive("solcon-tvplus://live/4")
        assertEquals(ErrorCategory.PROTECTED_UNSUPPORTED, (unsupported as ResolvedPlayback.Unsupported).category)

        val malformed = repository.resolveLive("solcon-tvplus://live/not-a-channel")
        assertEquals(ErrorCategory.PROTOCOL, (malformed as ResolvedPlayback.Failed).category)
    }
}
