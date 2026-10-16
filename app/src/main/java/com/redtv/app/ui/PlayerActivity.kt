package com.redtv.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityPlayerBinding
import com.redtv.app.model.Channel
import com.redtv.app.net.RoomState
import com.redtv.app.net.RoomSyncClient
import com.redtv.app.net.RoomSyncMath
import com.redtv.app.net.RoomSyncServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

@UnstableApi
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private var engine: PlaybackEngine? = null
    private var queue = emptyList<Channel>()
    private var queueKey: String? = null
    private var index = 0
    private var previous = -1
    private var position: Long? = null
    private var playing = true
    private val handler = Handler(Looper.getMainLooper())
    private var sleepAt = 0L
    private val sleep = Runnable { engine?.player?.pause(); finish() }
    private val hideInfo = Runnable { b.infoBar.visibility = View.GONE }
    private var roomJob: Job? = null
    private var host: RoomSyncServer? = null
    private var following = false
    private val ratios = intArrayOf(0, 3, 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.buffering.visibility = View.GONE
        b.retryButton.setOnClickListener { engine?.retryManually() }
        b.optionsButton.setOnClickListener { options() }
        ContentRepository.initialize(this)
        // The library is already loaded when opening a channel from the browser.
        // Re-reading and decrypting it here blocks the first video frame.
        if (!ContentRepository.hasAny() && !intent.hasExtra(EXTRA_ROOM_URL)) ContentRepository.restore(prefs)
        queueKey = savedInstanceState?.getString(EXTRA_QUEUE) ?: intent.getStringExtra(EXTRA_QUEUE)
        queue = queueKey?.let { ContentRepository.readQueue(this, it) }.orEmpty()
        if (queue.isEmpty()) queue = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty().mapNotNull { ContentRepository.channelById(it) }
        if (queue.isEmpty() && queueKey == null && !intent.hasExtra(EXTRA_ROOM_URL)) queue = ContentRepository.launchQueue
        queue = queue.filter { it.streamUrl.isNotBlank() && !it.isSeries }
        if (queue.isNotEmpty() && queueKey == null) queueKey = ContentRepository.createQueue(this, queue)
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        previous = savedInstanceState?.getInt("previous", -1) ?: -1
        position = savedInstanceState?.getLong("position")
        playing = savedInstanceState?.getBoolean("playing", true) ?: true
        sleepAt = savedInstanceState?.getLong("sleepAt", 0) ?: 0
        if (queue.isEmpty() && intent.getStringExtra(EXTRA_ROOM_URL) == null) {
            Toast.makeText(this, "No playable item was selected. Reopen it from the library.", Toast.LENGTH_LONG).show()
            finish()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        engine = PlaybackEngine(this, prefs, { message ->
            b.errorText.text = message
            b.errorText.visibility = if (message == null) View.GONE else View.VISIBLE
            b.retryButton.visibility = if (message?.startsWith("Unable to play") == true) View.VISIBLE else View.GONE
            if (b.retryButton.visibility == View.VISIBLE) b.retryButton.requestFocus()
        }, ::onEnded)
        b.playerView.player = engine?.player
        b.playerView.controllerShowTimeoutMs = 3500
        b.playerView.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
            b.optionsButton.visibility = visibility
            if (visibility != View.VISIBLE && b.optionsButton.hasFocus()) b.playerView.requestFocus()
        })
        b.playerView.requestFocus()
        b.optionsButton.visibility=if(b.playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
        b.playerView.resizeMode = prefs.resizeMode
        if (queue.isNotEmpty()) playCurrent(position)
        if (sleepAt > 0) handler.postDelayed(sleep, (sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
        startFollowing()
    }

    private fun playCurrent(at: Long? = null) {
        val ch = queue.getOrNull(index) ?: return
        engine?.play(ch, at ?: if (ch.isVod) prefs.resumePosition(ch.id) else 0, playing)
        showInfo()
    }

    private fun showInfo() {
        val ch = engine?.channel ?: return
        b.infoName.text = prefs.displayChannel(ch).name
        val (now, next) = ContentRepository.nowAndNext(ch.epgChannelId)
        b.infoNow.text = now?.let { "Now: ${it.title}" }.orEmpty()
        b.infoNext.text = next?.let { "Next: ${it.title}" }.orEmpty()
        b.infoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3500)
    }

    private fun switchTo(target: Int) {
        if (following || target !in queue.indices) return
        previous = index
        index = target
        position = null
        playing = true
        playCurrent()
    }

    private fun onEnded() {
        if (following) return
        val current = queue.getOrNull(index) ?: return
        val next = queue.getOrNull(index + 1) ?: return
        if (PlaybackPolicy.sequential(prefs.autoPlay, current.kind, next.kind, current.seriesId,
                next.seriesId, current.season, current.episode, next.season, next.episode)) switchTo(index + 1)
    }

    private fun options() {
        val choices = arrayOf("Retry playback", "Previous channel", "Picture size", "Audio track", "Subtitles / CC",
            "Auto-play next episode: ${if (prefs.autoPlay) "On" else "Off"}", "Sleep timer",
            if (host != null) "Stop sharing playback" else "Share playback with another TV",
            if (following) "Stop following TV" else "Playback language preferences")
        AlertDialog.Builder(this).setTitle("Playback options").setItems(choices) { _, which ->
            when (which) {
                0 -> engine?.retryManually()
                1 -> switchTo(previous)
                2 -> AlertDialog.Builder(this).setTitle("Picture size").setItems(arrayOf("Fit", "Stretch", "Zoom")) { _, n -> prefs.resizeMode = ratios[n]; b.playerView.resizeMode = ratios[n] }.show()
                3 -> tracks(C.TRACK_TYPE_AUDIO, "Audio track")
                4 -> { prefs.subtitleEnabled = !prefs.subtitleEnabled; engine?.player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitleEnabled).build() }; tracks(C.TRACK_TYPE_TEXT, "Subtitles / CC") }
                5 -> { prefs.autoPlay = !prefs.autoPlay }
                6 -> AlertDialog.Builder(this).setTitle("Stop playback after").setItems(arrayOf("Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes")) { _, n ->
                    handler.removeCallbacks(sleep)
                    val mins = intArrayOf(0, 15, 30, 60, 90)[n]
                    sleepAt = if (mins == 0) 0 else System.currentTimeMillis() + mins * 60_000L
                    if (mins > 0) handler.postDelayed(sleep, mins * 60_000L)
                }.show()
                7 -> if (host != null) stopRoom() else startHost()
                8 -> if (following) { stopRoom(); intent.removeExtra(EXTRA_ROOM_URL); intent.removeExtra(EXTRA_ROOM_TOKEN) } else languageOptions()
            }
        }.show()
    }

    private fun languageOptions() {
        val input = android.widget.EditText(this).apply { setText(prefs.audioLanguage); hint = "Language code, e.g. en, es (blank = automatic)" }
        AlertDialog.Builder(this).setTitle("Preferred audio language").setView(input).setPositiveButton("Next") { _, _ ->
            prefs.audioLanguage = input.text.toString().trim()
            val sub = android.widget.EditText(this).apply { setText(prefs.subtitleLanguage); hint = "Subtitle language code" }
            AlertDialog.Builder(this).setTitle("Preferred subtitle language").setView(sub).setPositiveButton("Save") { _, _ ->
                prefs.subtitleLanguage = sub.text.toString().trim()
                engine?.player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(prefs.audioLanguage.ifBlank { null }).setPreferredTextLanguage(prefs.subtitleLanguage.ifBlank { null }).build() }
            }.setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun tracks(type: Int, title: String) {
        val p = engine?.player ?: return
        if (p.currentTracks.groups.none { it.type == type }) { Toast.makeText(this, "No $title available", Toast.LENGTH_SHORT).show(); return }
        TrackSelectionDialogBuilder(this, title, p, type).setShowDisableOption(type == C.TRACK_TYPE_TEXT).build().show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> { options(); return true }
            KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { tracks(C.TRACK_TYPE_TEXT, "Subtitles / CC"); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switchTo((index - 1 + queue.size) % queue.size.coerceAtLeast(1)); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> { switchTo((index + 1) % queue.size.coerceAtLeast(1)); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!following && !b.optionsButton.hasFocus() && b.retryButton.visibility != View.VISIBLE && !b.playerView.isControllerFullyVisible && engine?.channel?.isVod == false) {
                switchTo((index + (if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) + queue.size) % queue.size.coerceAtLeast(1)); return true
            }
            KeyEvent.KEYCODE_BACK -> if (b.playerView.isControllerFullyVisible) { b.playerView.hideController(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun windowStart(): Long? {
        val p = engine?.player ?: return null
        if (p.currentTimeline.isEmpty) return null
        return p.currentTimeline.getWindow(p.currentMediaItemIndex, Timeline.Window()).windowStartTimeMs.takeIf { it != C.TIME_UNSET }
    }

    private fun startHost() {
        AlertDialog.Builder(this).setTitle("Share playback on your local network?")
            .setMessage("Paired TVs receive this stream's address and credentials. Only pair TVs you trust. Live synchronization is best effort unless the stream includes program time.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start sharing") { _, _ ->
                stopRoom()
                val server = runCatching { RoomSyncServer.startAvailable() }.getOrElse {
                    Toast.makeText(this, "Unable to start sharing. Check your local network connection.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                host = server
                val addresses = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }.mapNotNull { address ->
                    runCatching { com.redtv.app.net.RoomAddress.normalize("http://${address.hostAddress}:${server.listeningPort}") }.getOrNull()
                }.distinct().joinToString("\n") }.getOrDefault("TV LAN address, port ${server.listeningPort}")
                AlertDialog.Builder(this).setTitle("Pair another TV").setMessage("$addresses\nCode: ${server.code}\nCode expires after 10 minutes. Sharing stops when you leave playback.").setPositiveButton("Done", null).show()
                roomJob = lifecycleScope.launch {
                    while (isActive) {
                        val p = engine?.player
                        val ch = engine?.channel
                        if (p != null && ch != null) server.publish(RoomState(ch, p.currentPosition, p.playWhenReady, System.currentTimeMillis(), windowStart()?.plus(p.currentPosition)))
                        delay(1000)
                    }
                }
            }.show()
    }

    private fun startFollowing() {
        val url = intent.getStringExtra(EXTRA_ROOM_URL) ?: return
        val token = intent.getStringExtra(EXTRA_ROOM_TOKEN) ?: return
        following = true
        b.playerView.useController = false
        roomJob = lifecycleScope.launch {
            val client = runCatching { RoomSyncClient(url) }.getOrElse {
                b.errorText.text = "Invalid host address. Stop following in Options, then pair again."
                b.errorText.visibility = View.VISIBLE
                return@launch
            }
            var failures = 0
            while (isActive) {
                try {
                    val state = withContext(Dispatchers.IO) { client.state(token) }
                    failures = 0
                    if (engine?.channel != state.channel) {
                        queue = listOf(state.channel); index = 0
                        ContentRepository.remember(queue)
                        queueKey = ContentRepository.createQueue(this@PlayerActivity, queue)
                        engine?.play(state.channel, 0, state.playing)
                        showInfo()
                    }
                    val target = RoomSyncMath.targetPosition(state, System.currentTimeMillis(), windowStart())
                    engine?.player?.let { p ->
                        // Seeking on every poll while still buffering can prevent startup entirely.
                        if (target != null && p.playbackState == androidx.media3.common.Player.STATE_READY && p.isCurrentMediaItemSeekable && RoomSyncMath.needsSeek(p.currentPosition, target)) p.seekTo(target.coerceAtLeast(0))
                        p.playWhenReady = state.playing
                        if(p.playbackState == androidx.media3.common.Player.STATE_READY) b.errorText.visibility=View.GONE
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failures++
                    if (failures >= 3) {
                        engine?.player?.pause()
                        b.errorText.text = "Reconnecting to the host TV… Menu opens Options to stop following."
                        b.errorText.visibility = View.VISIBLE
                    }
                }
                delay(if(failures >= 3) 5000 else 1500)
            }
        }
    }

    private fun stopRoom() { roomJob?.cancel(); roomJob = null; host?.stop(); host = null; following = false; b.playerView.useController = true }

    override fun onSaveInstanceState(outState: Bundle) {
        engine?.let { position = it.player.currentPosition; playing = it.player.playWhenReady; it.save() }
        outState.putString(EXTRA_QUEUE, queueKey)
        outState.putInt(EXTRA_INDEX, index)
        outState.putInt("previous", previous)
        outState.putLong("position", position ?: 0)
        outState.putBoolean("playing", playing)
        outState.putLong("sleepAt", sleepAt)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        engine?.let { position = it.player.currentPosition; playing = it.player.playWhenReady }
        stopRoom()
        handler.removeCallbacksAndMessages(null)
        b.playerView.player = null
        engine?.release(); engine = null
        super.onStop()
    }

    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_QUEUE = "queue"
        const val EXTRA_INDEX = "index"
        const val EXTRA_ROOM_URL = "room_url"
        const val EXTRA_ROOM_TOKEN = "room_token"
    }
}
