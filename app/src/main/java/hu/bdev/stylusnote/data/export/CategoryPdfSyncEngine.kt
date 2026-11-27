package hu.bdev.stylusnote.data.export

import android.content.Context
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.CategoryPdfSyncRecordEntity
import hu.bdev.stylusnote.ui.shelf.exportNotebookPagesToPdfStream
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CategoryPdfSyncReport(
    val exportedPdfCount: Int,
    val trashedFolderCount: Int
)

/**
 * Exports readable, date-stamped PDFs (`<Title>_YYYY-MM-DD.pdf`) for notebooks inside categories
 * that have a linked Google Drive folder, overwriting & renaming existing Drive files cleanly so
 * no duplicate PDFs pile up, and drains any queued category Drive folder deletions.
 */
object CategoryPdfSyncEngine {

    suspend fun syncNow(
        context: Context,
        database: AppDatabase,
        driveFolderClient: GoogleDriveFolderClient,
        nowEpochMs: Long = System.currentTimeMillis()
    ): CategoryPdfSyncReport {
        var trashedCount = 0
        val pendingDeletions = database.pendingDriveFolderDeletionDao().getAll()
        for (pending in pendingDeletions) {
            driveFolderClient.trashFolder(pending.driveFolderId)
            database.pendingDriveFolderDeletionDao().deleteById(pending.driveFolderId)
            trashedCount++
        }

        val linkedFolders = database.folderDao().getFoldersWithDrivePdfMapping()
        var exportedCount = 0
        for (folder in linkedFolders) {
            val targetDriveFolderId = folder.drivePdfFolderId?.takeIf { it.isNotBlank() } ?: continue
            val notebooks = database.notebookDao().observeNotebooksInFolder(folder.id).first()
            for (notebook in notebooks) {
                val pages = database.pageDao().getPagesForNotebook(notebook.id)
                if (pages.isEmpty()) continue
                val latestContentMs = maxOf(
                    notebook.updatedAtEpochMs,
                    pages.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
                )
                val existing = database.categoryPdfSyncDao().getByNotebookId(notebook.id)
                val needsExport = existing == null ||
                    existing.driveFolderId != targetDriveFolderId ||
                    latestContentMs > existing.lastExportedUpdatedAtMs
                if (!needsExport) continue

                val pdfBytes = ByteArrayOutputStream().use { baos ->
                    exportNotebookPagesToPdfStream(
                        context = context,
                        database = database,
                        notebook = notebook,
                        pagesToExport = pages,
                        outputStream = baos
                    )
                    baos.toByteArray()
                }
                val fileName = buildDatedPdfFileName(notebook.title, latestContentMs.coerceAtLeast(nowEpochMs))
                val reusableFileId = existing
                    ?.takeIf { it.driveFolderId == targetDriveFolderId }
                    ?.remoteDriveFileId
                val uploadedId = driveFolderClient.uploadOrReplacePdfInFolder(
                    folderId = targetDriveFolderId,
                    existingFileId = reusableFileId,
                    fileName = fileName,
                    pdfBytes = pdfBytes
                )
                database.categoryPdfSyncDao().upsert(
                    CategoryPdfSyncRecordEntity(
                        notebookId = notebook.id,
                        folderId = folder.id,
                        driveFolderId = targetDriveFolderId,
                        remoteDriveFileId = uploadedId,
                        remoteFileName = fileName,
                        lastExportedUpdatedAtMs = latestContentMs,
                        lastSyncedEpochMs = nowEpochMs
                    )
                )
                exportedCount++
            }
        }
        return CategoryPdfSyncReport(
            exportedPdfCount = exportedCount,
            trashedFolderCount = trashedCount
        )
    }

    /**
     * Formats `<sanitizedTitle>_YYYY-MM-DD.pdf` using only filesystem- and Drive-safe characters.
     */
    fun buildDatedPdfFileName(notebookTitle: String, epochMs: Long): String {
        val cleaned = notebookTitle
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(64)
            .ifEmpty { "Jegyzet" }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))
        return "${cleaned}_${dateStr}.pdf"
    }
}
