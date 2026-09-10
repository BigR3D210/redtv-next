package com.redtv.app.net

import java.net.URI

/** Matches the checked release workflow: b<run> has Android versionCode 100 + run. */
object UpdatePolicy {
    fun versionCode(tag: String): Int? {
        val run = Regex("b([1-9][0-9]*)").matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return run.takeIf { it <= 2_099_999_900L }?.plus(100)?.toInt()
    }
    fun isNewer(tag: String, installed: Int) = (versionCode(tag) ?: 0) > installed
    fun releaseAsset(name: String, url: String, owner: String, repo: String): Boolean {
        if (name != "app-release.apk" || !allowedDownload(url)) return false
        val uri = URI(url)
        return uri.host == "github.com" && uri.path.startsWith("/$owner/$repo/releases/download/") &&
            uri.path.endsWith("/app-release.apk") && uri.rawQuery == null
    }
    fun allowedDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && uri.host in setOf(
                "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)
}
