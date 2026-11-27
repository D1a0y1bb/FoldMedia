package com.foldmedia.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.foldmedia.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

enum class InstallLaunchResult {
    LAUNCHED,
    NEED_PERMISSION,
    FAILED
}

/**
 * Checks GitHub Releases for newer versions, streams matching ABI APK packages locally,
 * and hands the verified APK to the Android package installer via [FileProvider].
 */
class AppUpdateManager(
    private val appContext: Context,
    httpClient: OkHttpClient
) {
    private val updateHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FoldMedia/$currentVersion")
                .build()
            val body = updateHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub 版本检查失败 (HTTP ${resp.code})")
                }
                resp.body.string()
            }
            val parsed = parseGitHubReleaseJson(body, Build.SUPPORTED_ABIS.toList())
                ?: return@runCatching null
            if (isNewerVersion(parsed.versionName, currentVersion)) parsed else null
        }
    }

    suspend fun downloadUpdateApk(
        release: AppReleaseInfo,
        onProgress: (Float, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { old ->
            if (old.name != release.apkFileName) old.delete()
        }
        val destFile = File(updatesDir, release.apkFileName)
        val candidates = listOf(
            release.apkDownloadUrl,
            "https://gh-proxy.com/${release.apkDownloadUrl}"
        ).distinct()

        var lastError: Throwable? = null
        for ((idx, url) in candidates.withIndex()) {
            val attempt = runCatching {
                if (destFile.exists()) destFile.delete()
                onProgress(
                    0.02f,
                    if (idx == 0) "正在连接 GitHub 下载..." else "主线路失败，尝试加速镜像下载..."
                )
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "FoldMedia/${BuildConfig.VERSION_NAME}")
                    .build()
                updateHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("下载安装包失败 (HTTP ${resp.code})")
                    }
                    val total = resp.body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                    val tmpFile = File(updatesDir, "${release.apkFileName}.part")
                    if (tmpFile.exists()) tmpFile.delete()
                    resp.body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                copied += read
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 150L || read == 0) {
                                    lastReport = now
                                    val frac = if (total > 0L) (copied.toFloat() / total.toFloat()).coerceIn(0f, 0.99f) else 0.5f
                                    val mbDone = String.format("%.1f", copied / (1024.0 * 1024.0))
                                    val mbTotal = if (total > 0L) String.format("%.1f", total / (1024.0 * 1024.0)) else "?"
                                    onProgress(frac, "正在下载 ${mbDone} MB / ${mbTotal} MB")
                                }
                            }
                            output.flush()
                        }
                    }
                    require(isValidApkFile(tmpFile)) { "下载的文件不是有效的 Android APK 安装包" }
                    if (destFile.exists()) destFile.delete()
                    check(tmpFile.renameTo(destFile)) { "写入更新缓存失败" }
                }
                onProgress(1f, "安装包下载完成，准备安装...")
                destFile
            }
            if (attempt.isSuccess) return@withContext attempt
            lastError = attempt.exceptionOrNull()
        }
        Result.failure(lastError ?: IllegalStateException("安装包下载失败"))
    }

    fun launchApkInstaller(context: Context, apkFile: File): InstallLaunchResult {
        if (!apkFile.exists() || !isValidApkFile(apkFile)) return InstallLaunchResult.FAILED
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (runCatching { context.startActivity(permIntent); true }.getOrDefault(false)) {
                InstallLaunchResult.NEED_PERMISSION
            } else {
                InstallLaunchResult.FAILED
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(installIntent); true }.getOrDefault(false)) {
            InstallLaunchResult.LAUNCHED
        } else {
            InstallLaunchResult.FAILED
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1_000_000L) return false
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        return read == 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    companion object {
        const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/D1a0y1bb/FoldMedia/releases/latest"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun isNewerVersion(remote: String, current: String): Boolean {
            val rParts = parseVersionSegments(remote)
            val cParts = parseVersionSegments(current)
            val count = maxOf(rParts.size, cParts.size)
            for (i in 0 until count) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        private fun parseVersionSegments(version: String): List<Int> {
            val cleaned = version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
            if (cleaned.isBlank()) return listOf(0)
            return cleaned.split('.').map { seg -> seg.toIntOrNull() ?: 0 }
        }

        internal fun parseGitHubReleaseJson(
            rawJson: String,
            supportedAbis: List<String>
        ): AppReleaseInfo? {
            val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
            if (root["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
            if (root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null

            val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tagName.isBlank()) return null
            val versionName = tagName.removePrefix("v").removePrefix("V")
            val title = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: tagName
            val releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val assets = root["assets"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
            val apkAssets = assets.filter { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true &&
                    !asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            }
            if (apkAssets.isEmpty()) return null

            val chosenAsset = selectMatchingApkAsset(apkAssets, supportedAbis) ?: return null
            val apkName = chosenAsset["name"]?.jsonPrimitive?.contentOrNull ?: "FoldMedia-$tagName.apk"
            val apkUrl = chosenAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val apkSize = chosenAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

            return AppReleaseInfo(
                tagName = tagName,
                versionName = versionName,
                title = title,
                releaseNotes = releaseNotes,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                apkSizeBytes = apkSize,
                publishedAt = publishedAt
            )
        }

        private fun selectMatchingApkAsset(
            apkAssets: List<JsonObject>,
            supportedAbis: List<String>
        ): JsonObject? {
            for (abi in supportedAbis) {
                val abiMatch = apkAssets.firstOrNull { asset ->
                    asset["name"]?.jsonPrimitive?.contentOrNull?.contains(abi, ignoreCase = true) == true
                }
                if (abiMatch != null) return abiMatch
            }
            return apkAssets.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.contains("universal", ignoreCase = true) == true
            } ?: apkAssets.firstOrNull()
        }
    }
}
