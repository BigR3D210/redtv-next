package com.redtv.app.net

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val prefix = "https://github.com/owner/repo/releases/download/b15/"
    @Test fun comparesActualVersionCodeAndIgnoresDebugArtifacts() {
        assertEquals(115, UpdatePolicy.versionCode("b15"))
        assertFalse(UpdatePolicy.isNewer("b15", 115))
        assertTrue(UpdatePolicy.isNewer("b15", 114))
        assertFalse(UpdatePolicy.releaseAsset("app-debug.apk", prefix + "app-debug.apk", "owner", "repo"))
        assertTrue(UpdatePolicy.releaseAsset("app-release.apk", prefix + "app-release.apk", "owner", "repo"))
    }
    @Test fun unrelatedVersionLabelsCannotMasqueradeAsBuildNumbers() {
        assertNull(UpdatePolicy.versionCode("v1.2.15"))
        assertNull(UpdatePolicy.versionCode("b2147483647"))
        assertNull(UpdatePolicy.versionCode("b0"))
    }
    @Test fun rejectsInsecureOrUnrelatedDownloadTargets() {
        assertFalse(UpdatePolicy.releaseAsset("app-release.apk", prefix.replace("https", "http") + "app-release.apk", "owner", "repo"))
        assertFalse(UpdatePolicy.releaseAsset("app-release.apk", "https://github.com/other/repo/releases/download/b15/app-release.apk", "owner", "repo"))
        assertFalse(UpdatePolicy.allowedDownload("https://github.com.attacker.test/payload"))
        assertFalse(UpdatePolicy.allowedDownload("https://user:pass@github.com/payload"))
        assertTrue(UpdatePolicy.allowedDownload("https://release-assets.githubusercontent.com/asset?signature=abc"))
    }
}
