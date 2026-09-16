package tv.own.owntv.provider.solcon.multicast

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.player.TrackOption
import tv.own.owntv.player.ZoomMode
import tv.own.owntv.provider.solcon.SolconStreamPolicy

/** Media3 player dedicated to clear local RTP/UDP MPEG-TS multicast. */
@UnstableApi
class SolconMulticastEngine(
    context: Context,
) : PlaybackEngine {
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    private val appContext = context.applicationContext
    private val player = ExoPlayer.Builder(appContext).build()
    private var surface: Surface? = null
    private var muted = true
    private var lastPlayMuted = true

    var currentUrl: String? = null
        private set
    private var lastMeta = MediaMeta()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _videoHeight = MutableStateFlow<Int?>(null)
    val videoHeight: StateFlow<Int?> = _videoHeight.asStateFlow()
    private val _videoFps = MutableStateFlow<Float?>(null)
    val videoFps: StateFlow<Float?> = _videoFps.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _error = MutableStateFlow<PlaybackFailure?>(null)
    override val error: StateFlow<PlaybackFailure?> = _error.asStateFlow()
    private val _videoRes = MutableStateFlow<String?>(null)
    override val videoRes: StateFlow<String?> = _videoRes.asStateFlow()
    private val _volume = MutableStateFlow(100)
    override val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _zoomMode = MutableStateFlow(ZoomMode.FIT)
    override val zoomMode: StateFlow<ZoomMode> = _zoomMode.asStateFlow()
    private val _audioCount = MutableStateFlow(0)
    override val audioCount: StateFlow<Int> = _audioCount.asStateFlow()
    private val _subCount = MutableStateFlow(0)
    override val subCount: StateFlow<Int> = _subCount.asStateFlow()
    private val _currentMeta = MutableStateFlow(MediaMeta())
    override val currentMeta: StateFlow<MediaMeta> = _currentMeta.asStateFlow()
    private val _audioOnly = MutableStateFlow(false)
    override val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()
    private val _audioOnlyMedia = MutableStateFlow(false)
    override val audioOnlyMedia: StateFlow<Boolean> = _audioOnlyMedia.asStateFlow()
    override val isLiveContent: Boolean = true

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _state.value = State.LOADING
                        _buffering.value = true
                    }
                    Player.STATE_READY -> {
                        _state.value = State.PLAYING
                        _buffering.value = false
                        updateTracks(player.currentTracks)
                    }
                    Player.STATE_ENDED -> {
                        _state.value = State.IDLE
                        _buffering.value = false
                        _isPlaying.value = false
                    }
                    Player.STATE_IDLE -> if (currentUrl == null) {
                        _state.value = State.IDLE
                        _buffering.value = false
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = State.ERROR
                _buffering.value = false
                _isPlaying.value = false
                _error.value = PlaybackFailure.Channel
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val height = videoSize.height.takeIf { it > 0 }
                _videoHeight.value = height
                _videoRes.value = null
                _videoFps.value = player.videoFormat?.frameRate?.takeIf { it > 0f }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateTracks(tracks)
            }
        })
    }

    fun play(url: String, muted: Boolean, meta: MediaMeta) {
        val decision = SolconStreamPolicy.classify(url)
        if (!decision.isMulticast) {
            failClosed()
            return
        }
        currentUrl = decision.normalizedUrl
        lastMeta = meta
        lastPlayMuted = muted
        _currentMeta.value = meta
        _error.value = null
        _state.value = State.LOADING
        _buffering.value = true
        _isPlaying.value = false
        _audioOnlyMedia.value = false
        _videoHeight.value = null
        _videoFps.value = null

        val mediaItem = MediaItem.Builder()
            .setUri(decision.normalizedUrl)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .build()
        val source = ProgressiveMediaSource.Factory(RtpDataSourceFactory(appContext))
            .createMediaSource(mediaItem)
        player.setMediaSource(source)
        setMuted(muted)
        player.prepare()
        player.playWhenReady = true
    }

    fun setMuted(value: Boolean) {
        muted = value
        player.volume = if (value) 0f else _volume.value / 100f
    }

    fun setSurface(next: Surface?) {
        surface = next
        if (_audioOnly.value) {
            player.clearVideoSurface()
        } else {
            player.setVideoSurface(next)
        }
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        currentUrl = null
        _state.value = State.IDLE
        _buffering.value = false
        _isPlaying.value = false
        _error.value = null
        _audioOnly.value = false
        _audioOnlyMedia.value = false
        _videoHeight.value = null
        _videoFps.value = null
    }

    fun release() {
        stop()
        player.release()
    }

    override fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun setZoomMode(mode: ZoomMode) {
        _zoomMode.value = mode
    }

    override fun adjustVolume(delta: Int) {
        val next = (_volume.value + delta).coerceIn(0, 100)
        _volume.value = next
        if (!muted) player.volume = next / 100f
    }

    override fun toggleMute() {
        setMuted(!muted)
    }

    override fun retry() {
        val url = currentUrl ?: return
        play(url, lastPlayMuted, lastMeta)
    }

    override fun selectAudio(id: Int) = Unit
    override fun selectSubtitle(id: Int) = Unit
    override fun disableSubtitles() = Unit
    override fun audioTracks(): List<TrackOption> = emptyList()
    override fun textTracks(): List<TrackOption> = emptyList()

    override fun enterAudioOnly() {
        if (_audioOnly.value) return
        _audioOnly.value = true
        player.clearVideoSurface()
    }

    override fun exitAudioOnly() {
        if (!_audioOnly.value) return
        _audioOnly.value = false
        player.setVideoSurface(surface)
    }

    private fun updateTracks(tracks: Tracks) {
        var hasVideo = false
        var hasAudio = false
        tracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> hasVideo = true
                C.TRACK_TYPE_AUDIO -> hasAudio = true
            }
        }
        _audioOnlyMedia.value = hasAudio && !hasVideo
        _videoFps.value = player.videoFormat?.frameRate?.takeIf { it > 0f }
    }

    private fun failClosed() {
        currentUrl = null
        _state.value = State.ERROR
        _buffering.value = false
        _isPlaying.value = false
        _error.value = PlaybackFailure.Channel
    }
}
