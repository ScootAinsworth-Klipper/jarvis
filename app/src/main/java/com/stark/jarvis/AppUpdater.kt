package com.stark.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
    val versionName: String,
    val releaseNotes: String,
    val assetApiUrl: String,
    val browserDownloadUrl: String,
    val assetSize: Long
)

class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(githubToken: String): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBuilder = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "JARVIS-Android-Updater")

            val token = githubToken.trim()
            if (token.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 404 && token.isEmpty()) {
                    throw IllegalStateException(
                        "Repository is private. Add your GitHub token in Settings to check for updates."
                    )
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub update check failed (${response.code}).")
                }

                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim()
                val remoteVersion = tag.removePrefix("v").removePrefix("V")
                if (remoteVersion.isBlank() || compareVersions(remoteVersion, BuildConfig.VERSION_NAME) <= 0) {
                    return@runCatching null
                }

                val assets = json.optJSONArray("assets") ?: return@runCatching null
                var apkAsset: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    if (item.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkAsset = item
                        break
                    }
                }
                if (apkAsset == null) return@runCatching null

                AppReleaseInfo(
                    versionName = remoteVersion,
                    releaseNotes = json.optString("body").ifBlank { "New J.A.R.V.I.S. build available." },
                    assetApiUrl = apkAsset.optString("url"),
                    browserDownloadUrl = apkAsset.optString("browser_download_url"),
                    assetSize = apkAsset.optLong("size", 0L)
                )
            }
        }
    }

    suspend fun downloadUpdateApk(
        release: AppReleaseInfo,
        githubToken: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val token = githubToken.trim()
            val targetUrl = if (token.isNotEmpty() && release.assetApiUrl.isNotBlank()) {
                release.assetApiUrl
            } else {
                release.browserDownloadUrl
            }

            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .addHeader("User-Agent", "JARVIS-Android-Updater")
            if (token.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
                reqBuilder.addHeader("Accept", "application/octet-stream")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("APK download failed (${response.code}).")
                }
                val body = response.body ?: throw IllegalStateException("Empty download response.")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: release.assetSize

                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "jarvis-update.apk")

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(1f)
                apkFile
            }
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun launchInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun launchApkInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun compareVersions(remote: String, current: String): Int {
        val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val cParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r != c) return r.compareTo(c)
        }
        return 0
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/ScootAinsworth-Klipper/jarvis/releases/latest"
    }
}
