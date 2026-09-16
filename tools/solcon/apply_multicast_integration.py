from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


def replace_all_checked(path: Path, old: str, new: str, expected: int) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new))


root = Path(__file__).resolve().parents[2]
vm = root / "app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt"
screen = root / "app/src/main/java/tv/own/owntv/features/live/LiveScreen.kt"
shell = root / "app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt"
app_module = root / "app/src/main/java/tv/own/owntv/di/AppModule.kt"

# Idempotent: the workflow's own patch commit triggers the workflow once more.
if "val multicastEngine: tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine" in vm.read_text():
    raise SystemExit(0)

replace_once(
    vm,
    "    private val recordings: tv.own.owntv.core.recording.RecordingManager,\n) : ViewModel() {",
    "    private val recordings: tv.own.owntv.core.recording.RecordingManager,\n"
    "    val multicastEngine: tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine,\n"
    ") : ViewModel() {",
)

replace_once(
    vm,
    "        val targetUrl = tuneUrl(channel, source)\n"
    "        // A one-session panel counts the muted preview as the account's single stream, so previewing while",
    "        val targetUrl = tuneUrl(channel, source)\n"
    "        val multicastDecision = tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(targetUrl)\n"
    "        if (multicastDecision.isMulticast) {\n"
    "            if (_liveOnMulticast.value) return\n"
    "            _previewBlockedSingleSession.value = false\n"
    "            previewEngine.stop()\n"
    "            _previewOnMulticast.value = true\n"
    "            val meta = tv.own.owntv.player.MediaMeta(\n"
    "                title = channel.name, subtitle = channelNumberLabel(channel),\n"
    "                logoUrl = channel.displayLogoUrl, contentKey = mpvPinKey(channel),\n"
    "            )\n"
    "            if (multicastEngine.currentUrl == multicastDecision.normalizedUrl &&\n"
    "                multicastEngine.state.value != tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.ERROR\n"
    "            ) {\n"
    "                multicastEngine.setMuted(!livePreviewAudio.value)\n"
    "            } else {\n"
    "                multicastEngine.play(multicastDecision.normalizedUrl, !livePreviewAudio.value, meta)\n"
    "            }\n"
    "            return\n"
    "        }\n"
    "        if (_previewOnMulticast.value) {\n"
    "            _previewOnMulticast.value = false\n"
    "            multicastEngine.stop()\n"
    "        }\n"
    "        // A one-session panel counts the muted preview as the account's single stream, so previewing while",
)

replace_once(
    vm,
    "    private val _liveOnExo = MutableStateFlow(false)\n"
    "    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()",
    "    private val _liveOnExo = MutableStateFlow(false)\n"
    "    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()\n"
    "    private val _liveOnMulticast = MutableStateFlow(false)\n"
    "    val liveOnMulticast: StateFlow<Boolean> = _liveOnMulticast.asStateFlow()\n"
    "    private val _previewOnMulticast = MutableStateFlow(false)\n"
    "    val previewOnMulticast: StateFlow<Boolean> = _previewOnMulticast.asStateFlow()",
)

replace_once(
    vm,
    "                if (!_liveOnExo.value && previewEngine.currentUrl != null) previewEngine.setMuted(!on)",
    "                if (!_liveOnExo.value && !_liveOnMulticast.value && _previewOnMulticast.value && multicastEngine.currentUrl != null) {\n"
    "                    multicastEngine.setMuted(!on)\n"
    "                } else if (!_liveOnExo.value && previewEngine.currentUrl != null) {\n"
    "                    previewEngine.setMuted(!on)\n"
    "                }",
)

replace_once(
    vm,
    "    fun onFullscreenExited() {\n        _liveOnExo.value = false",
    "    fun onFullscreenExited() {\n"
    "        val wasMulticast = _liveOnMulticast.value\n"
    "        _liveOnMulticast.value = false\n"
    "        if (wasMulticast) {\n"
    "            if (livePreviewEnabled.value) {\n"
    "                _previewOnMulticast.value = true\n"
    "                multicastEngine.setMuted(!livePreviewAudio.value)\n"
    "            } else {\n"
    "                multicastOutcomeJob?.cancel()\n"
    "                _previewOnMulticast.value = false\n"
    "                multicastEngine.stop()\n"
    "            }\n"
    "        }\n"
    "        _liveOnExo.value = false",
)

replace_once(
    vm,
    "    fun clearLiveOnExo() {\n        exoOutcomeJob?.cancel()",
    "    fun clearLiveOnExo() {\n"
    "        multicastOutcomeJob?.cancel()\n"
    "        _liveOnMulticast.value = false\n"
    "        _previewOnMulticast.value = false\n"
    "        multicastEngine.stop()\n"
    "        exoOutcomeJob?.cancel()",
)

replace_once(
    vm,
    "        _catchupActive.value = false // tuning live ends any archive playback the HUD was showing\n"
    "        // Three inputs, in descending authority: what the user pinned for THIS channel, the engine",
    "        _catchupActive.value = false // tuning live ends any archive playback the HUD was showing\n"
    "        val multicastRoute = tv.own.owntv.provider.solcon.SolconPlaybackRoute.decide(\n"
    "            channel.streamUrl, forceMpv = enginePin(channel) == true,\n"
    "        )\n"
    "        if (multicastRoute != tv.own.owntv.provider.solcon.SolconPlaybackRoute.Target.EXISTING && channel.drmConfig == null) {\n"
    "            if (multicastRoute == tv.own.owntv.provider.solcon.SolconPlaybackRoute.Target.MEDIA3_MULTICAST) {\n"
    "                startOnMulticast(channel)\n"
    "            } else {\n"
    "                multicastOutcomeJob?.cancel()\n"
    "                _liveOnExo.value = false\n"
    "                _liveOnMulticast.value = false\n"
    "                _previewOnMulticast.value = false\n"
    "                multicastEngine.stop()\n"
    "                startOnMpv(channel, multicastRoute.name)\n"
    "            }\n"
    "            recordLiveHistory(channel)\n"
    "            return\n"
    "        }\n"
    "        // Three inputs, in descending authority: what the user pinned for THIS channel, the engine",
)

multicast_methods = """
    private var multicastOutcomeJob: Job? = null

    private suspend fun startOnMulticast(channel: ChannelEntity) {
        val decision = tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(channel.streamUrl)
        if (!decision.isMulticast) return
        exoOutcomeJob?.cancel()
        mpvOutcomeJob?.cancel()
        _liveOnExo.value = false
        _liveOnMulticast.value = true
        _previewOnMulticast.value = true
        previewEngine.stop()
        if (player.hasActiveStream) {
            player.stopAndAwaitRelease()
            delay(tv.own.owntv.player.OwnTVPlayer.SURFACE_HANDOFF_MS)
            if (_previewChannel.value?.streamUrl != channel.streamUrl) return
        }
        val meta = tv.own.owntv.player.MediaMeta(
            title = channel.name, subtitle = channelNumberLabel(channel),
            logoUrl = channel.displayLogoUrl, contentKey = mpvPinKey(channel),
        )
        if (multicastEngine.currentUrl == decision.normalizedUrl &&
            multicastEngine.state.value != tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.ERROR
        ) {
            multicastEngine.setMuted(false)
        } else {
            multicastEngine.play(decision.normalizedUrl, false, meta)
        }
        watchMulticastOutcome(channel)
    }

    private fun watchMulticastOutcome(channel: ChannelEntity) {
        multicastOutcomeJob?.cancel()
        multicastOutcomeJob = viewModelScope.launch {
            val opened = kotlinx.coroutines.withTimeoutOrNull(MULTICAST_OPEN_TIMEOUT_MS) {
                combine(multicastEngine.state, multicastEngine.error) { state, error ->
                    when {
                        state == tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.PLAYING -> true
                        state == tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.ERROR || error != null -> false
                        else -> null
                    }
                }.first { it != null }
            }
            if (!isStillOnMulticast(channel)) return@launch
            if (opened == true) return@launch
            _liveOnMulticast.value = false
            _previewOnMulticast.value = false
            multicastEngine.stop()
            fallbackToMpv(
                channel,
                tv.own.owntv.provider.solcon.SolconPlaybackRoute.Target.MPV_MULTICAST.name,
                forceTs = true,
            )
        }
    }

    private fun isStillOnMulticast(channel: ChannelEntity): Boolean =
        _liveOnMulticast.value && _previewChannel.value?.streamUrl == channel.streamUrl

"""
replace_once(vm, "    private suspend fun startOnExo(channel: ChannelEntity) {", multicast_methods + "    private suspend fun startOnExo(channel: ChannelEntity) {")

replace_once(
    vm,
    "    private suspend fun startOnExo(channel: ChannelEntity) {\n        mpvOutcomeJob?.cancel() // ExoPlayer owns the channel now",
    "    private suspend fun startOnExo(channel: ChannelEntity) {\n"
    "        multicastOutcomeJob?.cancel()\n"
    "        _liveOnMulticast.value = false\n"
    "        _previewOnMulticast.value = false\n"
    "        multicastEngine.stop()\n"
    "        mpvOutcomeJob?.cancel() // ExoPlayer owns the channel now",
)

replace_once(
    vm,
    "        if (channel.drmConfig != null) return\n        // Base the swap on the ACTUAL running engine, not the pin:",
    "        if (channel.drmConfig != null) return\n"
    "        if (tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(channel.streamUrl).isMulticast) {\n"
    "            val goToMpv = _liveOnMulticast.value || _previewOnMulticast.value\n"
    "            viewModelScope.launch {\n"
    "                val key = mpvPinKey(channel)\n"
    "                forceMpvStore.pin(key ?: channel.streamUrl, goToMpv)\n"
    "                if (key != null) forceMpvStore.forget(channel.streamUrl)\n"
    "                if (goToMpv) {\n"
    "                    multicastOutcomeJob?.cancel()\n"
    "                    _liveOnMulticast.value = false\n"
    "                    _previewOnMulticast.value = false\n"
    "                    multicastEngine.stop()\n"
    "                    fallbackToMpv(\n"
    "                        channel,\n"
    "                        tv.own.owntv.provider.solcon.SolconPlaybackRoute.Target.MPV_MULTICAST.name,\n"
    "                        forceTs = true,\n"
    "                    )\n"
    "                } else {\n"
    "                    startOnMulticast(channel)\n"
    "                }\n"
    "            }\n"
    "            return\n"
    "        }\n"
    "        // Base the swap on the ACTUAL running engine, not the pin:",
)

replace_once(
    vm,
    "    private suspend fun fallbackToMpv(channel: ChannelEntity, reason: String, forceTs: Boolean = false) {\n        engineLog(",
    "    private suspend fun fallbackToMpv(channel: ChannelEntity, reason: String, forceTs: Boolean = false) {\n"
    "        multicastOutcomeJob?.cancel()\n"
    "        _liveOnMulticast.value = false\n"
    "        _previewOnMulticast.value = false\n"
    "        multicastEngine.stop()\n"
    "        engineLog(",
)

replace_once(
    vm,
    "        !_liveOnExo.value && _previewChannel.value?.streamUrl == channel.streamUrl",
    "        !_liveOnExo.value && !_liveOnMulticast.value && _previewChannel.value?.streamUrl == channel.streamUrl",
)

replace_once(
    vm,
    "    fun stopPreview() {\n        setStalkerReconnect(null)",
    "    fun stopPreview() {\n"
    "        multicastOutcomeJob?.cancel()\n"
    "        _liveOnMulticast.value = false\n"
    "        _previewOnMulticast.value = false\n"
    "        multicastEngine.stop()\n"
    "        setStalkerReconnect(null)",
)

replace_once(
    vm,
    "        const val MPV_OPEN_TIMEOUT_MS = 35_000L",
    "        const val MPV_OPEN_TIMEOUT_MS = 35_000L\n        const val MULTICAST_OPEN_TIMEOUT_MS = 10_000L",
)

# Live preview pane: render whichever Media3 engine owns the preview.
replace_once(
    screen,
    "    val previewArmed by vm.previewArmed.collectAsStateWithLifecycle()",
    "    val previewArmed by vm.previewArmed.collectAsStateWithLifecycle()\n"
    "    val previewOnMulticast by vm.previewOnMulticast.collectAsStateWithLifecycle()",
)
replace_once(
    screen,
    "                    previewEngine = vm.previewEngine,\n                    showVideo = effectivePreview,",
    "                    previewEngine = vm.previewEngine,\n"
    "                    multicastEngine = vm.multicastEngine,\n"
    "                    previewOnMulticast = previewOnMulticast,\n"
    "                    showVideo = effectivePreview,",
)
replace_once(
    screen,
    "    previewEngine: tv.own.owntv.player.LivePreviewEngine,\n    showVideo: Boolean,",
    "    previewEngine: tv.own.owntv.player.LivePreviewEngine,\n"
    "    multicastEngine: tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine,\n"
    "    previewOnMulticast: Boolean,\n"
    "    showVideo: Boolean,",
)
replace_once(
    screen,
    "    val previewState by previewEngine.state.collectAsStateWithLifecycle()\n"
    "    val previewHeight by previewEngine.videoHeight.collectAsStateWithLifecycle()\n"
    "    val streamChips by previewEngine.streamChips.collectAsStateWithLifecycle()\n"
    "    // Show the ExoPlayer surface once it's playing/buffering; on ERROR fall back to the channel logo.\n"
    "    val previewPlaying = showVideo && previewState != tv.own.owntv.player.LivePreviewEngine.State.ERROR &&\n"
    "        previewState != tv.own.owntv.player.LivePreviewEngine.State.IDLE\n"
    "    val previewLoading = showVideo && previewState == tv.own.owntv.player.LivePreviewEngine.State.LOADING",
    "    val exoPreviewState by previewEngine.state.collectAsStateWithLifecycle()\n"
    "    val exoPreviewHeight by previewEngine.videoHeight.collectAsStateWithLifecycle()\n"
    "    val exoStreamChips by previewEngine.streamChips.collectAsStateWithLifecycle()\n"
    "    val multicastState by multicastEngine.state.collectAsStateWithLifecycle()\n"
    "    val multicastHeight by multicastEngine.videoHeight.collectAsStateWithLifecycle()\n"
    "    val previewHeight = if (previewOnMulticast) multicastHeight else exoPreviewHeight\n"
    "    val streamChips = if (previewOnMulticast) emptyList() else exoStreamChips\n"
    "    val previewPlaying = if (previewOnMulticast) {\n"
    "        showVideo && multicastState != tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.ERROR &&\n"
    "            multicastState != tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.IDLE\n"
    "    } else {\n"
    "        showVideo && exoPreviewState != tv.own.owntv.player.LivePreviewEngine.State.ERROR &&\n"
    "            exoPreviewState != tv.own.owntv.player.LivePreviewEngine.State.IDLE\n"
    "    }\n"
    "    val previewLoading = if (previewOnMulticast) {\n"
    "        showVideo && multicastState == tv.own.owntv.provider.solcon.multicast.SolconMulticastEngine.State.LOADING\n"
    "    } else {\n"
    "        showVideo && exoPreviewState == tv.own.owntv.player.LivePreviewEngine.State.LOADING\n"
    "    }",
)
replace_once(
    screen,
    "            if (previewPlaying) {\n                tv.own.owntv.player.ExoPreviewSurface(engine = previewEngine, modifier = Modifier.fillMaxSize())\n            }",
    "            if (previewPlaying) {\n"
    "                if (previewOnMulticast) {\n"
    "                    tv.own.owntv.provider.solcon.multicast.SolconMulticastSurface(\n"
    "                        engine = multicastEngine, modifier = Modifier.fillMaxSize(),\n"
    "                    )\n"
    "                } else {\n"
    "                    tv.own.owntv.player.ExoPreviewSurface(engine = previewEngine, modifier = Modifier.fillMaxSize())\n"
    "                }\n"
    "            }",
)

# Shell: one active PlaybackEngine, plus a dedicated multicast SurfaceView.
replace_once(
    shell,
    "    val liveOnExo by liveVm.liveOnExo.collectAsStateWithLifecycle()",
    "    val liveOnExo by liveVm.liveOnExo.collectAsStateWithLifecycle()\n"
    "    val liveOnMulticast by liveVm.liveOnMulticast.collectAsStateWithLifecycle()\n"
    "    val activePlayerEngine: tv.own.owntv.player.PlaybackEngine = when {\n"
    "        liveOnMulticast -> liveVm.multicastEngine\n"
    "        liveOnExo -> liveVm.previewEngine\n"
    "        else -> mpvEngine\n"
    "    }",
)
replace_once(
    shell,
    "    LaunchedEffect(liveOnExo, playerMode) {\n"
    "        playbackSession.attach(\n"
    "            if (playerMode == PlayerMode.NONE) null else if (liveOnExo) liveVm.previewEngine else mpvEngine,\n"
    "        )\n"
    "    }",
    "    LaunchedEffect(liveOnExo, liveOnMulticast, playerMode) {\n"
    "        playbackSession.attach(if (playerMode == PlayerMode.NONE) null else activePlayerEngine)\n"
    "    }",
)
replace_all_checked(shell, "if (liveOnExo) liveVm.previewEngine else mpvEngine", "activePlayerEngine", 5)
replace_all_checked(shell, "liveOnExo || player.isLiveContent", "liveOnMulticast || liveOnExo || player.isLiveContent", 3)
replace_once(shell, "if (!liveOnExo) {\n                tv.own.owntv.player.SubtitleOverlay(", "if (!liveOnExo && !liveOnMulticast) {\n                tv.own.owntv.player.SubtitleOverlay(")
replace_once(
    shell,
    "            if (liveOnExo) {\n"
    "                tv.own.owntv.player.ExoPreviewSurface(\n"
    "                    engine = liveVm.previewEngine, modifier = Modifier.fillMaxSize(),\n"
    "                    keepAwake = true, autoFrameRate = isFull && autoFrameRate,\n"
    "                )\n"
    "            } else {\n"
    "                MpvVideoSurface(player = player, modifier = Modifier.fillMaxSize(), autoFrameRate = isFull && autoFrameRate)\n"
    "            }",
    "            if (liveOnMulticast) {\n"
    "                tv.own.owntv.provider.solcon.multicast.SolconMulticastSurface(\n"
    "                    engine = liveVm.multicastEngine, modifier = Modifier.fillMaxSize(), keepAwake = true,\n"
    "                )\n"
    "            } else if (liveOnExo) {\n"
    "                tv.own.owntv.player.ExoPreviewSurface(\n"
    "                    engine = liveVm.previewEngine, modifier = Modifier.fillMaxSize(),\n"
    "                    keepAwake = true, autoFrameRate = isFull && autoFrameRate,\n"
    "                )\n"
    "            } else {\n"
    "                MpvVideoSurface(player = player, modifier = Modifier.fillMaxSize(), autoFrameRate = isFull && autoFrameRate)\n"
    "            }",
)
replace_once(
    shell,
    "            val audioOnlyMedia by if (liveOnExo) {\n"
    "                liveVm.previewEngine.audioOnlyMedia.collectAsStateWithLifecycle()\n"
    "            } else {\n"
    "                player.audioOnlyMedia.collectAsStateWithLifecycle()\n"
    "            }",
    "            val audioOnlyMedia by activePlayerEngine.audioOnlyMedia.collectAsStateWithLifecycle()",
)
replace_once(
    shell,
    "                val activeFps by if (liveOnExo) {\n"
    "                    liveVm.previewEngine.videoFps.collectAsStateWithLifecycle()\n"
    "                } else {\n"
    "                    player.videoFps.collectAsStateWithLifecycle()\n"
    "                }",
    "                val activeFps by if (liveOnMulticast) {\n"
    "                    liveVm.multicastEngine.videoFps.collectAsStateWithLifecycle()\n"
    "                } else if (liveOnExo) {\n"
    "                    liveVm.previewEngine.videoFps.collectAsStateWithLifecycle()\n"
    "                } else {\n"
    "                    player.videoFps.collectAsStateWithLifecycle()\n"
    "                }",
)
replace_once(shell, "compatMode = if (isTunedLive) !liveOnExo else null", "compatMode = if (isTunedLive) !liveOnExo && !liveOnMulticast else null")

# Koin: the multicast engine is the new final LiveViewModel constructor parameter.
replace_once(
    app_module,
    "            get(),\n            get(),\n            get(),\n        )\n    }\n    viewModelOf(::MovieViewModel)",
    "            get(),\n            get(),\n            get(),\n            get(),\n        )\n    }\n    viewModelOf(::MovieViewModel)",
)
