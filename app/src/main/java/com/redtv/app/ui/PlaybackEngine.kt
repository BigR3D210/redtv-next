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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.redtv.app.data.Prefs
import com.redtv.app.model.Channel
import com.redtv.app.net.Http

@UnstableApi
class PlaybackEngine(context: Context, private val prefs: Prefs,
                     private val status: (String?) -> Unit,
                     private val ended: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = PlaybackPolicy()
    private val http = DefaultHttpDataSource.Factory().setUserAgent(Http.userAgent)
        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    private val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
    val player: ExoPlayer = ExoPlayer.Builder(context).setMediaSourceFactory(sources).build()
    var channel: Channel? = null
        private set
    private var released = false
    private val retry = Runnable { if (!released) { player.prepare(); player.play() } }
    private val checkpoint = object : Runnable {
        override fun run() { if (!released) { save(); handler.postDelayed(this, 10_000) } }
    }

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLanguage.ifBlank { null })
            .setPreferredTextLanguage(prefs.subtitleLanguage.ifBlank { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitleEnabled).build()
        player.addListener(object : Player.Listener {
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                prefs.subtitleEnabled = !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { policy.ready(); status(null); save() }
                    Player.STATE_BUFFERING -> status("Buffering…")
                    Player.STATE_ENDED -> { save(completed = true); ended() }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                handler.removeCallbacks(retry)
                val delay = policy.retryDelay()
                status(if (delay != null) "Connection interrupted. Retrying…" else
                    "Unable to play (${error.errorCodeName}). Open Options to retry or choose another item.")
                if (delay != null) handler.postDelayed(retry, delay)
            }
        })
        handler.postDelayed(checkpoint, 10_000)
    }

    fun play(item: Channel, position: Long = if (item.isVod) prefs.resumePosition(item.id) else 0L,
             playing: Boolean = true) {
        save()
        handler.removeCallbacks(retry)
        channel = item
        policy.begin()
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
