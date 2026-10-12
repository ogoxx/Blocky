package com.omargarcia.blocky

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omargarcia.blocky.data.AppDatabase
import com.omargarcia.blocky.data.SettingsManager
import com.omargarcia.blocky.utils.DateGroupHelper
import com.omargarcia.blocky.utils.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatusIndicatorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countCollectorJob: Job? = null
    private var midnightJob: Job? = null
    private var lastObservedStartOfDay: Long = 0L
    private var currentDailyCount: Int = 0
    private var settingsManager: SettingsManager? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsManager.KEY_LANGUAGE_CODE) {
            updateNotification()
        }
    }

    private val timeChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val currentStartOfToday = DateGroupHelper(applicationContext).startOfTodayMillis
            if (currentStartOfToday != lastObservedStartOfDay || intent?.action != Intent.ACTION_TIME_TICK) {
                lastObservedStartOfDay = currentStartOfToday
                observeDailyBlockedCalls()
                scheduleMidnightCheck()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val localizedContext = LocaleHelper.getLocalizedContext(newBase)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this).also {
            it.registerListener(prefListener)
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        try {
            registerReceiver(timeChangeReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        scheduleMidnightCheck()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createNotificationChannel()
        val currentNotification = createNotification(currentDailyCount)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    currentNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, currentNotification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            updateNotification()
        }

        if (countCollectorJob?.isActive != true) {
            observeDailyBlockedCalls()
        }
        
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotification()
    }

    private fun updateNotification() {
        createNotificationChannel()
        val updatedNotification = createNotification(currentDailyCount)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, updatedNotification)
    }

    private fun scheduleMidnightCheck() {
        midnightJob?.cancel()
        midnightJob = serviceScope.launch {
            val now = System.currentTimeMillis()
            val startOfToday = DateGroupHelper(applicationContext).startOfTodayMillis
            val nextMidnight = startOfToday + 24 * 60 * 60 * 1000L
            val delayMillis = (nextMidnight - now).coerceAtLeast(1000L)
            delay(delayMillis + 500L)
            observeDailyBlockedCalls()
            scheduleMidnightCheck()
        }
    }

    private fun observeDailyBlockedCalls() {
        countCollectorJob?.cancel()
        countCollectorJob = serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val historyDao = db.blockedCallDao()
            val startOfToday = DateGroupHelper(applicationContext).startOfTodayMillis
            lastObservedStartOfDay = startOfToday

            historyDao.getDailyBlockedCount(startOfToday).collect { count ->
                val currentStartOfToday = DateGroupHelper(applicationContext).startOfTodayMillis
                if (currentStartOfToday != startOfToday) {
                    // Midnight crossed or clock changed; restart collector with fresh start-of-day
                    observeDailyBlockedCalls()
                    scheduleMidnightCheck()
                    return@collect
                }
                currentDailyCount = count
                updateNotification()
            }
        }
    }

    private fun createNotification(dailyCount: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val localizedContext = LocaleHelper.getLocalizedContext(this)

        val countText = when (dailyCount) {
            0 -> localizedContext.getString(R.string.notification_active_with_count_zero)
            1 -> localizedContext.getString(R.string.notification_active_with_count_one)
            else -> localizedContext.getString(R.string.notification_active_with_count_many, dailyCount)
        }

        val builder = NotificationCompat.Builder(localizedContext, CHANNEL_ID)
            .setContentTitle(localizedContext.getString(R.string.notification_title))
            .setContentText(countText)
            .setSmallIcon(R.drawable.ic_stat_blocky)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Ensures prominent status bar display
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Updates text smoothly without re-alerting
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Remove old low-importance channels so Android doesn't keep cached silent settings
        try {
            manager.deleteNotificationChannel("blocky_status_channel_v1")
            manager.deleteNotificationChannel("blocky_status_channel_v2")
        } catch (_: Exception) {}

        val localizedContext = LocaleHelper.getLocalizedContext(this)

        // Use IMPORTANCE_DEFAULT without sound/vibration so the status bar icon is always visible
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = localizedContext.getString(R.string.notification_text)
            setSound(null, null) // Completely silent
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsManager?.unregisterListener(prefListener)
        try {
            unregisterReceiver(timeChangeReceiver)
        } catch (_: Exception) {}
        midnightJob?.cancel()
        countCollectorJob?.cancel()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_REFRESH_NOTIFICATION = "com.omargarcia.blocky.action.REFRESH_NOTIFICATION"
        private const val CHANNEL_ID = "blocky_status_channel_v3" // v3 ensures fresh channel with IMPORTANCE_DEFAULT
        private const val NOTIFICATION_ID = 1001

        fun refreshNotification(context: Context) {
            val intent = Intent(context, StatusIndicatorService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
