package com.omargarcia.blocky

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import com.omargarcia.blocky.data.AppDatabase
import com.omargarcia.blocky.data.BlockedCall
import com.omargarcia.blocky.data.BlockedCallDao
import com.omargarcia.blocky.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallBlockerService : CallScreeningService() {

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    private var soundPool: SoundPool? = null
    private var hitSoundId: Int = 0
    private var isSoundLoaded: Boolean = false

    override fun onCreate() {
        super.onCreate()
        initSoundPool()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        serviceJob = SupervisorJob()
        serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
        soundPool?.release()
        soundPool = null
    }

    private fun initSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool?.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) {
                    isSoundLoaded = true
                }
            }
            hitSoundId = soundPool?.load(this, R.raw.sfx_hit, 1) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SoundPool", e)
        }
    }

    override fun onScreenCall(callDetails: Call.Details) {
        // Fast-path 1: Never screen or block outgoing calls
        if (callDetails.callDirection == Call.Details.DIRECTION_OUTGOING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val settingsManager = SettingsManager(this)
        
        // Fast-path 2: If protection shield is disabled, immediately allow call
        if (!settingsManager.isBlockingEnabled) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val rawNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val normalizedNumber = PhoneNumberUtils.normalizeNumber(rawNumber)

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val whitelistDao = db.whitelistedNumberDao()
                val permanentBlockDao = db.permanentBlockedNumberDao()
                val unblockedDao = db.unblockedNumberDao()
                val historyDao = db.blockedCallDao()

                // 1. Handle Private / Hidden / Anonymous numbers -> Block
                if (rawNumber.isBlank()) {
                    blockCall(callDetails, "Private / Unknown", historyDao, settingsManager)
                    return@launch
                }

                // 2. Check if number is explicitly in the permanent blocked list
                val blockedList = permanentBlockDao.getAllList().map { it.phoneNumber }
                if (isNumberInList(rawNumber, normalizedNumber, blockedList)) {
                    blockCall(callDetails, rawNumber, historyDao, settingsManager)
                    return@launch
                }

                // 3. Check if number was unblocked by the user
                val unblockedList = unblockedDao.getAllList().map { it.phoneNumber }
                if (isNumberInList(rawNumber, normalizedNumber, unblockedList)) {
                    allowCall(callDetails)
                    return@launch
                }

                // 4. Check if number is whitelisted
                val whitelist = whitelistDao.getAllList().map { it.phoneNumber }
                if (isNumberInList(rawNumber, normalizedNumber, whitelist)) {
                    allowCall(callDetails)
                    return@launch
                }

                // 5. Check if number is in user's Contacts (deduplicated lookup)
                val inContacts = isNumberInContacts(rawNumber) || 
                                (normalizedNumber.isNotBlank() && normalizedNumber != rawNumber && isNumberInContacts(normalizedNumber))
                if (inContacts) {
                    allowCall(callDetails)
                    return@launch
                }

                // 6. Check repeat caller threshold (if configured > 1)
                val threshold = settingsManager.repeatCallThreshold
                if (threshold > 1) {
                    val intervalMinutes = settingsManager.repeatCallIntervalMinutes
                    val windowStartTime = System.currentTimeMillis() - (intervalMinutes * 60 * 1000L)
                    val recentBlockedCalls = historyDao.getBlockedCallsSinceList(windowStartTime)
                    val recentMatchingCount = countMatchingCalls(rawNumber, normalizedNumber, recentBlockedCalls)
                    val totalAttempts = recentMatchingCount + 1
                    if (totalAttempts >= threshold) {
                        allowCall(callDetails)
                        return@launch
                    }
                }

                // 7. Unknown caller not in contacts or whitelist -> Block
                blockCall(callDetails, rawNumber, historyDao, settingsManager)
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error during call screening", t)
                // Defensive fallback: ensure Telecom always receives a response
                try {
                    respondToCall(callDetails, CallResponse.Builder().build())
                } catch (_: Exception) {}
            }
        }
    }

    private fun allowCall(callDetails: Call.Details) {
        try {
            respondToCall(callDetails, CallResponse.Builder().build())
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching allowCall response", e)
        }
    }

    private suspend fun blockCall(
        callDetails: Call.Details, 
        displayLogNumber: String, 
        historyDao: BlockedCallDao,
        settingsManager: SettingsManager
    ) {
        val response = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSilenceCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(true)
            .build()

        // 1. Persist to Room database and refresh notification first
        // withContext(NonCancellable) ensures data is written to disk before Telecom unbinds
        withContext(NonCancellable) {
            try {
                historyDao.insert(BlockedCall(phoneNumber = displayLogNumber))
                StatusIndicatorService.refreshNotification(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting blocked call into history", e)
            }
        }

        // 2. Play sound alert
        playBlockedSound(settingsManager)

        // 3. Respond to Telecom
        try {
            respondToCall(callDetails, response)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching blockCall response", e)
        }
    }

    private fun playBlockedSound(settingsManager: SettingsManager) {
        if (!settingsManager.isBlockSoundEnabled) return
        try {
            val rawVolume = settingsManager.blockSoundVolume.coerceIn(0.0f, 1.0f)
            val gain = if (rawVolume <= 0.02f) 0.0f else (rawVolume * rawVolume)
            if (gain <= 0.0f) return

            if (soundPool != null && hitSoundId != 0 && isSoundLoaded) {
                soundPool?.play(hitSoundId, gain, gain, 1, 0, 1.0f)
            } else {
                // Fallback player if SoundPool is loading or unavailable
                val player = MediaPlayer.create(applicationContext, R.raw.sfx_hit)
                player?.apply {
                    setVolume(gain, gain)
                    setOnCompletionListener { mp ->
                        try {
                            mp.stop()
                            mp.release()
                        } catch (_: Exception) {}
                    }
                    setOnErrorListener { mp, _, _ ->
                        try {
                            mp.release()
                        } catch (_: Exception) {}
                        true
                    }
                    start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing blocked sound", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun isNumberInList(rawNumber: String, normalizedNumber: String, list: List<String>): Boolean {
        if (list.isEmpty()) return false
        for (item in list) {
            val normalizedItem = PhoneNumberUtils.normalizeNumber(item)
            if (item == rawNumber || 
                (normalizedNumber.isNotEmpty() && item == normalizedNumber) ||
                (normalizedItem.isNotEmpty() && normalizedItem == normalizedNumber) ||
                PhoneNumberUtils.compare(this, rawNumber, item) ||
                (normalizedNumber.isNotEmpty() && PhoneNumberUtils.compare(this, normalizedNumber, item))
            ) {
                return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun isNumberInContacts(number: String): Boolean {
        if (number.isBlank()) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.NUMBER
        )
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, null)
            if (cursor != null) {
                val numberColumnIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
                while (cursor.moveToNext()) {
                    if (numberColumnIndex != -1) {
                        val contactNumber = cursor.getString(numberColumnIndex)
                        if (!contactNumber.isNullOrBlank() && PhoneNumberUtils.compare(this, number, contactNumber)) {
                            return true
                        }
                    } else {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts for number", e)
        } finally {
            cursor?.close()
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun countMatchingCalls(rawNumber: String, normalizedNumber: String, calls: List<BlockedCall>): Int {
        var count = 0
        for (call in calls) {
            val item = call.phoneNumber
            val normalizedItem = PhoneNumberUtils.normalizeNumber(item)
            if (item == rawNumber || 
                (normalizedNumber.isNotEmpty() && item == normalizedNumber) ||
                (normalizedItem.isNotEmpty() && normalizedItem == normalizedNumber) ||
                PhoneNumberUtils.compare(this, rawNumber, item) ||
                (normalizedNumber.isNotEmpty() && PhoneNumberUtils.compare(this, normalizedNumber, item))
            ) {
                count++
            }
        }
        return count
    }

    companion object {
        private const val TAG = "CallBlockerService"
    }
}


