package hu.bdev.stylusnote.data.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import hu.bdev.stylusnote.data.local.AppDatabase
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Background [CoroutineWorker] running [CloudSyncEngine.syncNow] against the configured cloud
 * provider and updating [CloudSyncStatusBus] / [CloudSyncPreferencesStore] for live UI status.
 */
class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefsStore = CloudSyncPreferencesStore(applicationContext)
        val settings = prefsStore.currentSettings()
        if (!settings.isConfigured) {
            return Result.success()
        }

        val modeName = inputData.getString(CloudSyncScheduler.KEY_SYNC_MODE)
        val mode = SyncExecutionMode.entries.firstOrNull { it.name == modeName }
            ?: SyncExecutionMode.BIDIRECTIONAL_MERGE

        CloudSyncStatusBus.setSyncing(true)
        return try {
            val remoteClient = CloudSyncScheduler.createRemoteClient(
                context = applicationContext,
                settings = settings,
                prefsStore = prefsStore
            )
            val database = AppDatabase.getInstance(applicationContext)
            val report = CloudSyncEngine(applicationContext.filesDir, database).syncNow(
                remoteClient = remoteClient,
                mode = mode
            )
            prefsStore.recordSyncSucceeded(report.syncedAtEpochMs)
            if (settings.providerType == CloudProviderType.GOOGLE_DRIVE &&
                inputData.getString(CloudSyncScheduler.KEY_TRIGGER_REASON) == "periodic"
            ) {
                runCatching {
                    val folderClient = GoogleDriveFolderClient(
                        accessTokenProvider = {
                            GoogleDriveAuthorizationHelper.requireSilentAccessToken(applicationContext)
                        }
                    )
                    CategoryPdfSyncEngine.syncNow(
                        context = applicationContext,
                        database = database,
                        driveFolderClient = folderClient
                    )
                }
            }
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (authError: CloudAuthRequiredException) {
            prefsStore.recordSyncError(
                message = authError.message ?: "Újrahitelesítés szükséges a felhőkapcsolathoz.",
                needsReauth = true
            )
            Result.failure()
        } catch (ioError: IOException) {
            val msg = ioError.message ?: "Hálózati vagy felhőkommunikációs hiba."
            if (runAttemptCount < MAX_TRANSIENT_RETRIES) {
                Result.retry()
            } else {
                prefsStore.recordSyncError(message = msg, needsReauth = false)
                Result.failure()
            }
        } catch (t: Throwable) {
            prefsStore.recordSyncError(
                message = t.message ?: "Ismeretlen szinkronizálási hiba.",
                needsReauth = false
            )
            Result.failure()
        } finally {
            CloudSyncStatusBus.setSyncing(false)
        }
    }

    private companion object {
        private const val MAX_TRANSIENT_RETRIES = 3
    }
}
