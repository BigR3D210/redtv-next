package com.redtv.app.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.redtv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** User-initiated update; the platform installer retains the final install decision. */
object Updater {
    data class Info(val buildNumber: Int, val label: String, val apkUrl: String)
    private const val MAX_APK_BYTES = 150L * 1024 * 1024
    private val downloads by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        // Let failed network/parse operations reach the UI instead of claiming this is latest.
        val json = Http.getString("https://api.github.com/repos/${BuildConfig.GH_OWNER}/${BuildConfig.GH_REPO}/releases/latest")
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("draft")?.asBoolean == true || root.get("prerelease")?.asBoolean == true) return@withContext null
        val tag = root.get("tag_name")?.asString ?: throw IOException("Release metadata is missing a version")
        if (!UpdatePolicy.isNewer(tag, BuildConfig.VERSION_CODE)) return@withContext null
        val assets = root.getAsJsonArray("assets") ?: throw IOException("Release metadata is missing assets")
        val asset = assets.map { it.asJsonObject }.firstOrNull {
            UpdatePolicy.releaseAsset(it.get("name")?.asString.orEmpty(),
                it.get("browser_download_url")?.asString.orEmpty(), BuildConfig.GH_OWNER, BuildConfig.GH_REPO)
        } ?: return@withContext null
        Info(UpdatePolicy.versionCode(tag)!! - 100, root.get("name")?.asString?.takeIf { it.isNotBlank() } ?: tag,
            asset.get("browser_download_url").asString)
    }

    suspend fun downloadAndInstall(context: Context, apkUrl: String) = withContext(Dispatchers.IO) {
        require(UpdatePolicy.releaseAsset("app-release.apk", apkUrl, BuildConfig.GH_OWNER, BuildConfig.GH_REPO)) { "Untrusted update address" }
        val partial = File.createTempFile("redtv-update-", ".part", context.cacheDir)
        val completed = File(context.cacheDir, "RedTV-update.apk")
        try {
            download(apkUrl, partial)
            verifyPackage(context, partial)
            if (completed.exists() && !completed.delete()) throw IOException("Unable to replace previous update")
            if (!partial.renameTo(completed)) throw IOException("Unable to save verified update")
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", completed)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    private suspend fun download(initial: String, file: File) {
        var url = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if (!UpdatePolicy.allowedDownload(url)) throw IOException("Untrusted update redirect")
            downloads.newCall(Request.Builder().url(url).header("User-Agent", Http.userAgent).build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: throw IOException("Invalid update redirect")
                } else {
                    if (!response.isSuccessful) throw IOException("Update download failed (HTTP ${response.code})")
                    val body = response.body ?: throw IOException("Empty update download")
                    val expected = body.contentLength()
                    if (expected > MAX_APK_BYTES) throw IOException("Update exceeds size limit")
                    var total = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_APK_BYTES) throw IOException("Update exceeds size limit")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (total == 0L || (expected >= 0 && total != expected)) throw IOException("Incomplete update download")
                    return
                }
            }
        }
        throw IOException("Too many update redirects")
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val update = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Downloaded file is not a valid APK")
        val installed = manager.getPackageInfo(context.packageName, flags)
        if (update.packageName != context.packageName) throw IOException("Update is for another app")
        val newVersion = if (Build.VERSION.SDK_INT >= 28) update.longVersionCode else update.versionCode.toLong()
        val oldVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (newVersion <= oldVersion) throw IOException("Downloaded update is not newer")
        val currentSigners = signatures(installed)
        val newSigners = signatures(update)
        if (currentSigners.isEmpty() || newSigners != currentSigners) throw IOException("Update signing identity does not match this installation")
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
}
