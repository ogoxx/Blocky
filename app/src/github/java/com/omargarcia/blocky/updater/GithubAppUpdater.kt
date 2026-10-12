package com.omargarcia.blocky.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.omargarcia.blocky.R
import com.omargarcia.blocky.data.SettingsManager
import com.omargarcia.blocky.utils.AppUpdateInfo
import com.omargarcia.blocky.utils.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class GithubAppUpdater(private val context: Context) : AppUpdater {

    private val settingsManager = SettingsManager(context)
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    override val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var cachedUpdateInfo: AppUpdateInfo? = null

    override fun checkForUpdates(scope: CoroutineScope, isUserInitiated: Boolean) {
        val currentVersion = UpdateChecker.getCurrentVersionName(context)
        val now = System.currentTimeMillis()
        val oneDayMillis = 24 * 60 * 60 * 1000L

        if (!isUserInitiated && now - settingsManager.lastUpdateCheckTimestamp < oneDayMillis) {
            return
        }

        _status.value = UpdateStatus.Checking
        scope.launch(Dispatchers.IO) {
            val result = UpdateChecker.checkLatestRelease(currentVersion)
            settingsManager.lastUpdateCheckTimestamp = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                result.onSuccess { info ->
                    if (info.isUpdateAvailable) {
                        cachedUpdateInfo = info
                        settingsManager.cachedLatestVersion = info.latestVersion
                        settingsManager.hasPendingUpdate = true
                        _status.value = UpdateStatus.Available(
                            currentVersion = info.currentVersion,
                            latestVersion = info.latestVersion,
                            releaseNotes = info.releaseNotes
                        )
                    } else {
                        cachedUpdateInfo = null
                        settingsManager.hasPendingUpdate = false
                        _status.value = UpdateStatus.UpToDate(currentVersion)
                    }
                }.onFailure { err ->
                    Log.e("GithubAppUpdater", "Error checking updates", err)
                    _status.value = UpdateStatus.Error(
                        messageResId = R.string.updates_toast_error,
                        customMessage = err.message
                    )
                }
            }
        }
    }

    override fun startUpdate(activity: Activity, scope: CoroutineScope) {
        val downloadUrl = cachedUpdateInfo?.apkDownloadUrl ?: cachedUpdateInfo?.releaseHtmlUrl
        if (downloadUrl.isNullOrBlank()) {
            _status.value = UpdateStatus.Error(customMessage = "No APK available for download")
            return
        }

        if (!downloadUrl.endsWith(".apk", ignoreCase = true)) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                _status.value = UpdateStatus.Error(customMessage = e.message)
            }
            return
        }

        val curVer = cachedUpdateInfo?.currentVersion ?: ""
        val latVer = cachedUpdateInfo?.latestVersion ?: ""
        val notes = cachedUpdateInfo?.releaseNotes ?: ""

        _status.value = UpdateStatus.Downloading(
            progressPercent = 0,
            currentVersion = curVer,
            latestVersion = latVer,
            releaseNotes = notes
        )
        scope.launch(Dispatchers.IO) {
            val apkFile = File(context.cacheDir, "Blocky-update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                var currentUrl = downloadUrl
                var redirectCount = 0
                var currentConnection: HttpURLConnection

                while (true) {
                    val url = URL(currentUrl)
                    currentConnection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Blocky-Android-App")
                    }

                    val code = currentConnection.responseCode
                    if ((code == HttpURLConnection.HTTP_MOVED_TEMP ||
                                code == HttpURLConnection.HTTP_MOVED_PERM ||
                                code == 307 ||
                                code == 308) && redirectCount < 5
                    ) {
                        currentUrl = currentConnection.getHeaderField("Location")
                        currentConnection.disconnect()
                        redirectCount++
                    } else {
                        connection = currentConnection
                        break
                    }
                }

                val totalLength = connection.contentLength
                inputStream = connection.inputStream
                outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgress = 0

                while (inputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (totalLength > 0) {
                        val progress = ((totalBytesRead * 100) / totalLength).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            _status.value = UpdateStatus.Downloading(
                                progressPercent = progress,
                                currentVersion = curVer,
                                latestVersion = latVer,
                                releaseNotes = notes
                            )
                        }
                    }
                }
                outputStream.flush()

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                withContext(Dispatchers.Main) {
                    _status.value = UpdateStatus.ReadyToInstall(
                        apkUri = apkUri,
                        currentVersion = curVer,
                        latestVersion = latVer,
                        releaseNotes = notes
                    )
                    launchInstaller(activity, apkUri)
                }
            } catch (e: Exception) {
                Log.e("GithubAppUpdater", "Error downloading APK", e)
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                withContext(Dispatchers.Main) {
                    _status.value = UpdateStatus.Error(
                        messageResId = R.string.updates_toast_error,
                        customMessage = e.message
                    )
                }
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun launchInstaller(activity: Activity, apkUri: Uri) {
        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("GithubAppUpdater", "Error opening package installer", e)
            _status.value = UpdateStatus.Error(customMessage = e.message)
        }
    }

    override fun dismissUpdate() {
        _status.value = UpdateStatus.Idle
    }
}
