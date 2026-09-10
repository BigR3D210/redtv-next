package com.redtv.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.FilterInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Http {
    @Volatile var userAgent = "RedTV/1.2 (Android)"
    val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(true).build() }
    fun getString(url: String, agent: String = userAgent): String = withStream(url, agent) { it.reader(Charsets.UTF_8).readText() }
    fun <T> withStream(url: String, agent: String = userAgent, consume: (InputStream) -> T): T {
        try {
            val request = Request.Builder().url(url).header("User-Agent", agent).build()
            client.newCall(request).execute().use { response ->
                if(!response.isSuccessful) throw IOException("Source request failed (HTTP ${response.code})")
                val body = response.body ?: throw IOException("Source returned an empty response")
                val input = BufferedInputStream(body.byteStream())
                input.mark(2); val a = input.read(); val b = input.read(); input.reset()
                val decoded = if(a == 0x1f && b == 0x8b) GZIPInputStream(input) else input
                return consume(object: FilterInputStream(decoded) {
                    var count = 0L
                    fun checked(n: Int): Int { if(n > 0) count += n; if(count > 64L*1024*1024) throw IOException("Source response exceeds 64 MB limit"); return n }
                    override fun read(): Int { val n = super.read(); if(n >= 0) checked(1); return n }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = checked(`in`.read(b, off, len))
                })
            }
        } catch(e: Exception) {
            // Network exceptions often embed credential-bearing URLs. Do not expose their messages/causes.
            throw IOException(if(e.message?.startsWith("Source ") == true) e.message else "Unable to load source; check address, credentials and connection")
        }
    }
}
