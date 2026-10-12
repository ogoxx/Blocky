package com.omargarcia.blocky.updater

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.omargarcia.blocky.utils.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayStoreAppUpdater(private val context: Context) : AppUpdater {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context)
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    override val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var appUpdateInfo: AppUpdateInfo? = null
    private var currentVersionName: String = ""
    private var latestVersionName: String = ""

    private val installStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val totalBytes = state.totalBytesToDownload()
                val bytesDownloaded = state.bytesDownloaded()
                val progress = if (totalBytes > 0) {
                    ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                } else 0
                _status.value = UpdateStatus.Downloading(
                    progressPercent = progress,
                    currentVersion = currentVersionName,
                    latestVersion = latestVersionName
                )
            }
            InstallStatus.DOWNLOADED -> {
                _status.value = UpdateStatus.ReadyToInstall(
                    currentVersion = currentVersionName,
                    latestVersion = latestVersionName
                )
            }
            InstallStatus.FAILED -> {
                _status.value = UpdateStatus.Error(customMessage = "Play Store update failed")
            }
            else -> {}
        }
    }

    init {
        appUpdateManager.registerListener(installStateListener)
    }

    override fun checkForUpdates(scope: CoroutineScope, isUserInitiated: Boolean) {
        val currentVersion = UpdateChecker.getCurrentVersionName(context)
        currentVersionName = currentVersion
        _status.value = UpdateStatus.Checking

        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            ) {
                appUpdateInfo = info
                val latestVer = "v${info.availableVersionCode()}"
                latestVersionName = latestVer
                _status.value = UpdateStatus.Available(
                    currentVersion = currentVersion,
                    latestVersion = latestVer,
                    releaseNotes = ""
                )
            } else {
                appUpdateInfo = null
                _status.value = UpdateStatus.UpToDate(currentVersion)
            }
        }.addOnFailureListener { e ->
            Log.e("PlayStoreAppUpdater", "Error checking Play Store updates", e)
            _status.value = UpdateStatus.Error(customMessage = e.message)
        }
    }

    override fun startUpdate(activity: Activity, scope: CoroutineScope) {
        if (_status.value is UpdateStatus.ReadyToInstall) {
            appUpdateManager.completeUpdate()
            return
        }

        val info = appUpdateInfo ?: return
        try {
            appUpdateManager.startUpdateFlow(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
            )
        } catch (e: Exception) {
            Log.e("PlayStoreAppUpdater", "Error starting Play Store update flow", e)
            _status.value = UpdateStatus.Error(customMessage = e.message)
        }
    }

    override fun dismissUpdate() {
        _status.value = UpdateStatus.Idle
    }
}
