package com.redtv.app.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.redtv.app.data.Prefs
import com.redtv.app.data.TextStore
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Source
import com.redtv.app.model.SourceProfile
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigHttpTest {
    @Test fun pairedEditorKeepsSecretsRequiresCsrfAndRejectsStaleProfile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("redtv", 0).edit().clear().commit()
        val values = mutableMapOf<String, String>()
        val store = object : TextStore {
            override fun write(name: String, value: String) { values[name] = value }
            override fun read(name: String) = values[name]
            override fun remove(name: String) { values.remove(name) }
        }
        val prefs = Prefs(context, store)
        val original = SourceProfile("first", "My source", manual = RemoteConfig(userAgent = "Kept agent", source = Source("xtream", host="http://192.168.1.2", username="SECRET-USER", password="SECRET-PASSWORD")))
        prefs.upsertProfile(original)
        var saved = 0
        val server = ConfigServer(0, prefs, "123456", "<html>Pair browser</html>", { _, _, _ -> }, { saved++ })
        server.start()
        try {
            val http = OkHttpClient()
            val base = "http://127.0.0.1:${server.listeningPort}"
            var cookie = ""
            var csrf = ""
            fun get(path: String) = http.newCall(Request.Builder().url(base + path).header("Cookie", cookie).build()).execute()
            fun post(path: String, fields: Map<String,String>, sendCsrf: Boolean = true): okhttp3.Response {
                val form = FormBody.Builder(); fields.forEach { (k,v) -> form.add(k,v) }
                return http.newCall(Request.Builder().url(base + path).header("Cookie",cookie).header("X-CSRF-Token",if(sendCsrf) csrf else "").post(form.build()).build()).execute()
            }
            get("/api").use { assertEquals(403, it.code) }
            get("/?code=123456").use { assertFalse(it.body!!.string().contains("SECRET")) }
            post("/pair",mapOf("code" to "123456")).use {
                assertEquals(200,it.code)
                val header = it.header("Set-Cookie")!!
                assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict"))
                cookie = header.substringBefore(';')
            }
            get("/api").use {
                assertEquals(200,it.code); val body = it.body!!.string()
                assertFalse(body.contains("SECRET")); assertFalse(body.contains("192.168.1.2"))
                csrf = Gson().fromJson(body,Map::class.java)["csrf"] as String
            }
            post("/source",mapOf("type" to "xtream", "name" to "Changed"),false).use { assertEquals(403,it.code) }
            assertEquals(original,prefs.activeProfile())
            post("/source",mapOf("type" to "xtream", "name" to "Changed", "pass" to "")).use { assertEquals(200,it.code) }
            assertEquals(original.manual,prefs.activeProfile()!!.manual)
            assertEquals("Changed",prefs.activeProfile()!!.name)
            assertEquals(1,saved)
            post("/channels",mapOf("ids" to "invented-channel", "action" to "hidden", "value" to "true")).use { assertEquals(400,it.code) }
            assertFalse(prefs.isHidden("invented-channel"))
            prefs.upsertProfile(original.copy(id="other"))
            post("/source",mapOf("type" to "xtream", "name" to "Wrong target")).use { assertEquals(403,it.code) }
            assertEquals("My source",prefs.activeProfile()!!.name)
        } finally { server.stop() }
    }
}
