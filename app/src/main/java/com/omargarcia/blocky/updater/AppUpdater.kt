package com.omargarcia.blocky.updater

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Available(
        val currentVersion: String,
        val latestVersion: String,
        val releaseNotes: String
    ) : UpdateStatus()
    data class Downloading(
        val progressPercent: Int,
        val currentVersion: String = "",
        val latestVersion: String = "",
        val releaseNotes: String = ""
    ) : UpdateStatus()
    data class ReadyToInstall(
        val apkUri: android.net.Uri? = null,
        val currentVersion: String = "",
        val latestVersion: String = "",
        val releaseNotes: String = ""
    ) : UpdateStatus()
    data class Error(val messageResId: Int? = null, val customMessage: String? = null) : UpdateStatus()
}

interface AppUpdater {
    val status: StateFlow<UpdateStatus>
    fun checkForUpdates(scope: CoroutineScope, isUserInitiated: Boolean = false)
    fun startUpdate(activity: Activity, scope: CoroutineScope)
    fun dismissUpdate()
}
