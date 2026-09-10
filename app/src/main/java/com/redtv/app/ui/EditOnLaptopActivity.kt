package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityEditLaptopBinding
import com.redtv.app.model.Channel
import com.redtv.app.model.ContentKind
import com.redtv.app.net.ConfigServer
import com.redtv.app.net.LanAuth
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EditOnLaptopActivity : AppCompatActivity() {
    private var server: ConfigServer? = null
    private lateinit var b: ActivityEditLaptopBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditLaptopBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnDone.setOnClickListener { finish() }
        b.btnDone.requestFocus()
    }
    override fun onStart() {
        super.onStart()
        val code = LanAuth.newCode()
        b.codeText.text = code
        val ip = localIp()
        if (ip == null) { b.statusText.text = "Connect to your home Wi-Fi or Ethernet, then reopen the editor."; return }
        val srv = ConfigServer(8080, Prefs(this), code, assets.open("remote.html").bufferedReader().use { it.readText() }, onPlay = { url, name, live ->
            val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val ch = Channel("direct_$id", name, url, kind = if (live) ContentKind.LIVE else ContentKind.MOVIE)
            val key = ContentRepository.createQueue(this, listOf(ch))
            runOnUiThread { startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_QUEUE, key).putExtra(PlayerActivity.EXTRA_INDEX, 0)) }
        }) {
            ContentRepository.dirty = true
            runOnUiThread { b.statusText.text = "Changes saved. Press Done to reload your library." }
        }
        try {
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); server = srv
            b.urlText.text = "http://$ip:8080"
            b.statusText.text = "Pair within 10 minutes. Trusted home network only. Closing this screen signs out browsers."
        } catch (_: Exception) { srv.stop(); b.urlText.text = "Editor unavailable"; b.statusText.text = "Port 8080 is busy. Close other editor sessions and reopen." }
    }
    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
    override fun onStop() { server?.stop(); server = null; super.onStop() }
}


