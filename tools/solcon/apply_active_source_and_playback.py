#!/usr/bin/env python3
"""Finish Solcon source activation and just-in-time live playback wiring.

Exact/idempotent edits only. Signed playback URLs remain runtime-only: Room keeps the stable
solcon-tvplus://live/<id> reference and the LiveViewModel receives an ephemeral ChannelEntity copy.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(path)
    if marker and marker in text:
        return
    if old not in text:
        raise SystemExit(f"Expected integration anchor missing in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Integration anchor is not unique in {path}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# A successful first sync should leave a fresh profile with an explicit active source, while never
# stealing an existing user's chosen default source.
repo = "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt"
replace_once(
    repo,
    "        sourceDao.markSynced(sourceId, System.currentTimeMillis())\n        SyncSummary(\n",
    "        sourceDao.markSynced(sourceId, System.currentTimeMillis())\n        if (settings.currentDefaultSourceId() == null) settings.setDefaultSource(sourceId)\n        SyncSummary(\n",
    marker="settings.setDefaultSource(sourceId)",
)

# Settings search is a separate catalogue from the visible root rows; keep Solcon reachable there too.
settings = "app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt"
search_anchor = "            SettingsSearchEntry(stringResource(R.string.settings_group_content_metadata), stringResource(R.string.settings_open_subtitles), stringResource(R.string.settings_search_keywords_subtitle_appearance), OwnTVIcon.SUBTITLE, TileTone.PRIMARY) { open(SettingsTab.OPEN_SUBTITLES) },\n"
replace_once(
    settings,
    search_anchor,
    search_anchor + "            SettingsSearchEntry(stringResource(R.string.settings_app_group), stringResource(SolconSettingsRoute.titleRes), stringResource(SolconSettingsRoute.descriptionRes), OwnTVIcon.LIVE_TV, TileTone.PRIMARY) { open(SettingsTab.SOLCON) },\n",
    marker="SolconSettingsRoute.descriptionRes), OwnTVIcon.LIVE_TV, TileTone.PRIMARY) { open(SettingsTab.SOLCON) }",
)

live = "app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt"

# Inject the provider repository into the central Live playback decision point.
replace_once(
    live,
    "    val multicastEngine: tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine,\n) : ViewModel() {\n",
    "    val multicastEngine: tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine,\n    private val solconTvPlusRepository: tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository,\n) : ViewModel() {\n",
    marker="private val solconTvPlusRepository:",
)

# Resolve only the synthetic Solcon reference. Clear/Widevine headers and DRM live on the ephemeral
# copy passed to the engines; Unsupported/Failed never fall through to a fake URI or mpv workaround.
play_helper_anchor = "    /** Internal playback: the canonical ExoPlayer / mpv / Stalker / history side-effects for a\n"
play_helper = """    /** Resolve a Solcon TV+ channel just before playback without persisting the signed result. */
    private suspend fun resolveSolconPlayback(channel: ChannelEntity): ChannelEntity? {
        if (!tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(channel.streamUrl).isTvPlus) return channel
        return when (val resolved = solconTvPlusRepository.resolveLive(channel)) {
            is tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback.Ready -> channel.copy(
                streamUrl = resolved.url,
                httpHeaders = resolved.httpHeaders,
                drmConfig = resolved.drmConfig,
            )
            is tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback.Unsupported -> {
                engineLog(resolved.reason)
                null
            }
            is tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback.Failed -> {
                engineLog(resolved.reason)
                null
            }
        }
    }

"""
replace_once(
    live,
    play_helper_anchor,
    play_helper + play_helper_anchor,
    marker="private suspend fun resolveSolconPlayback(channel: ChannelEntity)",
)

# Resolve before external-player routing, multicast classification, engine pinning, or DRM choice.
replace_once(
    live,
    """    private suspend fun playChannel(channel: ChannelEntity) {
        val pid = currentProfileId() ?: return
        if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao)) return
        // Live TV set to play externally: hand the channel over instead of tuning an in-app engine.
""",
    """    private suspend fun playChannel(originalChannel: ChannelEntity) {
        val pid = currentProfileId() ?: return
        if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(pid, originalChannel.categoryId, profileDao, categoryDao)) return
        val channel = resolveSolconPlayback(originalChannel) ?: return
        // Live TV set to play externally: hand the channel over instead of tuning an in-app engine.
""",
    marker="val channel = resolveSolconPlayback(originalChannel) ?: return",
)

# In-pane preview: resolve asynchronously, then re-enter the normal preview path with the real URL.
replace_once(
    live,
    """        if (_liveOnExo.value) return
        val source = sourceById[channel.sourceId]
""",
    """        if (_liveOnExo.value) return
        if (tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(channel.streamUrl).isTvPlus) {
            stalkerPreviewJob?.cancel()
            stalkerPreviewJob = viewModelScope.launch {
                val resolved = resolveSolconPlayback(channel) ?: return@launch
                if (_liveOnExo.value) return@launch
                playPreview(resolved)
            }
            return
        }
        val source = sourceById[channel.sourceId]
""",
    marker="playPreview(resolved)",
)

# Multiview uses independent engines, but the provider reference still needs the same fresh resolve.
replace_once(
    live,
    """    fun tuneTile(engine: tv.own.owntv.player.LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        val source = sourceById[channel.sourceId]
""",
    """    fun tuneTile(engine: tv.own.owntv.player.LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        if (tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(channel.streamUrl).isTvPlus) {
            viewModelScope.launch {
                val resolved = resolveSolconPlayback(channel) ?: return@launch
                tuneTile(engine, resolved, muted)
            }
            return
        }
        val source = sourceById[channel.sourceId]
""",
    marker="tuneTile(engine, resolved, muted)",
)

# The explicit "open externally" action bypasses playChannel, so resolve it here as well. Protected
# streams deliberately stay in-app because an Android VIEW intent has no standard DRM licence contract.
replace_once(
    live,
    """        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao)) return@launch
            val source = withContext(Dispatchers.IO) { sourceDao.getById(channel.sourceId) }
            val url = if (streamUrlResolver.needsResolve(source)) {
""",
    """        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao)) return@launch
            val playableChannel = resolveSolconPlayback(channel) ?: return@launch
            if (playableChannel.drmConfig != null) return@launch
            val source = withContext(Dispatchers.IO) { sourceDao.getById(playableChannel.sourceId) }
            val url = if (streamUrlResolver.needsResolve(source)) {
""",
    marker="val playableChannel = resolveSolconPlayback(channel) ?: return@launch",
)
# Complete the external path by using the ephemeral playback fields. Do the replacements only inside
# the playExternal block so unrelated channel code remains stable.
text = read(live)
start = text.index("    fun playExternal(channel: ChannelEntity) {")
end = text.index("\n    /** Go full-screen on [channel].", start)
block = text[start:end]
if "playableChannel.streamUrl" not in block:
    block = block.replace("streamUrlResolver.resolve(source!!, channel.streamUrl)", "streamUrlResolver.resolve(source!!, playableChannel.streamUrl)")
    block = block.replace("                channel.streamUrl\n", "                playableChannel.streamUrl\n")
    block = block.replace("                title = channel.name,", "                title = playableChannel.name,")
    block = block.replace("                httpHeaders = channel.httpHeaders,", "                httpHeaders = playableChannel.httpHeaders,")
    text = text[:start] + block + text[end:]
    write(live, text)

# LiveViewModel is over Koin's constructor-reference arity, so its explicit binding needs one more get().
app_module = "app/src/main/java/tv/own/owntv/di/AppModule.kt"
text = read(app_module)
if "solconTvPlusRepository = get()" not in text:
    start = text.index("    viewModel {\n        LiveViewModel(\n")
    end = text.index("        )\n    }\n    viewModelOf(::MovieViewModel)", start)
    block = text[start:end]
    last_get = block.rfind("            get(),\n")
    if last_get < 0:
        raise SystemExit("Could not find LiveViewModel Koin binding tail")
    insert_at = last_get + len("            get(),\n")
    block = block[:insert_at] + "            solconTvPlusRepository = get(),\n" + block[insert_at:]
    text = text[:start] + block + text[end:]
    text = text.replace("this class now has 23.", "this class now has 25.", 1)
    write(app_module, text)

print("Applied Solcon active-source, Settings search, and just-in-time playback integration.")
