package com.omargarcia.blocky.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val releaseHtmlUrl: String
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_RELEASES_API_URL = "https://api.github.com/repos/ogoxx/Blocky/releases/latest"
    private const val DEFAULT_RELEASE_PAGE = "https://github.com/ogoxx/Blocky/releases/latest"

    /**
     * Compares two semantic version strings (e.g. "1.0.18" vs "v1.0.19").
     * Returns true if latest is strictly newer than current.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = parseVersionParts(current)
        val latestParts = parseVersionParts(latest)
        if (currentParts.isEmpty() || latestParts.isEmpty()) return false

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val late = latestParts.getOrElse(i) { 0 }
            if (late > curr) return true
            if (late < curr) return false
        }
        return false
    }

    private fun parseVersionParts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val base = cleaned.split("-", "+", "_")[0]
        return base.split(".")
            .mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Resolves the current app's versionName dynamically from Context.
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * Fetches the latest release from the GitHub repository API.
     * Executes safely on Dispatchers.IO.
     */
    suspend fun checkLatestRelease(currentVersion: String): Result<AppUpdateInfo> {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_RELEASES_API_URL)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "Blocky-Android-App")
                    instanceFollowRedirects = true
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(
                        Exception("GitHub API returned HTTP $responseCode")
                    )
                }

                val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }

                val json = JSONObject(responseBody)
                val tagName = json.optString("tag_name", "")
                val releaseTitle = json.optString("name", tagName).ifBlank { tagName }
                val releaseNotes = json.optString("body", "").trim()
                val htmlUrl = json.optString("html_url", DEFAULT_RELEASE_PAGE)

                var apkDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            if (!apkDownloadUrl.isNullOrBlank()) break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(currentVersion, tagName)

                Result.success(
                    AppUpdateInfo(
                        isUpdateAvailable = hasUpdate,
                        currentVersion = currentVersion,
                        latestVersion = tagName,
                        releaseTitle = releaseTitle,
                        releaseNotes = releaseNotes,
                        apkDownloadUrl = apkDownloadUrl,
                        releaseHtmlUrl = htmlUrl
                    )
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error checking for updates from GitHub", t)
                Result.failure(t)
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {}
            }
        }
    }
}
