@file:Suppress("FunctionName")

package com.omargarcia.blocky

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.saveable.rememberSaveable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.core.view.WindowCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import com.omargarcia.blocky.data.*
import com.omargarcia.blocky.ui.theme.BlockyTheme
import com.omargarcia.blocky.ui.theme.VT323Font
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

import com.omargarcia.blocky.utils.CsvContactHelper
import com.omargarcia.blocky.utils.CsvExportOption
import com.omargarcia.blocky.utils.DateGroupHelper
import com.omargarcia.blocky.updater.AppUpdaterProvider
import com.omargarcia.blocky.updater.UpdateStatus
import com.omargarcia.blocky.utils.AppUpdateInfo
import com.omargarcia.blocky.utils.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalSoundManager = compositionLocalOf<SoundManager?> { null }

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val settings = SettingsManager(newBase)
        val locale = Locale.forLanguageTag(settings.languageCode)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val settings = SettingsManager(this)
        val locale = Locale.forLanguageTag(settings.languageCode)
        newConfig.setLocale(locale)
        newConfig.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(newConfig, resources.displayMetrics)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            BlockyTheme {
                MainContainer()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

@Composable
fun MainContainer() {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val blockLogDao = remember { db.blockedCallDao() }
    val permanentBlockDao = remember { db.permanentBlockedNumberDao() }
    val whitelistDao = remember { db.whitelistedNumberDao() }
    val unblockedDao = remember { db.unblockedNumberDao() }
    val scope = rememberCoroutineScope()

    val soundManager = remember { SoundManager(context) }
    var isSoundEnabled by remember { mutableStateOf(settingsManager.isSoundEnabled) }
    var isBlockSoundEnabled by remember { mutableStateOf(settingsManager.isBlockSoundEnabled) }
    var blockSoundVolume by remember { mutableFloatStateOf(settingsManager.blockSoundVolume) }

    DisposableEffect(Unit) {
        onDispose {
            soundManager.release()
        }
    }

    var isOnboardingCompleted by remember { mutableStateOf(settingsManager.isOnboardingCompleted) }
    var roleHeldState by remember { mutableStateOf(checkRoleHeld(context)) }
    var isEnabled by remember { mutableStateOf(settingsManager.isBlockingEnabled) }
    var currentLang by remember { mutableStateOf(settingsManager.languageCode) }
    var repeatCallThreshold by remember { mutableIntStateOf(settingsManager.repeatCallThreshold) }
    var repeatCallIntervalMinutes by remember { mutableIntStateOf(settingsManager.repeatCallIntervalMinutes) }

    val appUpdater = remember { AppUpdaterProvider.create(context) }
    val updateStatus by appUpdater.status.collectAsState()
    val activity = context as? ComponentActivity

    val isCheckingUpdates = updateStatus is UpdateStatus.Checking
    val hasPendingUpdate = updateStatus is UpdateStatus.Available || updateStatus is UpdateStatus.Downloading || updateStatus is UpdateStatus.ReadyToInstall
    val cachedLatestVersion = when (val s = updateStatus) {
        is UpdateStatus.Available -> s.latestVersion
        else -> settingsManager.cachedLatestVersion
    }

    // Daily automatic check for updates on app startup
    LaunchedEffect(Unit) {
        appUpdater.checkForUpdates(scope, isUserInitiated = false)
    }

    val performUpdateCheck: () -> Unit = {
        soundManager.playClick()
        appUpdater.checkForUpdates(scope, isUserInitiated = true)
    }

    LaunchedEffect(updateStatus) {
        when (val s = updateStatus) {
            is UpdateStatus.UpToDate -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.updates_toast_up_to_date, s.currentVersion),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is UpdateStatus.Error -> {
                val msg = s.messageResId?.let { context.getString(it) } ?: s.customMessage
                if (!msg.isNullOrBlank()) {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settingsManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                roleHeldState = checkRoleHeld(context)
                isEnabled = settingsManager.isBlockingEnabled
                BlockyTileService.requestTileUpdate(context)
            }
        }
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsManager.KEY_BLOCKING_ENABLED) {
                isEnabled = settingsManager.isBlockingEnabled
            }
        }
        settingsManager.registerListener(prefListener)
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            settingsManager.unregisterListener(prefListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val isProtectionActive = roleHeldState && isEnabled

    LaunchedEffect(isProtectionActive) {
        val intent = Intent(context, StatusIndicatorService::class.java)
        if (isProtectionActive) {
            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }
            if (hasNotificationPermission) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val blockedCount by blockLogDao.getTotalBlockedCount().collectAsState(initial = 0)
    val blockedCalls by blockLogDao.getAll().collectAsState(initial = emptyList())
    val whitelist by whitelistDao.getAllWhitelisted().collectAsState(initial = emptyList())

    val csvImportSuccessTemplate = stringResource(R.string.csv_import_success)
    val shareCsvChooserTitle = stringResource(R.string.share_csv_chooser_title)

    var pendingSaveExportOption by remember { mutableStateOf<CsvExportOption?>(null) }
    val exportCsvDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val option = pendingSaveExportOption
        if (uri != null && option != null) {
            scope.launch {
                var count = 0
                withContext(Dispatchers.IO) {
                    try {
                        val allBlocked = blockLogDao.getAllList()
                        val allWhitelist = whitelistDao.getAllList()
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            count = CsvContactHelper.exportToGoogleCsv(os, allBlocked, allWhitelist, option)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (count > 0) {
                    Toast.makeText(context, R.string.toast_csv_saved_success, Toast.LENGTH_SHORT).show()
                }
            }
        }
        pendingSaveExportOption = null
    }

    val baseConfig = LocalConfiguration.current
    val currentLocale = remember(currentLang) { Locale.forLanguageTag(currentLang) }
    val localizedConfiguration = remember(currentLang, baseConfig) {
        Configuration(baseConfig).apply {
            setLocale(currentLocale)
            setLayoutDirection(currentLocale)
        }
    }

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalSoundManager provides soundManager
    ) {
        Crossfade(targetState = isOnboardingCompleted, label = "ScreenTransition") { completed ->
            if (completed) {
                MainContent(
                    roleHeld = roleHeldState,
                    isEnabled = isEnabled,
                    isSoundEnabled = isSoundEnabled,
                    isBlockSoundEnabled = isBlockSoundEnabled,
                    blockSoundVolume = blockSoundVolume,
                    blockedCount = blockedCount,
                    blockedList = blockedCalls,
                    whitelist = whitelist,
                    currentLang = currentLang,
                    repeatCallThreshold = repeatCallThreshold,
                    repeatCallIntervalMinutes = repeatCallIntervalMinutes,
                    onRoleChanged = {
                        roleHeldState = it
                        BlockyTileService.requestTileUpdate(context)
                    },
                    onEnabledChanged = {
                        isEnabled = it
                        settingsManager.isBlockingEnabled = it
                        BlockyTileService.requestTileUpdate(context)
                    },
                    onSoundEnabledChanged = {
                        isSoundEnabled = it
                        settingsManager.isSoundEnabled = it
                        if (!it) {
                            soundManager.stopMusic()
                        }
                    },
                    onBlockSoundEnabledChanged = {
                        isBlockSoundEnabled = it
                        settingsManager.isBlockSoundEnabled = it
                    },
                    onBlockSoundVolumeChanged = {
                        blockSoundVolume = it
                        settingsManager.blockSoundVolume = it
                    },
                    onLanguageChanged = { lang ->
                        settingsManager.languageCode = lang
                        currentLang = lang
                        updateAppLocale(context, lang)
                        if (isProtectionActive) {
                            StatusIndicatorService.refreshNotification(context)
                        }
                    },
                    onThresholdChanged = { threshold ->
                        repeatCallThreshold = threshold
                        settingsManager.repeatCallThreshold = threshold
                    },
                    onIntervalMinutesChanged = { interval ->
                        repeatCallIntervalMinutes = interval
                        settingsManager.repeatCallIntervalMinutes = interval
                    },
                onUnblockNumber = { numberObj ->
                    scope.launch { 
                        blockLogDao.delete(numberObj)
                        permanentBlockDao.deleteByNumber(numberObj.phoneNumber)
                        unblockedDao.insert(UnblockedNumber(phoneNumber = numberObj.phoneNumber))
                        Toast.makeText(context, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                },
                onUnblockAll = {
                    scope.launch {
                        blockedCalls.forEach {
                            unblockedDao.insert(UnblockedNumber(phoneNumber = it.phoneNumber))
                        }
                        blockLogDao.clearAll()
                        permanentBlockDao.clearAll()
                        Toast.makeText(context, R.string.toast_unblocked_all, Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToWhitelistFromBlocked = { numberObj ->
                    scope.launch {
                        blockLogDao.delete(numberObj)
                        permanentBlockDao.deleteByNumber(numberObj.phoneNumber)
                        unblockedDao.insert(UnblockedNumber(phoneNumber = numberObj.phoneNumber))
                        whitelistDao.insert(WhitelistedNumber(phoneNumber = numberObj.phoneNumber))
                        Toast.makeText(context, R.string.toast_whitelisted, Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToBlockedFromWhitelist = { numberObj ->
                    scope.launch {
                        whitelistDao.delete(numberObj)
                        unblockedDao.deleteByNumber(numberObj.phoneNumber)
                        blockLogDao.insert(BlockedCall(phoneNumber = numberObj.phoneNumber))
                        permanentBlockDao.insert(PermanentBlockedNumber(phoneNumber = numberObj.phoneNumber))
                        Toast.makeText(context, R.string.toast_blocked, Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteBlockedPermanent = { numberObj ->
                    scope.launch {
                        blockLogDao.delete(numberObj)
                        permanentBlockDao.deleteByNumber(numberObj.phoneNumber)
                        Toast.makeText(context, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteWhitelistPermanent = { numberObj ->
                    scope.launch {
                        whitelistDao.delete(numberObj)
                        Toast.makeText(context, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                },
                onRemoveFromWhitelist = { number ->
                    scope.launch { whitelistDao.delete(number) }
                },
                onAddBlockedManualNumber = { num ->
                    scope.launch {
                        permanentBlockDao.insert(PermanentBlockedNumber(phoneNumber = num))
                        blockLogDao.insert(BlockedCall(phoneNumber = num))
                        whitelistDao.deleteByNumber(num)
                        unblockedDao.deleteByNumber(num)
                        Toast.makeText(context, R.string.toast_blocked, Toast.LENGTH_SHORT).show()
                    }
                },
                onAddWhitelistManualNumber = { num ->
                    scope.launch {
                        whitelistDao.insert(WhitelistedNumber(phoneNumber = num))
                        unblockedDao.insert(UnblockedNumber(phoneNumber = num))
                        permanentBlockDao.deleteByNumber(num)
                        Toast.makeText(context, R.string.toast_whitelisted, Toast.LENGTH_SHORT).show()
                    }
                },
                onImportNumbersToBlocked = { numbers ->
                    scope.launch {
                        var count = 0
                        withContext(Dispatchers.IO) {
                            for (num in numbers) {
                                if (num.isNotBlank()) {
                                    permanentBlockDao.insert(PermanentBlockedNumber(phoneNumber = num))
                                    blockLogDao.insert(BlockedCall(phoneNumber = num))
                                    whitelistDao.deleteByNumber(num)
                                    count++
                                }
                            }
                        }
                        Toast.makeText(context, String.format(csvImportSuccessTemplate, count), Toast.LENGTH_SHORT).show()
                    }
                },
                onImportNumbersToWhitelist = { numbers ->
                    scope.launch {
                        var count = 0
                        withContext(Dispatchers.IO) {
                            for (num in numbers) {
                                if (num.isNotBlank()) {
                                    whitelistDao.insert(WhitelistedNumber(phoneNumber = num))
                                    unblockedDao.insert(UnblockedNumber(phoneNumber = num))
                                    permanentBlockDao.deleteByNumber(num)
                                    count++
                                }
                            }
                        }
                        Toast.makeText(context, String.format(csvImportSuccessTemplate, count), Toast.LENGTH_SHORT).show()
                    }
                },
                onExportNumbersToCsv = { exportOption ->
                    scope.launch {
                        var count = 0
                        val filename = when (exportOption) {
                            CsvExportOption.ALL -> "blocky_all_numbers.csv"
                            CsvExportOption.BLOCKED_ONLY -> "blocky_blocked_numbers.csv"
                            CsvExportOption.WHITELIST_ONLY -> "blocky_whitelist_numbers.csv"
                        }
                        withContext(Dispatchers.IO) {
                            try {
                                val allBlocked = blockLogDao.getAllList()
                                val allWhitelist = whitelistDao.getAllList()
                                count = CsvContactHelper.exportAndShareCsv(
                                    context = context,
                                    filename = filename,
                                    blockedList = allBlocked,
                                    whitelist = allWhitelist,
                                    exportOption = exportOption,
                                    chooserTitle = shareCsvChooserTitle
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        if (count == 0) {
                            Toast.makeText(context, R.string.csv_import_empty, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSaveNumbersToLocalFile = { exportOption ->
                    val filename = when (exportOption) {
                        CsvExportOption.ALL -> "blocky_all_numbers.csv"
                        CsvExportOption.BLOCKED_ONLY -> "blocky_blocked_numbers.csv"
                        CsvExportOption.WHITELIST_ONLY -> "blocky_whitelist_numbers.csv"
                    }
                    pendingSaveExportOption = exportOption
                    exportCsvDocumentLauncher.launch(filename)
                },
                isCheckingUpdates = isCheckingUpdates,
                hasPendingUpdate = hasPendingUpdate,
                cachedLatestVersion = cachedLatestVersion,
                onCheckForUpdates = performUpdateCheck
            ) {
                isOnboardingCompleted = false
            }
        } else {
            OnboardingScreen(
                onCompleted = {
                    scope.launch {
                        settingsManager.isOnboardingCompleted = true
                        isOnboardingCompleted = true
                        roleHeldState = checkRoleHeld(context)
                    }
                }
            )
        }
    }

    if (updateStatus is UpdateStatus.Available || updateStatus is UpdateStatus.Downloading || updateStatus is UpdateStatus.ReadyToInstall) {
        UpdateAvailableDialog(
            updateStatus = updateStatus,
            onDismiss = {
                soundManager.playClick()
                appUpdater.dismissUpdate()
            },
            onInstallNow = {
                soundManager.playClick()
                if (activity != null) {
                    appUpdater.startUpdate(activity, scope)
                }
            }
        )
    }
}
}

@Composable
fun LanguageFlagButton(
    currentLang: String,
    onLanguageChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val soundManager = LocalSoundManager.current
    val nextLang = if (currentLang == "en") "es" else "en"
    val flagRes = if (currentLang == "en") R.drawable.ic_flag_uk else R.drawable.ic_flag_mexico
    val contentDesc = if (currentLang == "en") "English" else "Español"

    Surface(
        onClick = {
            soundManager?.playClick()
            onLanguageChanged(nextLang)
        },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(flagRes),
                contentDescription = contentDesc,
                modifier = Modifier
                    .size(width = 30.dp, height = 20.dp)
                    .clip(RoundedCornerShape(3.dp)),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val isInPreview = LocalInspectionMode.current
    val soundManager = LocalSoundManager.current
    
    var notificationState by remember {
        mutableStateOf(
            if (isInPreview) {
                true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }
        )
    }
    var contactsState by remember { mutableStateOf(if (isInPreview) true else checkPermission(context, Manifest.permission.READ_CONTACTS)) }
    var batteryState by remember { mutableStateOf(if (isInPreview) true else isIgnoringBatteryOptimizations(context)) }
    var roleState by remember { mutableStateOf(if (isInPreview) true else checkRoleHeld(context)) }

    // Prominent Disclosure state dialogs
    var showContactsDisclosure by remember { mutableStateOf(false) }
    var showNotificationDisclosure by remember { mutableStateOf(false) }
    var showPrivacyPolicyModal by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationState = checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        }
        contactsState = checkPermission(context, Manifest.permission.READ_CONTACTS)
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        roleState = checkRoleHeld(context)
    }

    if (showContactsDisclosure) {
        AlertDialog(
            onDismissRequest = { showContactsDisclosure = false },
            icon = { Icon(Icons.Default.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.disclosure_contacts_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.disclosure_contacts_desc)) },
            confirmButton = {
                Button(onClick = {
                    soundManager?.playClick()
                    showContactsDisclosure = false
                    permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) {
                    Text(stringResource(R.string.disclosure_accept_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    soundManager?.playClick()
                    showContactsDisclosure = false
                }) {
                    Text(stringResource(R.string.disclosure_decline_btn))
                }
            }
        )
    }

    if (showNotificationDisclosure) {
        AlertDialog(
            onDismissRequest = { showNotificationDisclosure = false },
            icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.disclosure_notifications_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.disclosure_notifications_desc)) },
            confirmButton = {
                Button(onClick = {
                    soundManager?.playClick()
                    showNotificationDisclosure = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) {
                    Text(stringResource(R.string.disclosure_accept_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    soundManager?.playClick()
                    showNotificationDisclosure = false
                }) {
                    Text(stringResource(R.string.disclosure_decline_btn))
                }
            }
        )
    }

    if (showPrivacyPolicyModal) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicyModal = false })
    }

    val parallaxOffset by rememberParallaxOffset()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Image(
            painter = painterResource(R.drawable.bg_app_pattern),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.38f)
                .offset { IntOffset(parallaxOffset.x.roundToInt(), parallaxOffset.y.roundToInt()) },
            alpha = 0.30f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Image(
                painter = painterResource(R.drawable.ic_blocky_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.welcome_title),
                fontFamily = VT323Font,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.welcome_desc),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.85f)
            )
            
            Spacer(modifier = Modifier.height(10.dp))

            OnboardingStep(
                title = stringResource(R.string.step_role_title),
                desc = stringResource(R.string.step_role_desc),
                isDone = roleState,
                btnText = stringResource(R.string.set_default_app),
            ) {
                soundManager?.playClick()
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                roleManager?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)?.let { roleLauncher.launch(it) }
            }

            OnboardingStep(
                title = stringResource(R.string.step_contacts_title),
                desc = stringResource(R.string.step_contacts_desc),
                isDone = contactsState,
                btnText = stringResource(R.string.grant_permission),
            ) { 
                soundManager?.playClick()
                showContactsDisclosure = true
            }

            OnboardingStep(
                title = stringResource(R.string.step_notification_title),
                desc = stringResource(R.string.step_notification_desc),
                isDone = notificationState,
                btnText = stringResource(R.string.grant_permission),
            ) { 
                soundManager?.playClick()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    showNotificationDisclosure = true
                }
            }

            OnboardingStep(
                title = stringResource(R.string.step_battery_title),
                desc = stringResource(R.string.step_battery_desc),
                isDone = batteryState,
                btnText = stringResource(R.string.grant_permission),
            ) {
                soundManager?.playClick()
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            
            Button(
                onClick = {
                    soundManager?.playClick()
                    soundManager?.playOnboardingTheme()
                    onCompleted()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = roleState
            ) {
                Text(text = if (roleState && contactsState) stringResource(R.string.setup_complete) else stringResource(R.string.get_started))
            }

            TextButton(onClick = {
                soundManager?.playClick()
                showPrivacyPolicyModal = true
            }) {
                Text(stringResource(R.string.privacy_policy_btn), fontSize = 12.sp)
            }
        }
    }
}
}

@Composable
fun OnboardingStep(title: String, desc: String, isDone: Boolean, btnText: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(text = desc, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isDone) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
            } else {
                TextButton(onClick = onClick) { Text(btnText, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun rememberParallaxOffset(maxOffsetX: Float = 48f, maxOffsetY: Float = 42f): State<Offset> {
    val context = LocalContext.current
    val isInPreview = LocalInspectionMode.current
    val targetOffset = remember { mutableStateOf(Offset.Zero) }

    if (!isInPreview) {
        DisposableEffect(Unit) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val accelSensor = if (rotationSensor == null) {
                sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            } else null

            val listener = object : SensorEventListener {
                private val rotationMatrix = FloatArray(9)
                private var baselineTiltX = Float.NaN
                private var baselineTiltY = Float.NaN
                private var smoothedX = 0f
                private var smoothedY = 0f

                override fun onSensorChanged(event: SensorEvent) {
                    val tiltX: Float
                    val tiltY: Float

                    when (event.sensor.type) {
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            // Direct gravity vector from rotation matrix avoids Euler angle gimbal lock at 90° portrait:
                            // rotationMatrix[6] = lateral tilt (X)
                            // rotationMatrix[7] = longitudinal pitch tilt (Y)
                            tiltX = rotationMatrix[6]
                            tiltY = rotationMatrix[7]
                        }
                        Sensor.TYPE_ACCELEROMETER -> {
                            // Normalize accelerometer values to match rotation matrix scale:
                            tiltX = event.values[0] / 9.81f
                            tiltY = -event.values[1] / 9.81f
                        }
                        else -> return
                    }

                    if (baselineTiltY.isNaN()) {
                        baselineTiltX = tiltX
                        baselineTiltY = tiltY
                        smoothedX = 0f
                        smoothedY = 0f
                        targetOffset.value = Offset.Zero
                        return
                    } else {
                        // Gently adapt baseline to holding posture shifts over time
                        baselineTiltX += (tiltX - baselineTiltX) * 0.005f
                        baselineTiltY += (tiltY - baselineTiltY) * 0.005f
                    }

                    val diffX = (tiltX - baselineTiltX).coerceIn(-0.45f, 0.45f)
                    val diffY = (tiltY - baselineTiltY).coerceIn(-0.45f, 0.45f)

                    // Follow the physical movement of the phone:
                    // Tilt right -> background glides right (+X)
                    // Tilt down -> background glides down (+Y)
                    val rawTargetX = -diffX * (maxOffsetX / 0.45f)
                    val rawTargetY = diffY * (maxOffsetY / 0.45f)

                    // Responsive EMA low-pass filter eliminates micro-tremors with zero lag
                    smoothedX += (rawTargetX - smoothedX) * 0.22f
                    smoothedY += (rawTargetY - smoothedY) * 0.22f

                    targetOffset.value = Offset(smoothedX, smoothedY)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            val activeSensor = rotationSensor ?: accelSensor
            if (activeSensor != null) {
                sensorManager?.registerListener(listener, activeSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            onDispose {
                sensorManager?.unregisterListener(listener)
            }
        }
    }

    return animateOffsetAsState(
        targetValue = targetOffset.value,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "ParallaxOffset"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    roleHeld: Boolean,
    isEnabled: Boolean,
    isSoundEnabled: Boolean,
    isBlockSoundEnabled: Boolean = true,
    blockSoundVolume: Float = 0.85f,
    blockedCount: Int,
    blockedList: List<BlockedCall>,
    whitelist: List<WhitelistedNumber>,
    currentLang: String,
    repeatCallThreshold: Int = 1,
    repeatCallIntervalMinutes: Int = 15,
    onRoleChanged: (Boolean) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSoundEnabledChanged: (Boolean) -> Unit,
    onBlockSoundEnabledChanged: (Boolean) -> Unit = {},
    onBlockSoundVolumeChanged: (Float) -> Unit = {},
    onLanguageChanged: (String) -> Unit,
    onThresholdChanged: (Int) -> Unit = {},
    onIntervalMinutesChanged: (Int) -> Unit = {},
    onUnblockNumber: (BlockedCall) -> Unit,
    onUnblockAll: () -> Unit,
    onAddToWhitelistFromBlocked: (BlockedCall) -> Unit,
    onAddToBlockedFromWhitelist: (WhitelistedNumber) -> Unit,
    onDeleteBlockedPermanent: (BlockedCall) -> Unit,
    onDeleteWhitelistPermanent: (WhitelistedNumber) -> Unit,
    onRemoveFromWhitelist: (WhitelistedNumber) -> Unit,
    onAddBlockedManualNumber: (String) -> Unit,
    onAddWhitelistManualNumber: (String) -> Unit,
    onImportNumbersToBlocked: (List<String>) -> Unit,
    onImportNumbersToWhitelist: (List<String>) -> Unit,
    onExportNumbersToCsv: (CsvExportOption) -> Unit,
    onSaveNumbersToLocalFile: (CsvExportOption) -> Unit = {},
    isCheckingUpdates: Boolean = false,
    hasPendingUpdate: Boolean = false,
    cachedLatestVersion: String? = null,
    onCheckForUpdates: () -> Unit = {},
    onFinishOnboarding: () -> Unit,
) {
    val parallaxOffset by rememberParallaxOffset()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val isInPreview = LocalInspectionMode.current
    val soundManager = LocalSoundManager.current
    var showPrivacyPolicyModal by remember { mutableStateOf(false) }
    
    val isSetupIncomplete = !roleHeld || (!isInPreview && !checkPermission(context, Manifest.permission.READ_CONTACTS))

    if (showPrivacyPolicyModal) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicyModal = false })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Image(
            painter = painterResource(R.drawable.bg_app_pattern),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.38f)
                .offset { IntOffset(parallaxOffset.x.roundToInt(), parallaxOffset.y.roundToInt()) },
            alpha = 0.30f
        )

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    title = {
                        if (selectedTab != 0) {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontFamily = VT323Font,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    },
                navigationIcon = {
                    IconButton(onClick = {
                        val newSoundState = !isSoundEnabled
                        onSoundEnabledChanged(newSoundState)
                        if (newSoundState) {
                            soundManager?.playClick()
                        }
                    }) {
                        Icon(
                            imageVector = if (isSoundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(if (isSoundEnabled) R.string.sound_enabled else R.string.sound_disabled),
                            tint = if (isSoundEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    val isProtectionActive = roleHeld && isEnabled
                    if (isProtectionActive) {
                        Icon(imageVector = Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.padding(end = 16.dp))
                    } else {
                        Icon(imageVector = Icons.Default.GppBad, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 16.dp))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(painterResource(R.drawable.ic_stat_blocky), null, modifier = Modifier.size(24.dp)) },
                    label = { Text(stringResource(R.string.protection_tab)) },
                    selected = selectedTab == 0,
                    onClick = {
                        soundManager?.playClick()
                        selectedTab = 0
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Block, null) },
                    label = { Text(stringResource(R.string.blocked_list_tab)) },
                    selected = selectedTab == 1,
                    onClick = {
                        soundManager?.playClick()
                        selectedTab = 1
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Check, null) },
                    label = { Text(stringResource(R.string.whitelist_tab)) },
                    selected = selectedTab == 2,
                    onClick = {
                        soundManager?.playClick()
                        selectedTab = 2
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(stringResource(R.string.configuration_tab)) },
                    selected = selectedTab == 3,
                    onClick = {
                        soundManager?.playClick()
                        selectedTab = 3
                    }
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isSetupIncomplete && (selectedTab == 0)) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.missing_setup_warning), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = {
                                    soundManager?.playClick()
                                    onFinishOnboarding()
                                }) {
                                    Text(stringResource(R.string.finish_setup_btn))
                                }
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = selectedTab,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (slideInHorizontally(
                                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                    ) { width -> width / 3 } + fadeIn(
                                        animationSpec = tween(durationMillis = 280)
                                    )).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                                        ) { width -> -width / 3 } + fadeOut(
                                            animationSpec = tween(durationMillis = 200)
                                        )
                                    )
                                } else {
                                    (slideInHorizontally(
                                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                    ) { width -> -width / 3 } + fadeIn(
                                        animationSpec = tween(durationMillis = 280)
                                    )).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                                        ) { width -> width / 3 } + fadeOut(
                                            animationSpec = tween(durationMillis = 200)
                                        )
                                    )
                                }.using(
                                    SizeTransform(clip = false)
                                )
                            },
                            label = "TabContentAnimation"
                        ) { targetTab ->
                            when (targetTab) {
                                0 -> BlockyScreen(
                                    blockedCount = blockedCount,
                                    isRoleHeldInitial = roleHeld,
                                    onRoleChanged = onRoleChanged,
                                    isEnabledInitial = isEnabled,
                                    onEnabledChanged = onEnabledChanged,
                                    isBlockSoundEnabled = isBlockSoundEnabled,
                                    onBlockSoundEnabledChanged = onBlockSoundEnabledChanged,
                                    blockSoundVolume = blockSoundVolume,
                                    onBlockSoundVolumeChanged = onBlockSoundVolumeChanged,
                                    repeatCallThreshold = repeatCallThreshold,
                                    repeatCallIntervalMinutes = repeatCallIntervalMinutes,
                                    onThresholdChanged = onThresholdChanged,
                                    onIntervalMinutesChanged = onIntervalMinutesChanged,
                                )
                                1 -> BlockedListScreen(
                                    blockedList = blockedList,
                                    onUnblock = onUnblockNumber,
                                    onUnblockAll = onUnblockAll,
                                    onWhitelist = onAddToWhitelistFromBlocked,
                                    onDeletePermanent = onDeleteBlockedPermanent,
                                    onAddManualNumber = onAddBlockedManualNumber
                                )
                                2 -> WhitelistScreen(
                                    whitelist = whitelist,
                                    onRemove = onRemoveFromWhitelist,
                                    onDeletePermanent = onDeleteWhitelistPermanent,
                                    onBlockNumber = onAddToBlockedFromWhitelist,
                                    onAddManualNumber = onAddWhitelistManualNumber
                                )
                                3 -> ConfigurationScreen(
                                    roleHeld = roleHeld,
                                    onRoleChanged = onRoleChanged,
                                    currentLang = currentLang,
                                    onLanguageChanged = onLanguageChanged,
                                    onShowPrivacyPolicy = {
                                        soundManager?.playClick()
                                        showPrivacyPolicyModal = true
                                    },
                                    onExportNumbers = onExportNumbersToCsv,
                                    onSaveNumbersToLocalFile = onSaveNumbersToLocalFile,
                                    onImportBlocked = onImportNumbersToBlocked,
                                    onImportWhitelist = onImportNumbersToWhitelist,
                                    isCheckingUpdates = isCheckingUpdates,
                                    hasPendingUpdate = hasPendingUpdate,
                                    cachedLatestVersion = cachedLatestVersion,
                                    onCheckForUpdates = onCheckForUpdates
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockyScreen(
    modifier: Modifier = Modifier,
    isRoleHeldInitial: Boolean,
    onRoleChanged: (Boolean) -> Unit,
    isEnabledInitial: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    isBlockSoundEnabled: Boolean = true,
    onBlockSoundEnabledChanged: (Boolean) -> Unit = {},
    blockSoundVolume: Float = 0.85f,
    onBlockSoundVolumeChanged: (Float) -> Unit = {},
    blockedCount: Int = 0,
    repeatCallThreshold: Int = 1,
    repeatCallIntervalMinutes: Int = 15,
    onThresholdChanged: (Int) -> Unit = {},
    onIntervalMinutesChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val soundManager = LocalSoundManager.current
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        onRoleChanged(checkRoleHeld(context))
    }
    var showCustomThresholdDialog by remember { mutableStateOf(false) }
    var customInputText by remember { mutableStateOf("") }
    var customIntervalText by remember { mutableStateOf("") }
    var showThankYouDialog by remember { mutableStateOf(false) }
    var showVolumeDialog by rememberSaveable { mutableStateOf(false) }

    if (showVolumeDialog) {
        var tempVolume by rememberSaveable { mutableFloatStateOf(blockSoundVolume) }

        DisposableEffect(Unit) {
            onDispose {
                soundManager?.stopAlertPreview()
            }
        }

        AlertDialog(
            onDismissRequest = {
                soundManager?.stopAlertPreview()
                showVolumeDialog = false
            },
            icon = {
                Icon(
                    imageVector = when {
                        tempVolume == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                        tempVolume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.adjust_alert_volume_title),
                    fontFamily = VT323Font,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.adjust_alert_volume_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "${(tempVolume * 100).roundToInt()}%",
                        fontFamily = VT323Font,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                        Slider(
                            value = tempVolume,
                            onValueChange = { newVol ->
                                tempVolume = newVol
                                soundManager?.updateAlertPreviewVolume(newVol)
                            },
                            onValueChangeFinished = {
                                soundManager?.stopAlertPreview()
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f),
                            thumb = {
                                Image(
                                    painter = painterResource(R.drawable.ic_slider_alien),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    contentScale = ContentScale.Fit
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    modifier = Modifier.scale(scaleX = 1f, scaleY = 0.45f),
                                    thumbTrackGapSize = 0.dp,
                                    trackInsideCornerSize = 0.dp,
                                    drawStopIndicator = null
                                )
                            }
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        soundManager?.stopAlertPreview()
                        soundManager?.playClick()
                        if (tempVolume <= 0.01f) {
                            onBlockSoundEnabledChanged(false)
                            onBlockSoundVolumeChanged(0f)
                        } else {
                            onBlockSoundEnabledChanged(true)
                            onBlockSoundVolumeChanged(tempVolume)
                        }
                        showVolumeDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        soundManager?.stopAlertPreview()
                        soundManager?.playClick()
                        showVolumeDialog = false
                    }
                ) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    if (showThankYouDialog) {
        AlertDialog(
            onDismissRequest = { showThankYouDialog = false },
            icon = {
                Image(
                    painter = painterResource(R.drawable.ic_blocky_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.app_name),
                    fontFamily = VT323Font,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.thank_you_message),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        soundManager?.playClick()
                        showThankYouDialog = false
                    }
                ) {
                    Text(stringResource(R.string.close_btn))
                }
            }
        )
    }

    if (showCustomThresholdDialog) {
        AlertDialog(
            onDismissRequest = { showCustomThresholdDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.custom_threshold_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.custom_threshold_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.threshold_calls_input_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = customInputText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                customInputText = input
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text(stringResource(R.string.custom_threshold_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.threshold_interval_input_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = customIntervalText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                customIntervalText = input
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("15") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val num = customInputText.toIntOrNull()
                        val interval = customIntervalText.toIntOrNull()
                        if (num != null && num >= 2) {
                            soundManager?.playClick()
                            onThresholdChanged(num)
                            if (interval != null && interval in 1..180) {
                                onIntervalMinutesChanged(interval)
                            }
                            showCustomThresholdDialog = false
                        } else {
                            Toast.makeText(context, R.string.toast_invalid_threshold, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.save_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    soundManager?.playClick()
                    showCustomThresholdDialog = false
                }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Logo + App Name + Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_blocky_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            soundManager?.playClick()
                            showThankYouDialog = true
                        }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 50.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = VT323Font,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.main_screen_subtitle),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.95f)
                )
            }

            // Hero Center Section: Big Blocked Counter
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = blockedCount.toString(),
                    fontSize = 78.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = VT323Font,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.calls_blocked_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.90f)
                )
            }

            // Controls Section: Combined Shield Status & Switch Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRoleHeldInitial && isEnabledInitial) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isRoleHeldInitial && isEnabledInitial) Icons.Default.Shield else Icons.Default.GppBad,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = if (isRoleHeldInitial && isEnabledInitial) stringResource(R.string.shield_active) else stringResource(R.string.shield_down),
                                fontFamily = VT323Font,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = when {
                                    !isRoleHeldInitial -> stringResource(R.string.shield_down_desc)
                                    !isEnabledInitial -> stringResource(R.string.service_disabled)
                                    else -> stringResource(R.string.shield_active_desc)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }

                    if (isRoleHeldInitial) {
                        Switch(
                            checked = isEnabledInitial,
                            onCheckedChange = {
                                soundManager?.playClick()
                                onEnabledChanged(it)
                            }
                        )
                    } else {
                        Button(
                            onClick = {
                                soundManager?.playClick()
                                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                                roleManager?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)?.let { roleLauncher.launch(it) }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = stringResource(R.string.grant_permission), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Blocked Call Sound Alert Switch Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        soundManager?.playClick()
                        showVolumeDialog = true
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isBlockSoundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = null,
                            tint = if (isBlockSoundEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.block_sound_alert_title),
                                    fontFamily = VT323Font,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "(${(blockSoundVolume * 100).roundToInt()}%)",
                                    fontFamily = VT323Font,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = stringResource(R.string.block_sound_alert_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isBlockSoundEnabled,
                        onCheckedChange = { isEnabled ->
                            if (isEnabled) {
                                soundManager?.playHit()
                            } else {
                                soundManager?.playClick()
                            }
                            onBlockSoundEnabledChanged(isEnabled)
                        }
                    )
                }
            }

            // Allowed Calls Threshold Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.repeat_call_threshold_title),
                            fontFamily = VT323Font,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (repeatCallThreshold > 1) {
                            stringResource(R.string.threshold_interval_label, repeatCallIntervalMinutes)
                        } else {
                            stringResource(R.string.repeat_call_threshold_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Always Block chip
                        FilterChip(
                            selected = repeatCallThreshold <= 1,
                            onClick = {
                                if (repeatCallThreshold != 1) {
                                    soundManager?.playClick()
                                    onThresholdChanged(1)
                                }
                            },
                            leadingIcon = if (repeatCallThreshold <= 1) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            label = { Text(stringResource(R.string.threshold_always_block), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )

                        // Custom write box button
                        val isCustomActive = repeatCallThreshold > 1
                        FilterChip(
                            selected = isCustomActive,
                            onClick = {
                                soundManager?.playClick()
                                customInputText = if (repeatCallThreshold > 1) repeatCallThreshold.toString() else "2"
                                customIntervalText = repeatCallIntervalMinutes.toString()
                                showCustomThresholdDialog = true
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isCustomActive) Icons.Default.Check else Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = if (isCustomActive) {
                                        stringResource(R.string.threshold_custom_btn, repeatCallThreshold)
                                    } else {
                                        stringResource(R.string.threshold_set_custom_btn)
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedListScreen(
    blockedList: List<BlockedCall>,
    onUnblock: (BlockedCall) -> Unit,
    onUnblockAll: () -> Unit,
    onWhitelist: (BlockedCall) -> Unit,
    onDeletePermanent: (BlockedCall) -> Unit,
    onAddManualNumber: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val soundManager = LocalSoundManager.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedNumberForDetails by remember { mutableStateOf<BlockedCall?>(null) }
    var showUnblockAllDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var inputNumber by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            icon = { Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.add_number_dialog_title_blocked), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = inputNumber,
                    onValueChange = { inputNumber = it },
                    label = { Text(stringResource(R.string.add_number_input_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = inputNumber.trim()
                        if (trimmed.length >= 3) {
                            soundManager?.playClick()
                            onAddManualNumber(trimmed)
                            showAddDialog = false
                            inputNumber = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.add_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    if (showUnblockAllDialog) {
        AlertDialog(
            onDismissRequest = { showUnblockAllDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.unblock_all_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.unblock_all_dialog_desc)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        soundManager?.playClick()
                        showUnblockAllDialog = false
                        onUnblockAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.unblock_all_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    soundManager?.playClick()
                    showUnblockAllDialog = false
                }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    val dateGroupHelper = remember(context) { DateGroupHelper(context) }

    val filteredList = remember(blockedList, searchQuery, dateGroupHelper) {
        if (searchQuery.isBlank()) {
            blockedList
        } else {
            val cleanQuery = searchQuery.trim().lowercase(Locale.getDefault())
            blockedList.filter { dateGroupHelper.matchesSearchQuery(it.phoneNumber, it.timestamp, cleanQuery) }
        }
    }

    val groupedByDate = remember(filteredList, dateGroupHelper) {
        filteredList.groupBy { dateGroupHelper.getDateGroupTitle(it.timestamp) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
            Text(
                text = stringResource(R.string.blocked_numbers_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Search Bar with Quick Add Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            soundManager?.playClick()
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            FilledTonalIconButton(
                onClick = {
                    soundManager?.playClick()
                    inputNumber = ""
                    showAddDialog = true
                },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_btn), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (blockedList.isNotEmpty() && searchQuery.isBlank()) {
            Button(
                onClick = {
                    soundManager?.playClick()
                    showUnblockAllDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.unblock_all), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (blockedList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_blocked_numbers),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = VT323Font,
                    fontSize = 24.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = VT323Font,
                    fontSize = 24.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedByDate.forEach { (dateHeader, itemsInGroup) ->
                    item(key = "header_$dateHeader") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                        ) {
                            Text(
                                text = dateHeader.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    items(itemsInGroup, key = { it.id }) { number ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    soundManager?.playClick()
                                    selectedNumberForDetails = number
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Block,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = number.phoneNumber,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default),
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                                IconButton(onClick = {
                                    soundManager?.playClick()
                                    onWhitelist(number)
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_whitelist), tint = Color(0xFF4CAF50))
                                }
                                IconButton(onClick = {
                                    soundManager?.playClick()
                                    onUnblock(number)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove_from_list), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedNumberForDetails?.let { number ->
        CallerDetailBottomSheet(
            phoneNumber = number.phoneNumber,
            timestamp = number.timestamp,
            isBlocked = true,
            onDismiss = { selectedNumberForDetails = null },
            onRemoveFromList = { onUnblock(number) },
            onDeletePermanent = { onDeletePermanent(number) },
            onToggleList = { onWhitelist(number) },
            onAddToContacts = { numToSave -> launchAddToContacts(context, numToSave) }
        )
    }
}

@Composable
fun WhitelistScreen(
    whitelist: List<WhitelistedNumber>,
    onRemove: (WhitelistedNumber) -> Unit,
    onDeletePermanent: (WhitelistedNumber) -> Unit,
    onBlockNumber: (WhitelistedNumber) -> Unit,
    onAddManualNumber: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val soundManager = LocalSoundManager.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedNumberForDetails by remember { mutableStateOf<WhitelistedNumber?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var inputNumber by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50)) },
            title = { Text(stringResource(R.string.add_number_dialog_title_whitelist), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = inputNumber,
                    onValueChange = { inputNumber = it },
                    label = { Text(stringResource(R.string.add_number_input_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = inputNumber.trim()
                        if (trimmed.length >= 3) {
                            soundManager?.playClick()
                            onAddManualNumber(trimmed)
                            showAddDialog = false
                            inputNumber = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.add_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    val dateGroupHelper = remember(context) { DateGroupHelper(context) }

    val filteredList = remember(whitelist, searchQuery, dateGroupHelper) {
        if (searchQuery.isBlank()) {
            whitelist
        } else {
            val cleanQuery = searchQuery.trim().lowercase(Locale.getDefault())
            whitelist.filter { dateGroupHelper.matchesSearchQuery(it.phoneNumber, it.timestamp, cleanQuery) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
            Text(
                text = stringResource(R.string.whitelist_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Search Bar with Quick Add Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            soundManager?.playClick()
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            FilledTonalIconButton(
                onClick = {
                    soundManager?.playClick()
                    inputNumber = ""
                    showAddDialog = true
                },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_btn), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (whitelist.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.whitelist_empty),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontFamily = VT323Font,
                        fontSize = 24.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.whitelist_empty_hint),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontFamily = VT323Font,
                        fontSize = 20.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = VT323Font,
                    fontSize = 24.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList, key = { it.id }) { number ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                soundManager?.playClick()
                                selectedNumberForDetails = number
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = number.phoneNumber,
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default),
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                            IconButton(onClick = {
                                soundManager?.playClick()
                                onRemove(number)
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_from_whitelist), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    selectedNumberForDetails?.let { number ->
        CallerDetailBottomSheet(
            phoneNumber = number.phoneNumber,
            timestamp = number.timestamp,
            isBlocked = false,
            onDismiss = { selectedNumberForDetails = null },
            onRemoveFromList = { onRemove(number) },
            onDeletePermanent = { onDeletePermanent(number) },
            onToggleList = { onBlockNumber(number) },
            onAddToContacts = { numToSave -> launchAddToContacts(context, numToSave) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallerDetailBottomSheet(
    phoneNumber: String,
    timestamp: Long,
    isBlocked: Boolean,
    onDismiss: () -> Unit,
    onRemoveFromList: () -> Unit,
    onDeletePermanent: () -> Unit,
    onToggleList: () -> Unit,
    onAddToContacts: (String) -> Unit,
) {
    val context = LocalContext.current
    val soundManager = LocalSoundManager.current
    val metadataHelper = remember { PhoneNumberMetadataHelper(context) }
    val details = remember(phoneNumber) { metadataHelper.getNumberDetails(phoneNumber) }
    val dateStr = remember(timestamp) {
        SimpleDateFormat("EEE, MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.delete_permanent_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.delete_permanent_dialog_desc)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        soundManager?.playClick()
                        showDeleteConfirmDialog = false
                        onDeletePermanent()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_permanent_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    soundManager?.playClick()
                    showDeleteConfirmDialog = false
                }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = details.flagEmoji,
                fontSize = 38.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Text(
                text = details.formattedNational.ifBlank { details.rawNumber },
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Default),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (details.formattedInternational.isNotBlank() && details.formattedInternational != details.formattedNational) {
                Text(
                    text = details.formattedInternational,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (details.countryName.isNotBlank()) {
                        DetailInfoRow(
                            icon = Icons.Default.Public,
                            label = stringResource(R.string.country_label),
                            value = if (details.countryCode > 0) "${details.countryName} (+${details.countryCode})" else details.countryName
                        )
                    }

                    if (details.location.isNotBlank()) {
                        DetailInfoRow(
                            icon = Icons.Default.Place,
                            label = stringResource(R.string.location_label),
                            value = details.location
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Button(
                onClick = {
                    soundManager?.playClick()
                    onAddToContacts(details.formattedNational.ifBlank { details.rawNumber })
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_add_to_contacts))
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedButton(
                onClick = {
                    soundManager?.playClick()
                    onToggleList()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isBlocked) Icons.Default.CheckCircle else Icons.Default.Block,
                    contentDescription = null,
                    tint = if (isBlocked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isBlocked) stringResource(R.string.action_move_to_whitelist) else stringResource(R.string.action_move_to_blocked)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedButton(
                onClick = {
                    soundManager?.playClick()
                    onRemoveFromList()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_remove_from_list))
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextButton(
                onClick = {
                    soundManager?.playClick()
                    showDeleteConfirmDialog = true
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_delete_permanent))
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun DetailInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun ConfigurationScreen(
    roleHeld: Boolean = true,
    onRoleChanged: (Boolean) -> Unit = {},
    currentLang: String = "en",
    onLanguageChanged: (String) -> Unit = {},
    onShowPrivacyPolicy: () -> Unit = {},
    onExportNumbers: (CsvExportOption) -> Unit = {},
    onSaveNumbersToLocalFile: (CsvExportOption) -> Unit = {},
    onImportBlocked: (List<String>) -> Unit = {},
    onImportWhitelist: (List<String>) -> Unit = {},
    isCheckingUpdates: Boolean = false,
    hasPendingUpdate: Boolean = false,
    cachedLatestVersion: String? = null,
    onCheckForUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    val soundManager = LocalSoundManager.current
    val isInPreview = LocalInspectionMode.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val appVersionStr = remember(isInPreview) {
        if (isInPreview) {
            "v1.0.18"
        } else {
            try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "v${pInfo.versionName}"
            } catch (_: Exception) {
                "v1.0.18"
            }
        }
    }

    var notificationState by remember {
        mutableStateOf(
            if (isInPreview) true
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            else true
        )
    }
    var contactsState by remember { mutableStateOf(if (isInPreview) true else checkPermission(context, Manifest.permission.READ_CONTACTS)) }
    var batteryState by remember { mutableStateOf(if (isInPreview) true else isIgnoringBatteryOptimizations(context)) }
    var currentRoleHeld by remember { mutableStateOf(roleHeld) }

    // Dialogs for CSV
    var pendingImportNumbers by remember { mutableStateOf<List<String>?>(null) }
    var showExportChoiceDialog by remember { mutableStateOf(false) }
    var selectedExportOption by remember { mutableStateOf(CsvExportOption.ALL) }

    // Easter Egg State
    val alienAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    var isAlienFlying by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationState = checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        }
        contactsState = checkPermission(context, Manifest.permission.READ_CONTACTS)
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        val held = checkRoleHeld(context)
        currentRoleHeld = held
        onRoleChanged(held)
    }

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val numbers = CsvContactHelper.parseCsvForPhoneNumbers(stream)
                    if (numbers.isNotEmpty()) {
                        pendingImportNumbers = numbers
                    } else {
                        Toast.makeText(context, R.string.csv_import_empty, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, R.string.csv_import_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showExportChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showExportChoiceDialog = false },
            icon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.export_target_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.export_target_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    FilterChip(
                        selected = selectedExportOption == CsvExportOption.ALL,
                        onClick = {
                            soundManager?.playClick()
                            selectedExportOption = CsvExportOption.ALL
                        },
                        shape = RoundedCornerShape(4.dp),
                        leadingIcon = if (selectedExportOption == CsvExportOption.ALL) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.export_option_both), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    FilterChip(
                        selected = selectedExportOption == CsvExportOption.BLOCKED_ONLY,
                        onClick = {
                            soundManager?.playClick()
                            selectedExportOption = CsvExportOption.BLOCKED_ONLY
                        },
                        shape = RoundedCornerShape(4.dp),
                        leadingIcon = if (selectedExportOption == CsvExportOption.BLOCKED_ONLY) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.export_option_blocked), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    FilterChip(
                        selected = selectedExportOption == CsvExportOption.WHITELIST_ONLY,
                        onClick = {
                            soundManager?.playClick()
                            selectedExportOption = CsvExportOption.WHITELIST_ONLY
                        },
                        shape = RoundedCornerShape(4.dp),
                        leadingIcon = if (selectedExportOption == CsvExportOption.WHITELIST_ONLY) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.export_option_whitelist), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            soundManager?.playClick()
                            showExportChoiceDialog = false
                            onExportNumbers(selectedExportOption)
                        },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.export_action_share))
                    }

                    OutlinedButton(
                        onClick = {
                            soundManager?.playClick()
                            showExportChoiceDialog = false
                            onSaveNumbersToLocalFile(selectedExportOption)
                        },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.export_action_save))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showExportChoiceDialog = false },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    if (pendingImportNumbers != null) {
        val numbers = pendingImportNumbers ?: emptyList()
        AlertDialog(
            onDismissRequest = { pendingImportNumbers = null },
            icon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.import_target_dialog_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.import_target_dialog_desc, numbers.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        soundManager?.playClick()
                        onImportBlocked(numbers)
                        pendingImportNumbers = null
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(stringResource(R.string.import_to_blocked))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        soundManager?.playClick()
                        onImportWhitelist(numbers)
                        pendingImportNumbers = null
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(stringResource(R.string.import_to_whitelist))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.configuration_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            LanguageFlagButton(
                currentLang = currentLang,
                onLanguageChanged = onLanguageChanged
            )
        }

        // 1. Setup Status & Permissions Checklist
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.permissions_health_title),
                        fontFamily = VT323Font,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = stringResource(R.string.permissions_health_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f)
                )

                // Item 1: Call Screening Role
                PermissionCheckRow(
                    title = stringResource(R.string.step_role_title),
                    isGranted = currentRoleHeld,
                    btnText = stringResource(R.string.set_default_app),
                    onAction = {
                        soundManager?.playClick()
                        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                        roleManager?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)?.let { roleLauncher.launch(it) }
                    }
                )

                // Item 2: Contacts Permission
                PermissionCheckRow(
                    title = stringResource(R.string.step_contacts_title),
                    isGranted = contactsState,
                    btnText = stringResource(R.string.grant_permission),
                    onAction = {
                        soundManager?.playClick()
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                )

                // Item 3: Notifications Permission
                PermissionCheckRow(
                    title = stringResource(R.string.step_notification_title),
                    isGranted = notificationState,
                    btnText = stringResource(R.string.grant_permission),
                    onAction = {
                        soundManager?.playClick()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )

                // Item 4: Battery Optimization Exemption
                PermissionCheckRow(
                    title = stringResource(R.string.step_battery_title),
                    isGranted = batteryState,
                    btnText = stringResource(R.string.grant_permission),
                    onAction = {
                        soundManager?.playClick()
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. CSV Backup & Restore Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SyncAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.csv_backup_title),
                        fontFamily = VT323Font,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = stringResource(R.string.csv_backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            soundManager?.playClick()
                            showExportChoiceDialog = true
                        },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.export_csv_btn), fontSize = 11.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            soundManager?.playClick()
                            importCsvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
                        },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.import_csv_btn), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. App Updates Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.updates_card_title),
                        fontFamily = VT323Font,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = stringResource(R.string.updates_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.updates_status_checking),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        } else if (hasPendingUpdate && !cachedLatestVersion.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Default.NewReleases,
                                contentDescription = null,
                                tint = Color(0xFFFFCC00),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.updates_status_available, cachedLatestVersion),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFFCC00)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.updates_status_up_to_date, appVersionStr),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onCheckForUpdates,
                        enabled = !isCheckingUpdates,
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (hasPendingUpdate) Icons.Default.Download else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (hasPendingUpdate) stringResource(R.string.update_dialog_install_now) else stringResource(R.string.updates_btn_check),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. Contact Developer Button
        val deviceInfoHeader = stringResource(R.string.device_info_header)
        val supportSubject = stringResource(R.string.support_email_subject)
        
        Button(
            onClick = {
                soundManager?.playClick()
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})"
                
                val deviceInfo = """
                    $deviceInfoHeader
                    App Version: $appVersion
                    Model: ${Build.MANUFACTURER} ${Build.MODEL}
                    Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                """.trimIndent()

                val email = "prismaticamedia@gmail.com"
                val uriString = "mailto:$email?subject=${Uri.encode(supportSubject)}&body=${Uri.encode(deviceInfo)}"

                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = uriString.toUri()
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, supportSubject)
                    putExtra(Intent.EXTRA_TEXT, deviceInfo)
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.no_email_app_found, Toast.LENGTH_SHORT).show()
                }
            },
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.Email, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.contact_btn))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5. Privacy Policy Button
        Button(
            onClick = {
                soundManager?.playClick()
                onShowPrivacyPolicy()
            },
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.PrivacyTip, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.privacy_policy_title))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.app_version_footer, appVersionStr),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .clickable {
                    if (!isAlienFlying) {
                        isAlienFlying = true
                        soundManager?.playAlienSound()
                        coroutineScope.launch {
                            alienAnim.snapTo(0f)
                            alienAnim.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = 2400,
                                    easing = androidx.compose.animation.core.LinearEasing
                                )
                            )
                            isAlienFlying = false
                        }
                    }
                }
        )
    }

    if (isAlienFlying) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val screenW = maxWidth
            val screenH = maxHeight
            val progress = alienAnim.value

            // Alien flight coordinates: swoops across from left (-alienSize) to right (screenW + alienSize)
            val alienSize = 56.dp
            val alienStartX = -alienSize
            val alienEndX = screenW + alienSize
            val alienX = androidx.compose.ui.unit.lerp(alienStartX, alienEndX, progress)
            // Wavy flight pattern like classic Galaxian arcade alien dive
            val alienY = (screenH * 0.20f) + ((kotlin.math.sin(progress * Math.PI.toFloat() * 2f)) * 40).dp

            // Helper to get exact bottom-center of the alien at a given firing progress
            fun getAlienShotOrigin(fireProgress: Float): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
                val originX = androidx.compose.ui.unit.lerp(alienStartX, alienEndX, fireProgress) + (alienSize / 2) - 2.5.dp
                val originY = (screenH * 0.20f) + ((kotlin.math.sin(fireProgress * Math.PI.toFloat() * 2f)) * 40).dp + alienSize - 4.dp
                return Pair(originX, originY)
            }

            // Shot 1: Fired at progress 0.35f directly from alien bottom-center
            val fire1Time = 0.35f
            val (shot1OriginX, shot1OriginY) = getAlienShotOrigin(fire1Time)
            if (progress >= fire1Time) {
                val shot1Progress = ((progress - fire1Time) / 0.45f).coerceIn(0f, 1f)
                if (shot1Progress < 1f) {
                    val shot1Y = androidx.compose.ui.unit.lerp(shot1OriginY, screenH + 40.dp, shot1Progress)
                    Box(
                        modifier = Modifier
                            .offset(x = shot1OriginX, y = shot1Y)
                            .size(width = 5.dp, height = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFF3333))
                    )
                }
            }

            // Shot 2: Fired at progress 0.58f directly from alien bottom-center
            val fire2Time = 0.58f
            val (shot2OriginX, shot2OriginY) = getAlienShotOrigin(fire2Time)
            if (progress >= fire2Time) {
                val shot2Progress = ((progress - fire2Time) / 0.40f).coerceIn(0f, 1f)
                if (shot2Progress < 1f) {
                    val shot2Y = androidx.compose.ui.unit.lerp(shot2OriginY, screenH + 40.dp, shot2Progress)
                    Box(
                        modifier = Modifier
                            .offset(x = shot2OriginX, y = shot2Y)
                            .size(width = 5.dp, height = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFFCC00))
                    )
                }
            }

            // Galaxian Alien Sprite (stays upright, no rotation)
            Image(
                painter = painterResource(R.drawable.alien),
                contentDescription = "Galaxian Alien",
                modifier = Modifier
                    .offset(x = alienX, y = alienY)
                    .size(alienSize)
            )
        }
    }
}
}

@Composable
fun PermissionCheckRow(
    title: String,
    isGranted: Boolean,
    btnText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
        if (!isGranted) {
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(btnText, fontSize = 12.sp)
            }
        } else {
            Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50),
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.privacy_policy_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.privacy_policy_content),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(stringResource(R.string.close_btn))
            }
        }
    )
}

@Composable
fun UpdateAvailableDialog(
    updateStatus: UpdateStatus,
    onDismiss: () -> Unit,
    onInstallNow: () -> Unit
) {
    val (latestVer, currentVer, releaseNotes) = when (updateStatus) {
        is UpdateStatus.Available -> Triple(updateStatus.latestVersion, updateStatus.currentVersion, updateStatus.releaseNotes)
        is UpdateStatus.Downloading -> Triple(updateStatus.latestVersion, updateStatus.currentVersion, updateStatus.releaseNotes)
        is UpdateStatus.ReadyToInstall -> Triple(updateStatus.latestVersion, updateStatus.currentVersion, updateStatus.releaseNotes)
        else -> Triple("", "", "")
    }

    val isDownloading = updateStatus is UpdateStatus.Downloading
    val isReadyToInstall = updateStatus is UpdateStatus.ReadyToInstall

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_dialog_title),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (latestVer.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.update_dialog_version,
                                    latestVer,
                                    currentVer
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                if (updateStatus is UpdateStatus.Downloading) {
                    val progress = updateStatus.progressPercent
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.updates_status_downloading, progress),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            )
                        }
                    }
                } else if (updateStatus is UpdateStatus.ReadyToInstall) {
                    Surface(
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.updates_status_ready),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81C784),
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (releaseNotes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.update_dialog_changelog),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onInstallNow,
                enabled = !isDownloading,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.update_dialog_install_now))
            }
        },
        dismissButton = {
            if (!isDownloading) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(stringResource(R.string.update_dialog_later))
                }
            }
        }
    )
}

fun getDateGroupTitle(context: Context, timestamp: Long): String {
    return DateGroupHelper(context).getDateGroupTitle(timestamp)
}

fun matchesSearchQuery(context: Context, phoneNumber: String, timestamp: Long, query: String): Boolean {
    val helper = DateGroupHelper(context)
    return helper.matchesSearchQuery(phoneNumber, timestamp, query.trim().lowercase(Locale.getDefault()))
}

fun launchAddToContacts(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
        putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallbackIntent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.toast_contact_intent_error, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun checkPermission(context: Context, permission: String): Boolean {
    return try {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return try {
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

private fun checkRoleHeld(context: Context): Boolean {
    val roleManager = (context.getSystemService(Context.ROLE_SERVICE) as? RoleManager) ?: return false
    return try {
        roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    } catch (_: Exception) {
        false
    }
}

// ==========================================
// 🎨 Jetpack Compose Android Studio Previews
// ==========================================

@Composable
fun PreviewBackgroundContainer(content: @Composable () -> Unit) {
    BlockyTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Image(
                painter = painterResource(R.drawable.bg_app_pattern),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.22f),
                alpha = 0.35f
            )
            content()
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "1. Onboarding Screen")
@Composable
fun OnboardingPreview() {
    BlockyTheme {
        OnboardingScreen(
            onCompleted = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "2. Protection Active (With Bricks)")
@Composable
fun BlockyScreenActivePreview() {
    PreviewBackgroundContainer {
        BlockyScreen(
            isRoleHeldInitial = true,
            onRoleChanged = {},
            isEnabledInitial = true,
            onEnabledChanged = {},
            isBlockSoundEnabled = true,
            onBlockSoundEnabledChanged = {},
            blockedCount = 42,
            repeatCallThreshold = 2,
            repeatCallIntervalMinutes = 15,
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "3. Protection Inactive (With Bricks)")
@Composable
fun BlockyScreenInactivePreview() {
    PreviewBackgroundContainer {
        BlockyScreen(
            isRoleHeldInitial = false,
            onRoleChanged = {},
            isEnabledInitial = false,
            onEnabledChanged = {},
            isBlockSoundEnabled = false,
            onBlockSoundEnabledChanged = {},
            blockedCount = 0
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "4. Blocked Numbers List (With Bricks)")
@Composable
fun BlockedListPreview() {
    val mockBlocked = listOf(
        BlockedCall(id = 1, phoneNumber = "+1 555-0101"),
        BlockedCall(id = 2, phoneNumber = "+1 555-0102"),
        BlockedCall(id = 3, phoneNumber = "Private / Unknown")
    )
    PreviewBackgroundContainer {
        BlockedListScreen(
            blockedList = mockBlocked,
            onUnblock = {},
            onUnblockAll = {},
            onWhitelist = {},
            onDeletePermanent = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "5. Whitelist Screen (With Bricks)")
@Composable
fun WhitelistPreview() {
    val mockWhitelist = listOf(
        WhitelistedNumber(id = 1, phoneNumber = "+1 555-9999"),
        WhitelistedNumber(id = 2, phoneNumber = "+1 555-1234")
    )
    PreviewBackgroundContainer {
        WhitelistScreen(
            whitelist = mockWhitelist,
            onRemove = {},
            onDeletePermanent = {},
            onBlockNumber = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "6. Configuration & Settings (With Bricks)")
@Composable
fun ConfigurationPreview() {
    PreviewBackgroundContainer {
        ConfigurationScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "7. Full Main App Scaffold")
@Composable
fun FullMainAppPreview() {
    BlockyTheme {
        MainContent(
            roleHeld = true,
            isEnabled = true,
            isSoundEnabled = true,
            isBlockSoundEnabled = true,
            blockedCount = 42,
            blockedList = emptyList(),
            whitelist = emptyList(),
            currentLang = "en",
            repeatCallThreshold = 2,
            repeatCallIntervalMinutes = 15,
            onRoleChanged = {},
            onEnabledChanged = {},
            onSoundEnabledChanged = {},
            onBlockSoundEnabledChanged = {},
            onLanguageChanged = {},
            onThresholdChanged = {},
            onIntervalMinutesChanged = {},
            onUnblockNumber = {},
            onUnblockAll = {},
            onAddToWhitelistFromBlocked = {},
            onAddToBlockedFromWhitelist = {},
            onDeleteBlockedPermanent = {},
            onDeleteWhitelistPermanent = {},
            onRemoveFromWhitelist = {},
            onAddBlockedManualNumber = {},
            onAddWhitelistManualNumber = {},
            onImportNumbersToBlocked = {},
            onImportNumbersToWhitelist = {},
            onExportNumbersToCsv = {},
            onSaveNumbersToLocalFile = {},
            onFinishOnboarding = {}
        )
    }
}

private fun updateAppLocale(context: Context, lang: String) {
    val newLocale = Locale.forLanguageTag(lang)
    Locale.setDefault(newLocale)
    val appContext = context.applicationContext
    val config = Configuration(appContext.resources.configuration).apply {
        setLocale(newLocale)
        setLayoutDirection(newLocale)
    }
    @Suppress("DEPRECATION")
    appContext.resources.updateConfiguration(config, appContext.resources.displayMetrics)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            (context.getSystemService(Context.LOCALE_SERVICE) as? android.app.LocaleManager)?.applicationLocales =
                android.os.LocaleList.forLanguageTags(lang)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
