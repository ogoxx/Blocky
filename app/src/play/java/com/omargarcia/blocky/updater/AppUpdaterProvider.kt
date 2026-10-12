package com.omargarcia.blocky.updater

import android.content.Context

object AppUpdaterProvider {
    fun create(context: Context): AppUpdater = PlayStoreAppUpdater(context.applicationContext)
}
