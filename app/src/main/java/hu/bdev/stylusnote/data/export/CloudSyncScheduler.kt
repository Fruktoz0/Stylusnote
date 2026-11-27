package hu.bdev.stylusnote.data.export

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import hu.bdev.stylusnote.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object CloudSyncPolicy {
    const val WRITING_IDLE_DELAY_MS: Long = 180_000L // 3 minutes idle after stopping strokes
    const val DEBOUNCE_MARK_DIRTY_MS: Long = 3_000L
    const val PAGE_TRANSITION_DELAY_MS: Long = 2_000L
    const val NOTEBOOK_EXIT_DELAY_MS: Long = 1_500L
    const val PERIODIC_BATTERY_SAVER_MINUTES: Long = 30L
    const val PERIODIC_SMART_AUTO_FALLBACK_MINUTES: Long = 60L

    fun resolvePeriodicIntervalMinutes(settings: CloudSyncSettings): Long? {
        if (!settings.isConfigured) return null
        return when (settings.scheduleMode) {
            CloudSyncScheduleMode.SMART_AUTO -> PERIODIC_SMART_AUTO_FALLBACK_MINUTES
            CloudSyncScheduleMode.BATTERY_SAVER -> PERIODIC_BATTERY_SAVER_MINUTES
            CloudSyncScheduleMode.MANUAL -> null
        }
    }

    fun resolveIdleDelayMs(settings: CloudSyncSettings): Long? {
        if (!settings.isConfigured) return null
        return when (settings.scheduleMode) {
            CloudSyncScheduleMode.SMART_AUTO -> WRITING_IDLE_DELAY_MS
            CloudSyncScheduleMode.BATTERY_SAVER,
            CloudSyncScheduleMode.MANUAL -> null
        }
    }

    fun shouldSyncOnPageTransition(settings: CloudSyncSettings): Boolean =
        settings.isConfigured && settings.scheduleMode == CloudSyncScheduleMode.SMART_AUTO

    fun shouldSyncOnNotebookOrAppExit(settings: CloudSyncSettings): Boolean =
        settings.isConfigured && (
            settings.scheduleMode == CloudSyncScheduleMode.SMART_AUTO ||
                settings.scheduleMode == CloudSyncScheduleMode.BATTERY_SAVER
            )

    fun resolveBackgroundNetworkType(settings: CloudSyncSettings): NetworkType =
        if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
}

/**
 * Coordinates battery-friendly WorkManager cloud backup triggers without interfering with
 * 144 Hz active stylus writing:
 * - Debounces rapid stroke writes purely in memory (`DEBOUNCE_MARK_DIRTY_MS`).
 * - Schedules `3 min` writing-idle backup in [CloudSyncScheduleMode.SMART_AUTO].
 * - Flushes promptly on page transitions, notebook exit, and `MainActivity.onStop()`.
 * - Maintains periodic safety sync (`30 min` Battery Saver / `60 min` Smart Auto fallback).
 */
object CloudSyncScheduler {
    const val UNIQUE_AUTO_SYNC_WORK = "stylusnote_cloud_auto_sync"
    const val UNIQUE_PERIODIC_SYNC_WORK = "stylusnote_cloud_periodic_sync"
    const val UNIQUE_MANUAL_SYNC_WORK = "stylusnote_cloud_manual_sync"
    const val UNIQUE_CATEGORY_PDF_SYNC_WORK = "stylusnote_category_pdf_sync"

    internal const val KEY_SYNC_MODE = "sync_mode"
    internal const val KEY_TRIGGER_REASON = "trigger_reason"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()
    private val pendingInMemoryDirty = AtomicBoolean(false)
    private var dirtyDebounceJob: Job? = null

    /**
     * Called off the UI thread whenever [hu.bdev.stylusnote.data.local.StrokeJournal] commits a
     * WAL frame. Resets a lightweight 3 s memory timer so continuous sketching causes zero IPC/disk
     * churn, and marks pending + schedules the 3-minute idle WorkManager job once writing pauses.
     */
    fun notifyLocalMutation(
        context: Context,
        debounceMs: Long = CloudSyncPolicy.DEBOUNCE_MARK_DIRTY_MS
    ) {
        val appContext = context.applicationContext
        pendingInMemoryDirty.set(true)
        dirtyDebounceJob?.cancel()
        dirtyDebounceJob = scope.launch {
            delay(debounceMs)
            commitDirtyAndScheduleIdle(appContext)
        }
    }

    /**
     * Called when Room entities change from bookshelf/dialog actions (rename, reorder, new folder,
     * tombstone delete, cover change, new page).
     */
    fun notifyDatabaseMetadataChanged(context: Context) {
        val appContext = context.applicationContext
        pendingInMemoryDirty.set(true)
        scope.launch {
            commitDirtyAndScheduleIdle(appContext)
        }
    }

    /**
     * Called when navigating from one page to another inside an open notebook.
     */
    fun onPageTransition(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            scheduleMutex.withLock {
                val store = CloudSyncPreferencesStore(appContext)
                val settings = store.currentSettings()
                val hadNewDirty = flushInMemoryDirtyFlagLocked(store, settings)
                if ((hadNewDirty || settings.hasPendingLocalChanges) &&
                    CloudSyncPolicy.shouldSyncOnPageTransition(settings)
                ) {
                    enqueueOneTimeAutoSync(
                        context = appContext,
                        settings = settings,
                        delayMs = CloudSyncPolicy.PAGE_TRANSITION_DELAY_MS,
                        reason = "page_switch"
                    )
                }
            }
        }
    }

    /**
     * Called when leaving the canvas back to the bookshelf, updating the closed page's timestamp
     * if local stroke/object edits were committed since opening.
     */
    fun onNotebookExit(
        context: Context,
        database: AppDatabase? = null,
        editedPageId: String? = null
    ) {
        val appContext = context.applicationContext
        scope.launch {
            if (database != null && !editedPageId.isNullOrBlank()) {
                runCatching {
                    database.pageDao().touchPageTimestamp(editedPageId, System.currentTimeMillis())
                }
                pendingInMemoryDirty.set(true)
            }
            scheduleMutex.withLock {
                val store = CloudSyncPreferencesStore(appContext)
                val settings = store.currentSettings()
                val hadNewDirty = flushInMemoryDirtyFlagLocked(store, settings)
                if ((hadNewDirty || settings.hasPendingLocalChanges) &&
                    CloudSyncPolicy.shouldSyncOnNotebookOrAppExit(settings)
                ) {
                    enqueueOneTimeAutoSync(
                        context = appContext,
                        settings = settings,
                        delayMs = CloudSyncPolicy.NOTEBOOK_EXIT_DELAY_MS,
                        reason = "notebook_exit"
                    )
                }
            }
        }
    }

    /**
     * Called from `MainActivity.onStop()` when the user leaves StylusNote or locks the tablet.
     */
    fun onAppBackgrounded(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            scheduleMutex.withLock {
                val store = CloudSyncPreferencesStore(appContext)
                val settings = store.currentSettings()
                val hadNewDirty = flushInMemoryDirtyFlagLocked(store, settings)
                if ((hadNewDirty || settings.hasPendingLocalChanges) &&
                    CloudSyncPolicy.shouldSyncOnNotebookOrAppExit(settings)
                ) {
                    enqueueOneTimeAutoSync(
                        context = appContext,
                        settings = settings,
                        delayMs = 0L,
                        reason = "app_stop"
                    )
                }
                if (settings.isConfigured && settings.providerType == CloudProviderType.GOOGLE_DRIVE) {
                    enqueueCategoryPdfSync(appContext, settings)
                }
            }
        }
    }

    fun cancelCategoryPdfSync(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_CATEGORY_PDF_SYNC_WORK)
    }

    private fun enqueueCategoryPdfSync(
        context: Context,
        settings: CloudSyncSettings
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(CloudSyncPolicy.resolveBackgroundNetworkType(settings))
            .build()
        val request = OneTimeWorkRequestBuilder<CategoryPdfSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(4L, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_CATEGORY_PDF_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun triggerImmediateManualSync(
        context: Context,
        mode: SyncExecutionMode = SyncExecutionMode.BIDIRECTIONAL_MERGE
    ): UUID {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    KEY_SYNC_MODE to mode.name,
                    KEY_TRIGGER_REASON to if (mode == SyncExecutionMode.RESTORE_FROM_CLOUD) "restore" else "manual"
                )
            )
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_MANUAL_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    suspend fun syncPeriodicSchedule(context: Context) {
        val appContext = context.applicationContext
        val store = CloudSyncPreferencesStore(appContext)
        val settings = store.currentSettings()
        val intervalMinutes = CloudSyncPolicy.resolvePeriodicIntervalMinutes(settings)
        val wm = WorkManager.getInstance(appContext)
        if (intervalMinutes == null) {
            wm.cancelUniqueWork(UNIQUE_PERIODIC_SYNC_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(CloudSyncPolicy.resolveBackgroundNetworkType(settings))
            .build()
        val periodic = PeriodicWorkRequestBuilder<CloudSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    KEY_SYNC_MODE to SyncExecutionMode.BIDIRECTIONAL_MERGE.name,
                    KEY_TRIGGER_REASON to "periodic"
                )
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
    }

    suspend fun createRemoteClient(
        context: Context,
        settings: CloudSyncSettings,
        prefsStore: CloudSyncPreferencesStore
    ): CloudSyncRemoteClient {
        val appContext = context.applicationContext
        return when (settings.providerType) {
            CloudProviderType.GOOGLE_DRIVE -> GoogleDriveRestClient(
                accessTokenProvider = {
                    GoogleDriveAuthorizationHelper.requireSilentAccessToken(appContext)
                },
                initialFolderId = settings.googleDriveFolderId,
                onFolderIdResolved = { folderId ->
                    prefsStore.saveGoogleDriveFolderId(folderId)
                }
            )
            CloudProviderType.WEBDAV -> {
                val password = prefsStore.decryptWebDavPassword()
                if (password.isNullOrEmpty() || settings.webDavBaseUrl.isBlank() || settings.webDavUsername.isBlank()) {
                    throw CloudAuthRequiredException("WebDAV hitelesítési adatok hiányoznak vagy újra megadandók.")
                }
                WebDavRemoteClient(
                    baseUrl = settings.webDavBaseUrl,
                    username = settings.webDavUsername,
                    password = password
                )
            }
            CloudProviderType.NONE -> throw CloudAuthRequiredException("Nincs csatlakoztatott felhőszolgáltató.")
        }
    }

    suspend fun probeConfiguredRemoteBackup(context: Context): RemoteCloudSummary {
        val appContext = context.applicationContext
        val prefsStore = CloudSyncPreferencesStore(appContext)
        val settings = prefsStore.currentSettings()
        if (!settings.isConfigured) {
            throw IOException("Nincs beállított felhőkapcsolat.")
        }
        val remoteClient = createRemoteClient(appContext, settings, prefsStore)
        val database = AppDatabase.getInstance(appContext)
        return CloudSyncEngine(appContext.filesDir, database).probeRemoteBackup(remoteClient)
    }

    private suspend fun commitDirtyAndScheduleIdle(appContext: Context) {
        scheduleMutex.withLock {
            val store = CloudSyncPreferencesStore(appContext)
            val settings = store.currentSettings()
            val flushed = flushInMemoryDirtyFlagLocked(store, settings)
            val idleDelayMs = CloudSyncPolicy.resolveIdleDelayMs(settings)
            if ((flushed || settings.hasPendingLocalChanges) && idleDelayMs != null) {
                enqueueOneTimeAutoSync(
                    context = appContext,
                    settings = settings,
                    delayMs = idleDelayMs,
                    reason = "idle"
                )
            }
        }
    }

    private suspend fun flushInMemoryDirtyFlagLocked(
        store: CloudSyncPreferencesStore,
        settings: CloudSyncSettings
    ): Boolean {
        val wasDirty = pendingInMemoryDirty.getAndSet(false)
        if (!wasDirty || !settings.isConfigured) return false
        if (!settings.hasPendingLocalChanges) {
            store.markPendingLocalChanges(true)
        }
        return true
    }

    private fun enqueueOneTimeAutoSync(
        context: Context,
        settings: CloudSyncSettings,
        delayMs: Long,
        reason: String
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(CloudSyncPolicy.resolveBackgroundNetworkType(settings))
            .build()
        val builder = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    KEY_SYNC_MODE to SyncExecutionMode.BIDIRECTIONAL_MERGE.name,
                    KEY_TRIGGER_REASON to reason
                )
            )
        if (delayMs > 0L) {
            builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_AUTO_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )
    }
}
