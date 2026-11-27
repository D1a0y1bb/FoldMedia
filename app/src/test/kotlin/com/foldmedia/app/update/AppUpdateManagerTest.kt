package com.foldmedia.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun comparesSemanticVersionsProperly() {
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.2", "1.0.1"))
        assertTrue(AppUpdateManager.isNewerVersion("1.1.0", "1.0.2"))
        assertTrue(AppUpdateManager.isNewerVersion("2.0", "1.9.9"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.2", "1.0.2"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.1", "1.0.2"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.2"))
    }

    @Test
    fun selectsArm64ApkFirstAndFallsBackToUniversal() {
        val releaseJson = """
            {
              "tag_name": "v1.0.3",
              "name": "FoldMedia v1.0.3",
              "body": "新增功能与修复",
              "html_url": "https://github.com/D1a0y1bb/FoldMedia/releases/tag/v1.0.3",
              "published_at": "2026-11-28T02:00:00Z",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "FoldMedia-v1.0.3-universal-release.apk",
                  "size": 208000000,
                  "browser_download_url": "https://github.com/D1a0y1bb/FoldMedia/releases/download/v1.0.3/FoldMedia-v1.0.3-universal-release.apk"
                },
                {
                  "name": "FoldMedia-v1.0.3-arm64-v8a-release.apk",
                  "size": 63000000,
                  "browser_download_url": "https://github.com/D1a0y1bb/FoldMedia/releases/download/v1.0.3/FoldMedia-v1.0.3-arm64-v8a-release.apk"
                }
              ]
            }
        """.trimIndent()

        val armInfo = AppUpdateManager.parseGitHubReleaseJson(releaseJson, listOf("arm64-v8a", "armeabi-v7a"))
        assertNotNull(armInfo)
        assertEquals("v1.0.3", armInfo!!.tagName)
        assertEquals("1.0.3", armInfo.versionName)
        assertEquals("FoldMedia-v1.0.3-arm64-v8a-release.apk", armInfo.apkFileName)

        val fallbackInfo = AppUpdateManager.parseGitHubReleaseJson(releaseJson, listOf("armeabi-v7a"))
        assertNotNull(fallbackInfo)
        assertEquals("FoldMedia-v1.0.3-universal-release.apk", fallbackInfo!!.apkFileName)

        val draftJson = releaseJson.replace("\"draft\": false", "\"draft\": true")
        assertNull(AppUpdateManager.parseGitHubReleaseJson(draftJson, listOf("arm64-v8a")))
    }
}
