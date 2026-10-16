package com.redtv.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.redtv.app.data.Prefs
import com.redtv.app.data.ContentRepository
import com.redtv.app.model.Channel
import com.redtv.app.net.Http

@UnstableApi
class PlaybackEngine(context: Context, private val prefs: Prefs,
                     private val status: (String?) -> Unit,
                     private val ended: () -> Unit = {}, private val recordHistory: Boolean = true) {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = PlaybackPolicy()
    private val http = DefaultHttpDataSource.Factory().setUserAgent(Http.userAgent)
        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    private val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(sources)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 1_000, 3_000)
            .setTargetBufferBytes(24 * 1024 * 1024).build()).build()
    var channel: Channel? = null
        private set
    private var released = false
    private val retry = Runnable { if (!released) {
        if (channel?.isVod == false) player.seekToDefaultPosition()
        player.prepare(); player.play()
    } }
    private val stable = Runnable { if (!released && player.isPlaying) policy.stable() }
    private val stalled = Runnable {
        if (!released && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            val at=player.currentPosition
            player.stop()
            if(channel?.isVod == true) player.seekTo(at)
            scheduleRetry()
        }
    }
    private fun scheduleRetry() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stalled)
        handler.removeCallbacks(stable)
        val wait=policy.retryDelay()
        status(if(wait != null) "Connection interrupted. Retrying…" else "Unable to play. Open Options to retry or choose another item.")
        if(wait != null) handler.postDelayed(retry,wait)
    }
    private val checkpoint = object : Runnable {
        override fun run() { if (!released) { save(); handler.postDelayed(this, 10_000) } }
    }

    init {
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLanguage.ifBlank { null })
            .setPreferredTextLanguage(prefs.subtitleLanguage.ifBlank { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitleEnabled).build()
        player.addListener(object : Player.Listener {
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                prefs.subtitleEnabled = !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            }
            override fun onPlaybackStateChanged(state: Int) {
                handler.removeCallbacks(stalled)
                handler.removeCallbacks(stable)
                when (state) {
                    Player.STATE_READY -> { policy.ready(); status(null); save(); handler.postDelayed(stable,60_000) }
                    Player.STATE_BUFFERING -> { status("Buffering…"); handler.postDelayed(stalled,30_000) }
                    Player.STATE_ENDED -> { save(completed = true); ended() }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                scheduleRetry()
            }
        })
        handler.postDelayed(checkpoint, 10_000)
    }

    fun play(item: Channel, position: Long = if (item.isVod) prefs.resumePosition(item.id) else 0L,
             playing: Boolean = true) {
        save()
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stalled)
        handler.removeCallbacks(stable)
        channel = item
        policy.begin()
        http.setUserAgent(ContentRepository.config?.userAgent ?: Http.userAgent)
        http.setDefaultRequestProperties(item.headers)
        player.setMediaItem(MediaItem.fromUri(item.streamUrl), if (item.isVod) position.coerceAtLeast(0) else C.TIME_UNSET)
        player.playWhenReady = playing
        player.prepare()
    }

    fun retryManually() {
        val item = channel ?: return
        val position = player.currentPosition
        play(item, if (item.isVod) position else 0L)
    }

    fun save(completed: Boolean = false) {
        if (!recordHistory) return
        val item = channel ?: return
        if (!policy.canRecord) return
        prefs.recordPlayback(item, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it > 0 } ?: 0, completed || player.playbackState == Player.STATE_ENDED)
        if (!item.isVod) prefs.lastChannelId = item.id
    }

    fun release() {
        if (released) return
        save()
        released = true
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
