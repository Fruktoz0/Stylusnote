package hu.bdev.stylusnote.ui.shelf

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.auth.api.identity.Identity
import hu.bdev.stylusnote.data.export.BackupManager
import hu.bdev.stylusnote.data.export.CloudProviderType
import hu.bdev.stylusnote.data.export.CloudSyncEngine
import hu.bdev.stylusnote.data.export.CloudSyncPreferencesStore
import hu.bdev.stylusnote.data.export.CloudSyncRuntimeStatus
import hu.bdev.stylusnote.data.export.CloudSyncScheduleMode
import hu.bdev.stylusnote.data.export.CloudSyncScheduler
import hu.bdev.stylusnote.data.export.CloudSyncSettings
import hu.bdev.stylusnote.data.export.CloudSyncStatusBus
import hu.bdev.stylusnote.data.export.DriveAuthOutcome
import hu.bdev.stylusnote.data.export.GoogleDriveAuthorizationHelper
import hu.bdev.stylusnote.data.export.RemoteCloudSummary
import hu.bdev.stylusnote.data.export.SyncExecutionMode
import hu.bdev.stylusnote.data.export.TrashedNotebookRecord
import hu.bdev.stylusnote.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact reactive Cloud status button shared between `CanvasTopToolbar` (next to notebook title)
 * and the `BookshelfScreen` left sidebar header. Short tap starts an immediate sync when configured
 * (or opens settings if unconfigured/needing re-auth); long press always opens [CloudBackupDialog].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudSyncStatusBadgeButton(
    onOpenSettingsDialog: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onQuickSyncTriggered: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val appContext = context.applicationContext
    val prefsStore = remember(appContext) { CloudSyncPreferencesStore(appContext) }
    val settings by prefsStore.settingsFlow.collectAsState(initial = CloudSyncSettings())
    val isSyncing by CloudSyncStatusBus.isSyncing.collectAsState()
    val status = settings.deriveRuntimeStatus(isSyncing)

    val (icon, tint, label) = when (status) {
        CloudSyncRuntimeStatus.DISABLED -> Triple(
            Icons.Outlined.Cloud,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            "Felhőmentés nincs beállítva (hosszan: beállítások)"
        )
        CloudSyncRuntimeStatus.IDLE_SYNCED -> Triple(
            Icons.Filled.CloudDone,
            Color(0xFF1E8E3E),
            "Felhőbe mentve (koppintás: szinkron, hosszan: beállítások)"
        )
        CloudSyncRuntimeStatus.PENDING_CHANGES -> Triple(
            Icons.Filled.CloudUpload,
            Color(0xFFD97706),
            "Függőben lévő változások (koppintás: azonnali mentés)"
        )
        CloudSyncRuntimeStatus.SYNCING -> Triple(
            Icons.Filled.CloudSync,
            MaterialTheme.colorScheme.primary,
            "Szinkronizálás folyamatban"
        )
        CloudSyncRuntimeStatus.NEEDS_REAUTH,
        CloudSyncRuntimeStatus.ERROR -> Triple(
            Icons.Filled.CloudOff,
            MaterialTheme.colorScheme.error,
            "Felhőhiba vagy újrahitelesítés szükséges"
        )
    }

    val spinAngle = if (status == CloudSyncRuntimeStatus.SYNCING) {
        val transition = rememberInfiniteTransition(label = "cloud_sync_spin")
        val anim by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "cloud_sync_angle"
        )
        anim
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .combinedClickable(
                onClick = {
                    if (!settings.isConfigured || settings.needsReauth) {
                        onOpenSettingsDialog()
                    } else if (!isSyncing) {
                        CloudSyncScheduler.triggerImmediateManualSync(appContext)
                        onQuickSyncTriggered?.invoke("Felhőszinkron elindítva...")
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenSettingsDialog()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                .rotate(if (status == CloudSyncRuntimeStatus.SYNCING) -spinAngle else 0f)
        )
        if (status == CloudSyncRuntimeStatus.PENDING_CHANGES ||
            status == CloudSyncRuntimeStatus.NEEDS_REAUTH ||
            status == CloudSyncRuntimeStatus.ERROR
        ) {
            val dotColor = if (status == CloudSyncRuntimeStatus.PENDING_CHANGES) {
                Color(0xFFD97706)
            } else {
                MaterialTheme.colorScheme.error
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

/**
 * Full cloud backup, restore, schedule policy, and manual SAF `.zip` export/import modal.
 * Supports dismissal via ESC key, back navigation, Close icon, or clicking outside backdrop.
 */
@Composable
fun CloudBackupDialog(
    database: AppDatabase,
    onDismiss: () -> Unit,
    onBackupNotice: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val prefsStore = remember(appContext) { CloudSyncPreferencesStore(appContext) }
    val backupManager = remember(appContext) { BackupManager(appContext.filesDir) }

    val settings by prefsStore.settingsFlow.collectAsState(initial = CloudSyncSettings())
    val isSyncing by CloudSyncStatusBus.isSyncing.collectAsState()
    val runtimeStatus = settings.deriveRuntimeStatus(isSyncing)

    var selectedTab by remember(settings.providerType) {
        mutableStateOf(
            if (settings.providerType == CloudProviderType.WEBDAV) {
                CloudProviderType.WEBDAV
            } else {
                CloudProviderType.GOOGLE_DRIVE
            }
        )
    }

    var webDavUrl by remember(settings.webDavBaseUrl) { mutableStateOf(settings.webDavBaseUrl) }
    var webDavUser by remember(settings.webDavUsername) { mutableStateOf(settings.webDavUsername) }
    var webDavPass by remember { mutableStateOf("") }

    var busyActionMessage by remember { mutableStateOf<String?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    var discoveredRemoteBackup by remember { mutableStateOf<RemoteCloudSummary?>(null) }
    var confirmOverwriteRestore by remember { mutableStateOf(false) }
    var showTrashRestoreDialog by remember { mutableStateOf(false) }

    suspend fun probeAfterConnect() {
        runCatching {
            CloudSyncScheduler.probeConfiguredRemoteBackup(appContext)
        }.onSuccess { summary ->
            if (summary.exists) {
                discoveredRemoteBackup = summary
                feedbackMessage = "Kapcsolat kész. Felhőmentés található: ${summary.notebookCount} füzet."
                feedbackIsError = false
            } else {
                discoveredRemoteBackup = null
                CloudSyncScheduler.triggerImmediateManualSync(appContext)
                feedbackMessage = "Kapcsolat mentve, első felhőmentés elindítva..."
                feedbackIsError = false
            }
        }.onFailure { err ->
            feedbackMessage = "Kapcsolat mentve, de az ellenőrzés hibát jelzett: ${err.message ?: "ismeretlen hiba"}"
            feedbackIsError = true
        }
    }

    suspend fun finishGoogleDriveAuthorization(accessToken: String) {
        busyActionMessage = "Google-fiók ellenőrzése..."
        try {
            val email = GoogleDriveAuthorizationHelper.fetchDriveAccountEmail(accessToken)
                ?: "Csatlakoztatott Google-fiók"
            prefsStore.saveGoogleDriveConnection(accountEmail = email)
            CloudSyncScheduler.syncPeriodicSchedule(appContext)
            probeAfterConnect()
        } finally {
            busyActionMessage = null
        }
    }

    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            feedbackMessage = "A Google Drive engedélyezés megszakadt."
            feedbackIsError = true
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                val authResult = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(activityResult.data)
                val token = authResult.accessToken
                    ?: throw IllegalStateException("Nem érkezett Google Drive hozzáférési token.")
                finishGoogleDriveAuthorization(token)
            }.onFailure { err ->
                feedbackMessage = "Google Drive hitelesítés sikertelen: ${err.message ?: "OAuth beállítás szükséges"}"
                feedbackIsError = true
                busyActionMessage = null
            }
        }
    }

    val zipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { destUri ->
        if (destUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyActionMessage = "Biztonsági mentés exportálása (.zip)..."
            feedbackMessage = null
            runCatching {
                backupManager.exportToSafUri(context, database, destUri)
            }.onSuccess { summary ->
                val msg = "Exportálva: ${summary.manifest.notebooks.size} füzet, ${summary.packagedAssetFileCount} fájl."
                feedbackMessage = msg
                feedbackIsError = false
                onBackupNotice(msg)
            }.onFailure { err ->
                feedbackMessage = "Exportálás sikertelen: ${err.message ?: "ismeretlen hiba"}"
                feedbackIsError = true
            }
            busyActionMessage = null
        }
    }

    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { srcUri ->
        if (srcUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyActionMessage = "Biztonsági mentés visszaállítása (.zip)..."
            feedbackMessage = null
            runCatching {
                backupManager.importFromSafUri(context, database, srcUri)
            }.onSuccess { summary ->
                CloudSyncScheduler.notifyDatabaseMetadataChanged(appContext)
                val msg = "Visszaállítva: ${summary.manifest.notebooks.size} füzet, ${summary.manifest.pages.size} oldal."
                feedbackMessage = msg
                feedbackIsError = false
                onBackupNotice(msg)
            }.onFailure { err ->
                feedbackMessage = "Visszaállítás sikertelen: ${err.message ?: "sérült vagy nem StylusNote zip"}"
                feedbackIsError = true
            }
            busyActionMessage = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .widthIn(min = 340.dp, max = 560.dp)
                    .heightIn(max = 760.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header + live status pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Felhőszinkron és Biztonsági Mentés",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatSyncStatusSummary(settings, runtimeStatus),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (runtimeStatus == CloudSyncRuntimeStatus.ERROR ||
                                    runtimeStatus == CloudSyncRuntimeStatus.NEEDS_REAUTH
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Bezárás (ESC)")
                        }
                    }

                    if (!settings.lastErrorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = settings.lastErrorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (busyActionMessage != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = busyActionMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (feedbackMessage != null) {
                        Surface(
                            color = if (feedbackIsError) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = feedbackMessage.orEmpty(),
                                color = if (feedbackIsError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    discoveredRemoteBackup?.let { remoteSummary ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Felhőben meglévő mentés található",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${remoteSummary.notebookCount} jegyzetfüzet, ${remoteSummary.pageCount} oldal (${formatEpochDateTime(remoteSummary.updatedAtEpochMs)})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            CloudSyncScheduler.triggerImmediateManualSync(
                                                appContext,
                                                SyncExecutionMode.RESTORE_FROM_CLOUD
                                            )
                                            discoveredRemoteBackup = null
                                            feedbackMessage = "Visszaállítás és összefésülés elindítva..."
                                            feedbackIsError = false
                                        }
                                    ) {
                                        Text("Visszaállítás")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            CloudSyncScheduler.triggerImmediateManualSync(
                                                appContext,
                                                SyncExecutionMode.BIDIRECTIONAL_MERGE
                                            )
                                            discoveredRemoteBackup = null
                                            feedbackMessage = "Kétirányú szinkron elindítva..."
                                            feedbackIsError = false
                                        }
                                    ) {
                                        Text("Összefésülés")
                                    }
                                }
                            }
                        }
                    }

                    // Provider selector tabs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProviderChoiceChip(
                            label = "Google Drive",
                            selected = selectedTab == CloudProviderType.GOOGLE_DRIVE,
                            onClick = { selectedTab = CloudProviderType.GOOGLE_DRIVE },
                            modifier = Modifier.weight(1f)
                        )
                        ProviderChoiceChip(
                            label = "WebDAV (NAS / Nextcloud)",
                            selected = selectedTab == CloudProviderType.WEBDAV,
                            onClick = { selectedTab = CloudProviderType.WEBDAV },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (selectedTab == CloudProviderType.GOOGLE_DRIVE) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val connectedDrive = settings.providerType == CloudProviderType.GOOGLE_DRIVE &&
                                    !settings.googleDriveAccountEmail.isNullOrBlank()
                                Text(
                                    text = if (connectedDrive) {
                                        "Csatlakozva: ${settings.googleDriveAccountEmail}\nMappa: StylusNote Backups (csak az alkalmazás saját fájljaihoz fér hozzá)"
                                    } else {
                                        "A StylusNote kizárólag a saját \"StylusNote Backups\" mappáját kezeli a Google Drive-odon (drive.file jogosultság)."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = busyActionMessage == null,
                                        onClick = {
                                            scope.launch {
                                                busyActionMessage = "Google Drive csatlakoztatása..."
                                                feedbackMessage = null
                                                runCatching {
                                                    when (val outcome = GoogleDriveAuthorizationHelper.authorize(context)) {
                                                        is DriveAuthOutcome.Authorized ->
                                                            finishGoogleDriveAuthorization(outcome.accessToken)
                                                        is DriveAuthOutcome.NeedsResolution -> {
                                                            busyActionMessage = null
                                                            val req = IntentSenderRequest.Builder(
                                                                outcome.pendingIntent.intentSender
                                                            ).build()
                                                            driveAuthLauncher.launch(req)
                                                        }
                                                    }
                                                }.onFailure { err ->
                                                    busyActionMessage = null
                                                    feedbackMessage = "Google Drive csatlakozás nem érhető el (${err.message ?: "ellenőrizd a Google Cloud OAuth Android klienst"}). WebDAV és .zip mentés azonnal használható."
                                                    feedbackIsError = true
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            when {
                                                !connectedDrive -> "Google Drive csatlakoztatása"
                                                settings.needsReauth -> "Újraengedélyezés"
                                                else -> "Fiók ellenőrzése / frissítése"
                                            }
                                        )
                                    }
                                    if (connectedDrive) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    prefsStore.disconnectProvider()
                                                    CloudSyncScheduler.syncPeriodicSchedule(appContext)
                                                    feedbackMessage = "Google Drive leválasztva."
                                                    feedbackIsError = false
                                                }
                                            }
                                        ) {
                                            Text("Leválasztás")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = webDavUrl,
                                    onValueChange = { webDavUrl = it },
                                    label = { Text("WebDAV szerver URL") },
                                    placeholder = { Text("https://nas.sajat.hu/remote.php/dav/files/nev") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = webDavUser,
                                    onValueChange = { webDavUser = it },
                                    label = { Text("Felhasználónév") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = webDavPass,
                                    onValueChange = { webDavPass = it },
                                    label = { Text("Jelszó / App-jelszó") },
                                    placeholder = {
                                        Text(
                                            if (settings.hasWebDavPassword) {
                                                "Mentve a Keystore-ban (üresen = megtartva)"
                                            } else {
                                                "Add meg a jelszót"
                                            }
                                        )
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = busyActionMessage == null,
                                        onClick = {
                                            val cleanUrl = webDavUrl.trim()
                                            val cleanUser = webDavUser.trim()
                                            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                                                feedbackMessage = "Az URL-nek http:// vagy https:// előtaggal kell kezdődnie."
                                                feedbackIsError = true
                                                return@Button
                                            }
                                            if (cleanUser.isBlank()) {
                                                feedbackMessage = "A felhasználónév megadása kötelező."
                                                feedbackIsError = true
                                                return@Button
                                            }
                                            if (webDavPass.isEmpty() && !settings.hasWebDavPassword) {
                                                feedbackMessage = "Add meg a WebDAV jelszót."
                                                feedbackIsError = true
                                                return@Button
                                            }
                                            scope.launch {
                                                busyActionMessage = "WebDAV kapcsolat tesztelése..."
                                                feedbackMessage = null
                                                runCatching {
                                                    prefsStore.saveWebDavConfig(
                                                        baseUrl = cleanUrl,
                                                        username = cleanUser,
                                                        rawPassword = webDavPass.takeIf { it.isNotEmpty() }
                                                    )
                                                    webDavPass = ""
                                                    CloudSyncScheduler.syncPeriodicSchedule(appContext)
                                                    probeAfterConnect()
                                                }.onFailure { err ->
                                                    feedbackMessage = "WebDAV mentés sikertelen: ${err.message}"
                                                    feedbackIsError = true
                                                }
                                                busyActionMessage = null
                                            }
                                        }
                                    ) {
                                        Text("Mentés és teszt")
                                    }
                                    if (settings.providerType == CloudProviderType.WEBDAV) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    prefsStore.disconnectProvider()
                                                    CloudSyncScheduler.syncPeriodicSchedule(appContext)
                                                    feedbackMessage = "WebDAV kapcsolat bontva."
                                                    feedbackIsError = false
                                                }
                                            }
                                        ) {
                                            Text("Leválasztás")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Schedule frequency & network policy
                    Text(
                        text = "Automatikus mentés ütemezése",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CloudSyncScheduleMode.entries.forEach { mode ->
                            val selected = settings.scheduleMode == mode
                            Surface(
                                onClick = {
                                    scope.launch {
                                        prefsStore.updateScheduleAndNetwork(mode, settings.wifiOnly)
                                        CloudSyncScheduler.syncPeriodicSchedule(appContext)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = null
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mode.labelHu,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = mode.descriptionHu,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Csak Wi-Fi-n automatikusan",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Kézi szinkronizálás mobilneten is futtatható.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.wifiOnly,
                            onCheckedChange = { wifiOnly ->
                                scope.launch {
                                    prefsStore.updateScheduleAndNetwork(settings.scheduleMode, wifiOnly)
                                    CloudSyncScheduler.syncPeriodicSchedule(appContext)
                                }
                            }
                        )
                    }

                    if (settings.isConfigured) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = !isSyncing && busyActionMessage == null,
                                onClick = {
                                    CloudSyncScheduler.triggerImmediateManualSync(
                                        appContext,
                                        SyncExecutionMode.BIDIRECTIONAL_MERGE
                                    )
                                    feedbackMessage = "Szinkronizálás elindítva..."
                                    feedbackIsError = false
                                    onBackupNotice("Felhőszinkron elindítva...")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Szinkron most", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                enabled = !isSyncing && busyActionMessage == null,
                                onClick = { confirmOverwriteRestore = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Visszaállítás", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        OutlinedButton(
                            enabled = !isSyncing && busyActionMessage == null,
                            onClick = { showTrashRestoreDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cloud_trash_open_button")
                        ) {
                            Icon(Icons.Filled.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "Törölt jegyzetek helyreállítása (7 napos lomtár)",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (confirmOverwriteRestore) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Letöltse a felhőben tárolt oldalakat?",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { confirmOverwriteRestore = false }) {
                                        Text("Mégse")
                                    }
                                    Button(
                                        onClick = {
                                            confirmOverwriteRestore = false
                                            CloudSyncScheduler.triggerImmediateManualSync(
                                                appContext,
                                                SyncExecutionMode.RESTORE_FROM_CLOUD
                                            )
                                            feedbackMessage = "Felhő-visszaállítás elindítva..."
                                            feedbackIsError = false
                                        }
                                    ) {
                                        Text("Igen")
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Manual SAF .zip backup/restore (no cloud account required)
                    Text(
                        text = "Helyi biztonsági mentés (.zip)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Teljes füzetstruktúra, oldalak, képek, PDF-ek és keresési index offline mentése/visszatöltése.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = busyActionMessage == null,
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                zipExportLauncher.launch("stylusnote_backup_$stamp.zip")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Export (.zip)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            enabled = busyActionMessage == null,
                            onClick = {
                                zipImportLauncher.launch(
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Import (.zip)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showTrashRestoreDialog) {
        CloudTrashRestoreDialog(
            database = database,
            settings = settings,
            prefsStore = prefsStore,
            onRestoredNotebook = { title ->
                val msg = "\"$title\" helyreállítva a felhőből."
                feedbackMessage = msg
                feedbackIsError = false
                onBackupNotice(msg)
            },
            onDismiss = { showTrashRestoreDialog = false }
        )
    }
}

/**
 * Recovery modal listing notebooks retained in the 7-day cloud trash (`cloud-trash.json`),
 * showing remaining retention days/hours and allowing 1-tap restore or permanent deletion.
 * Supports ESC key and backdrop click dismissal.
 */
@Composable
fun CloudTrashRestoreDialog(
    database: AppDatabase,
    settings: CloudSyncSettings,
    prefsStore: CloudSyncPreferencesStore,
    onRestoredNotebook: (String) -> Unit,
    onDismiss: () -> Unit,
    preloadedEntriesForPreview: List<TrashedNotebookRecord>? = null,
    onRestoreActionOverride: (suspend (TrashedNotebookRecord) -> Unit)? = null,
    onPermanentDeleteActionOverride: (suspend (TrashedNotebookRecord) -> Unit)? = null
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(preloadedEntriesForPreview ?: emptyList()) }
    var isLoading by remember { mutableStateOf(preloadedEntriesForPreview == null) }
    var actionInProgressId by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    suspend fun reloadTrashEntries() {
        if (preloadedEntriesForPreview != null) {
            isLoading = false
            return
        }
        isLoading = true
        statusError = null
        runCatching {
            val remoteClient = CloudSyncScheduler.createRemoteClient(appContext, settings, prefsStore)
            CloudSyncEngine(appContext.filesDir, database).listTrashedNotebooks(remoteClient)
        }.onSuccess { loaded ->
            entries = loaded
        }.onFailure { err ->
            statusError = err.message ?: "Nem sikerült lekérdezni a felhős lomtárat."
        }
        isLoading = false
    }

    LaunchedEffect(preloadedEntriesForPreview, settings) {
        if (preloadedEntriesForPreview == null) {
            reloadTrashEntries()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo.isWindowFocused) {
            if (windowInfo.isWindowFocused) {
                runCatching { focusRequester.requestFocus() }
            }
        }
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag("cloud_trash_backdrop")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .widthIn(min = 340.dp, max = 560.dp)
                    .heightIn(max = 680.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .testTag("cloud_trash_dialog"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Törölt jegyzetek helyreállítása",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "A törölt jegyzetek 7 napig megmaradnak a felhőben, utána szinkronkor automatikusan törlődnek.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Bezárás (ESC)")
                        }
                    }

                    HorizontalDivider()

                    if (!statusError.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = statusError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    when {
                        isLoading -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Lomtár lekérdezése...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        entries.isEmpty() -> {
                            Text(
                                text = "Nincs 7 napon belül törölt jegyzet a felhős lomtárban.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        }
                        else -> {
                            val nowMs = System.currentTimeMillis()
                            entries.forEach { rec ->
                                val busyHere = actionInProgressId == rec.notebookId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cloud_trash_item_${rec.notebookId}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = rec.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${rec.pageCount} oldal • Törölve: ${formatEpochDateTime(rec.deletedAtEpochMs)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "${rec.formatRemainingTimeHu(nowMs)} (lejárat: ${formatEpochDateTime(rec.expiresAtEpochMs)})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Button(
                                            enabled = actionInProgressId == null,
                                            onClick = {
                                                scope.launch {
                                                    actionInProgressId = rec.notebookId
                                                    statusError = null
                                                    runCatching {
                                                        val customRestore = onRestoreActionOverride
                                                        if (customRestore != null) {
                                                            customRestore(rec)
                                                        } else {
                                                            val client = CloudSyncScheduler.createRemoteClient(
                                                                appContext,
                                                                settings,
                                                                prefsStore
                                                            )
                                                            CloudSyncEngine(appContext.filesDir, database)
                                                                .restoreTrashedNotebook(client, rec.notebookId)
                                                        }
                                                    }.onSuccess {
                                                        entries = entries.filterNot { it.notebookId == rec.notebookId }
                                                        CloudSyncScheduler.notifyDatabaseMetadataChanged(appContext)
                                                        onRestoredNotebook(rec.title)
                                                    }.onFailure { err ->
                                                        statusError = err.message ?: "Helyreállítás sikertelen."
                                                    }
                                                    actionInProgressId = null
                                                }
                                            },
                                            modifier = Modifier.testTag("cloud_trash_restore_${rec.notebookId}")
                                        ) {
                                            if (busyHere) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text("Visszaállít")
                                            }
                                        }
                                        IconButton(
                                            enabled = actionInProgressId == null,
                                            onClick = {
                                                scope.launch {
                                                    actionInProgressId = rec.notebookId
                                                    statusError = null
                                                    runCatching {
                                                        val customDel = onPermanentDeleteActionOverride
                                                        if (customDel != null) {
                                                            customDel(rec)
                                                        } else {
                                                            val client = CloudSyncScheduler.createRemoteClient(
                                                                appContext,
                                                                settings,
                                                                prefsStore
                                                            )
                                                            CloudSyncEngine(appContext.filesDir, database)
                                                                .permanentlyDeleteTrashedNotebook(client, rec.notebookId)
                                                        }
                                                    }.onSuccess {
                                                        entries = entries.filterNot { it.notebookId == rec.notebookId }
                                                    }.onFailure { err ->
                                                        statusError = err.message ?: "Végleges törlés sikertelen."
                                                    }
                                                    actionInProgressId = null
                                                }
                                            },
                                            modifier = Modifier.testTag("cloud_trash_purge_${rec.notebookId}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteForever,
                                                contentDescription = "Végleges törlés",
                                                tint = MaterialTheme.colorScheme.error
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
    }
}

@Composable
private fun ProviderChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = if (selected) 1.25.dp else 0.75.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private fun formatSyncStatusSummary(
    settings: CloudSyncSettings,
    status: CloudSyncRuntimeStatus
): String = when (status) {
    CloudSyncRuntimeStatus.DISABLED -> "Nincs felhőszolgáltató csatlakoztatva • Helyi .zip mentés elérhető"
    CloudSyncRuntimeStatus.SYNCING -> "${settings.providerType.labelHu} • Szinkronizálás folyamatban..."
    CloudSyncRuntimeStatus.NEEDS_REAUTH -> "${settings.providerType.labelHu} • Újrahitelesítés szükséges"
    CloudSyncRuntimeStatus.ERROR -> "${settings.providerType.labelHu} • Sikertelen szinkronizálás"
    CloudSyncRuntimeStatus.PENDING_CHANGES -> "${settings.providerType.labelHu} • Függőben lévő változások"
    CloudSyncRuntimeStatus.IDLE_SYNCED -> {
        val last = if (settings.lastSyncEpochMs > 0L) {
            "Utolsó mentés: ${formatEpochDateTime(settings.lastSyncEpochMs)}"
        } else {
            "Csatlakoztatva (${settings.scheduleMode.labelHu})"
        }
        "${settings.providerType.labelHu} • $last"
    }
}

private fun formatEpochDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return "ismeretlen"
    return SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}
