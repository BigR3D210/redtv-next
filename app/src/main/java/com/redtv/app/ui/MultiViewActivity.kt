package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.model.Channel

/** Each pane owns its decoder. Muting happens before playback starts. */
@UnstableApi
class MultiViewActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private val channels = mutableListOf<Channel>()
    private val engines = mutableListOf<PlaybackEngine>()
    private val positions = mutableMapOf<String, Long>()
    private var active = 0
    private var capacity = 2
    private var queueKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ContentRepository.initialize(this)
        ContentRepository.restore(prefs)
        queueKey = savedInstanceState?.getString(PlayerActivity.EXTRA_QUEUE) ?: intent.getStringExtra(PlayerActivity.EXTRA_QUEUE)
        channels.addAll(queueKey?.let { ContentRepository.readQueue(this, it) }.orEmpty().filter { !it.isSeries && it.streamUrl.isNotBlank() }.take(4))
        if (channels.isEmpty()) channels.addAll(intent.getStringArrayListExtra(PlayerActivity.EXTRA_IDS).orEmpty().mapNotNull { ContentRepository.channelById(it) }.filter { !it.isSeries }.take(4))
        capacity = if ((savedInstanceState?.getInt("capacity") ?: intent.getIntExtra(EXTRA_PANES, channels.size)) > 2) 4 else 2
        while (channels.size > capacity) channels.removeAt(channels.lastIndex)
        active = (savedInstanceState?.getInt("active") ?: 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        savedInstanceState?.getBundle("positions")?.let { bundle -> bundle.keySet().forEach { positions[it] = bundle.getLong(it) } }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
        setContentView(root)
    }

    override fun onStart() { super.onStart(); render() }

    private fun rememberQueue() { queueKey = ContentRepository.createQueue(this, channels) }

    private fun releasePlayers() {
        engines.forEach { engine -> engine.channel?.let { positions[it.id] = engine.player.currentPosition }; engine.release() }
        engines.clear()
    }

    private fun render() {
        releasePlayers()
        root.removeAllViews()
        val help = Button(this).apply {
            text = "${capacity} views • Select a pane for audio and options • Menu changes layout"
            setOnClickListener { layoutOptions() }
        }
        root.addView(help, LinearLayout.LayoutParams(-1, -2))
        val rows = if (capacity == 4) 2 else 1
        repeat(rows) { rowIndex ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            repeat(2) { col ->
                val i = rowIndex * 2 + col
                val frame = FrameLayout(this).apply {
                    isFocusable = true
                    isClickable = true
                    setPadding(4, 4, 4, 4)
                    setBackgroundColor(if (i == active) 0xFFE53935.toInt() else 0xFF333333.toInt())
                    setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else .85f }
                    setOnClickListener { if (i < channels.size) { setAudio(i); paneOptions(i) } else pickChannel(i) }
                }
                row.addView(frame, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(3, 3, 3, 3) })
                val ch = channels.getOrNull(i)
                if (ch == null) {
                    frame.addView(TextView(this).apply { text = "+ Add channel"; setTextColor(-1); gravity = android.view.Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    val view = PlayerView(this).apply { useController = false; isFocusable = false; resizeMode = prefs.resizeMode }
                    frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                    val label = TextView(this).apply { text = prefs.displayChannel(ch).name; setTextColor(-1); setBackgroundColor(0xAA000000.toInt()); setPadding(12, 8, 12, 8) }
                    frame.addView(label, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
                    val engine = PlaybackEngine(this, prefs, { status -> label.text = prefs.displayChannel(ch).name + if (status != null) " — $status" else "" })
                    engine.player.volume = if (i == active) 1f else 0f
                    view.player = engine.player
                    engine.play(ch, positions[ch.id] ?: if (ch.isVod) prefs.resumePosition(ch.id) else 0)
                    engines.add(engine)
                }
                if (i == active) frame.requestFocus()
            }
        }
        rememberQueue()
    }

    private fun setAudio(i: Int) { active = i; engines.forEachIndexed { n, e -> e.player.volume = if (n == i) 1f else 0f } }

    private fun paneOptions(i: Int) {
        AlertDialog.Builder(this).setTitle(channels[i].name).setItems(arrayOf("Listen to this pane", "Full screen", "Replace channel", "Remove pane", "Retry")) { _, option ->
            when (option) {
                0 -> setAudio(i)
                1 -> {
                    engines.getOrNull(i)?.save()
                    val key = ContentRepository.createQueue(this, listOf(channels[i]))
                    startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_QUEUE, key))
                }
                2 -> pickChannel(i)
                3 -> { channels.removeAt(i); active = active.coerceAtMost((channels.size - 1).coerceAtLeast(0)); render() }
                4 -> engines.getOrNull(i)?.retryManually()
            }
        }.show()
    }

    private fun pickChannel(index: Int) {
        val choices = ContentRepository.allChannels().filter { !it.isSeries && it.streamUrl.isNotBlank() && !prefs.isHidden(it.id) }.sortedBy { it.name.lowercase() }
        val input = android.widget.EditText(this).apply { hint = "Search channels"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Find a channel").setView(input).setPositiveButton("Search") { _, _ ->
            val matches = choices.filter { it.name.contains(input.text.toString(), true) }.take(200)
            AlertDialog.Builder(this).setTitle("Choose a channel").setItems(matches.map { prefs.displayChannel(it).name }.toTypedArray()) { _, chosen ->
                if (index < channels.size) channels[index] = matches[chosen] else channels.add(matches[chosen])
                render()
            }.setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun layoutOptions() {
        AlertDialog.Builder(this).setTitle("Multiview").setMessage("More panes need more hardware decoders and provider connections. If a pane cannot play, use two views or full screen.")
            .setPositiveButton(if (capacity == 2) "Use four views" else "Use two views") { _, _ ->
                capacity = if (capacity == 2) 4 else 2
                while (channels.size > capacity) channels.removeAt(channels.lastIndex)
                active = active.coerceAtMost((channels.size - 1).coerceAtLeast(0)); render()
            }.setNegativeButton("Close", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { layoutOptions(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        engines.forEach { engine -> engine.channel?.let { positions[it.id] = engine.player.currentPosition }; engine.save() }
        rememberQueue()
        outState.putString(PlayerActivity.EXTRA_QUEUE, queueKey)
        outState.putInt("capacity", capacity); outState.putInt("active", active)
        outState.putBundle("positions", Bundle().apply { positions.forEach { (id, pos) -> putLong(id, pos) } })
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { releasePlayers(); super.onStop() }
    companion object { const val EXTRA_PANES = "panes" }
}
