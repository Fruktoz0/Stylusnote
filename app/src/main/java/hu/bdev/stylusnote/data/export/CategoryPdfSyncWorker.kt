package hu.bdev.stylusnote.data.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import hu.bdev.stylusnote.data.local.AppDatabase
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Low-overhead background [CoroutineWorker] that exports readable PDFs for categories mapped to
 * a Google Drive folder (`AppDatabase.folderDao().getFoldersWithDrivePdfMapping()`) and finishes any
 * queued Drive folder deletions without running during foreground pen input.
 */
class CategoryPdfSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefsStore = CloudSyncPreferencesStore(applicationContext)
        val settings = prefsStore.currentSettings()
        if (!settings.isConfigured || settings.providerType != CloudProviderType.GOOGLE_DRIVE) {
            return Result.success()
        }

        val database = AppDatabase.getInstance(applicationContext)
        val hasMappedFolders = database.folderDao().getFoldersWithDrivePdfMapping().isNotEmpty()
        val hasPendingDeletions = database.pendingDriveFolderDeletionDao().getAll().isNotEmpty()
        if (!hasMappedFolders && !hasPendingDeletions) {
            return Result.success()
        }

        return try {
            val driveFolderClient = GoogleDriveFolderClient(
                accessTokenProvider = {
                    GoogleDriveAuthorizationHelper.requireSilentAccessToken(applicationContext)
                }
            )
            CategoryPdfSyncEngine.syncNow(
                context = applicationContext,
                database = database,
                driveFolderClient = driveFolderClient
            )
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: CloudAuthRequiredException) {
            Result.failure()
        } catch (_: IOException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}
