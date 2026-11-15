package hu.bdev.stylusnote.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.CoverLabelLayout
import hu.bdev.stylusnote.domain.model.CoverTexture
import hu.bdev.stylusnote.domain.model.CoverTypography
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer
import java.util.Locale

/**
 * Accent folding and safe SQLite FTS4 query builder for Hungarian (`áéíóöőúüű`) and Latin text.
 *
 * SQLite's built-in `unicode61` (`remove_diacritics=1`) is paired with an explicitly NFD-folded
 * [PageTextFtsEntity.asciiFoldedText] column so searches for `tukorfuro` match `tükörfúrógép`
 * reliably across Android 10+ devices, including Hungarian double-acute `ő`/`ű`.
 */
object AccentNormalizer {
    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_WHITESPACE_REGEX = Regex("[^\\p{L}\\p{Nd}\\s]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun foldToAscii(input: String): String {
        if (input.isBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val stripped = COMBINING_MARKS_REGEX.replace(decomposed, "")
        return stripped.lowercase(Locale.ROOT).trim()
    }

    /**
     * Converts free-form user input into a sanitized FTS4 prefix `MATCH` expression, or returns an
     * empty string if no searchable token remains.
     */
    fun buildFts4MatchQuery(rawUserQuery: String): String {
        val folded = foldToAscii(rawUserQuery)
        val sanitized = NON_ALNUM_WHITESPACE_REGEX.replace(folded, " ").trim()
        if (sanitized.isEmpty()) return ""
        return sanitized
            .split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ") { token -> "$token*" }
    }
}

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId")]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val accentColorArgb: Int = 0xFF3F51B5.toInt(),
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "notebooks",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId"), Index("updatedAtEpochMs")]
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val folderId: String? = null,
    val title: String,
    @Embedded(prefix = "cover_")
    val cover: NotebookCover = NotebookCover(),
    val defaultCanvasMode: CanvasMode = CanvasMode.FIXED_PAGE,
    val defaultPageSizePreset: PageSizePreset = PageSizePreset.A4_PORTRAIT,
    val defaultTemplateType: PaperTemplateType = PaperTemplateType.GRID_5MM,
    val ocrLanguage: OcrLanguageTag = OcrLanguageTag.HUNGARIAN,
    val isFavorite: Boolean = false,
    val lastOpenedPageId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId")]
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val title: String,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long
)

/**
 * Page metadata row linking hierarchical organization in Room with per-page binary stroke files
 * (`pages/<pageId>/page.snote` and `page.journal` — strokes themselves never enter SQLite).
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("notebookId"), Index("chapterId")]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val chapterId: String? = null,
    val pageIndexInNotebook: Int,
    val canvasMode: CanvasMode = CanvasMode.FIXED_PAGE,
    val pageSizePreset: PageSizePreset = PageSizePreset.A4_PORTRAIT,
    val templateType: PaperTemplateType = PaperTemplateType.GRID_5MM,
    val pdfAssetId: String? = null,
    val pdfPageIndex: Int? = null,
    val invertPdfInOledMode: Boolean = true,
    val snapshotRelativePath: String = defaultSnapshotPath(id),
    val journalRelativePath: String = defaultJournalPath(id),
    val needsOcrSync: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    companion object {
        fun defaultSnapshotPath(pageId: String): String =
            "pages/$pageId/${StrokeJournal.SNAPSHOT_FILE_NAME}"

        fun defaultJournalPath(pageId: String): String =
            "pages/$pageId/${StrokeJournal.JOURNAL_FILE_NAME}"
    }
}

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int
)

@Entity(
    tableName = "notebook_tag_cross_ref",
    primaryKeys = ["notebookId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId"), Index("tagId")]
)
data class NotebookTagCrossRef(
    val notebookId: String,
    val tagId: String
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId"), Index("pageId")]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val pageId: String,
    val label: String,
    val colorArgb: Int = 0xFFFF7043.toInt(),
    val createdAtEpochMs: Long
)

/**
 * Full-text OCR search table using SQLite `@Fts4` (`unicode61`) per Section 2 & 7.
 *
 * `remove_diacritics=1` preserves compatibility down to API 29 (`minSdk = 29`) while
 * [asciiFoldedText] guarantees accent-insensitive matching for Hungarian `ő`/`ű` as well.
 */
@Entity(tableName = "page_text_fts")
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"]
)
data class PageTextFtsEntity(
    val pageId: String,
    val notebookId: String,
    val rawText: String,
    val asciiFoldedText: String
)

data class PageSearchHit(
    val pageId: String,
    val notebookId: String,
    val rawText: String
)

class StylusNoteDatabaseConverters {
    @TypeConverter
    fun fromCanvasMode(value: CanvasMode): String = value.name

    @TypeConverter
    fun toCanvasMode(name: String): CanvasMode =
        CanvasMode.entries.firstOrNull { it.name == name } ?: CanvasMode.FIXED_PAGE

    @TypeConverter
    fun fromPageSizePreset(value: PageSizePreset): String = value.name

    @TypeConverter
    fun toPageSizePreset(name: String): PageSizePreset =
        PageSizePreset.entries.firstOrNull { it.name == name } ?: PageSizePreset.A4_PORTRAIT

    @TypeConverter
    fun fromPaperTemplateType(value: PaperTemplateType): String = value.name

    @TypeConverter
    fun toPaperTemplateType(name: String): PaperTemplateType =
        PaperTemplateType.entries.firstOrNull { it.name == name } ?: PaperTemplateType.GRID_5MM

    @TypeConverter
    fun fromCoverTexture(value: CoverTexture): String = value.name

    @TypeConverter
    fun toCoverTexture(name: String): CoverTexture =
        CoverTexture.entries.firstOrNull { it.name == name } ?: CoverTexture.MATTE_MINIMAL

    @TypeConverter
    fun fromCoverLabelLayout(value: CoverLabelLayout): String = value.name

    @TypeConverter
    fun toCoverLabelLayout(name: String): CoverLabelLayout =
        CoverLabelLayout.entries.firstOrNull { it.name == name } ?: CoverLabelLayout.CLASSIC_BAND

    @TypeConverter
    fun fromCoverTypography(value: CoverTypography): String = value.name

    @TypeConverter
    fun toCoverTypography(name: String): CoverTypography =
        CoverTypography.entries.firstOrNull { it.name == name } ?: CoverTypography.SANS_SERIF

    @TypeConverter
    fun fromOcrLanguageTag(value: OcrLanguageTag): String = value.name

    @TypeConverter
    fun toOcrLanguageTag(name: String): OcrLanguageTag =
        OcrLanguageTag.entries.firstOrNull { it.name == name } ?: OcrLanguageTag.HUNGARIAN
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder ASC, name ASC")
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, name ASC")
    fun observeAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :folderId LIMIT 1")
    suspend fun getFolderById(folderId: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolderById(folderId: String)
}

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks WHERE folderId IS :folderId ORDER BY updatedAtEpochMs DESC")
    fun observeNotebooksInFolder(folderId: String?): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks ORDER BY updatedAtEpochMs DESC")
    fun observeAllNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :notebookId LIMIT 1")
    suspend fun getNotebookById(notebookId: String): NotebookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotebook(notebook: NotebookEntity)

    @Update
    suspend fun updateNotebook(notebook: NotebookEntity)

    @Query("DELETE FROM notebooks WHERE id = :notebookId")
    suspend fun deleteNotebookById(notebookId: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE notebookId = :notebookId ORDER BY sortOrder ASC")
    fun observeChapters(notebookId: String): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(chapter: ChapterEntity)

    @Query("DELETE FROM chapters WHERE id = :chapterId")
    suspend fun deleteChapterById(chapterId: String)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageIndexInNotebook ASC")
    fun observePagesForNotebook(notebookId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageIndexInNotebook ASC")
    suspend fun getPagesForNotebook(notebookId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageById(pageId: String): PageEntity?

    @Query("SELECT * FROM pages WHERE needsOcrSync = 1 ORDER BY updatedAtEpochMs ASC")
    suspend fun getPagesNeedingOcr(): List<PageEntity>

    @Query("SELECT DISTINCT notebookId FROM pages WHERE pdfAssetId IS NOT NULL")
    fun observePdfNotebookIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: PageEntity)

    @Transaction
    suspend fun reorderPagesInNotebook(
        notebookId: String,
        orderedPageIds: List<String>,
        updatedAtMs: Long
    ) {
        val currentPages = getPagesForNotebook(notebookId).associateBy { it.id }
        var nextIndex = 0
        val visited = HashSet<String>(orderedPageIds.size)
        for (pageId in orderedPageIds) {
            val page = currentPages[pageId] ?: continue
            if (visited.add(pageId)) {
                upsertPage(page.copy(pageIndexInNotebook = nextIndex, updatedAtEpochMs = updatedAtMs))
                nextIndex++
            }
        }
        for ((pageId, page) in currentPages) {
            if (!visited.contains(pageId)) {
                upsertPage(page.copy(pageIndexInNotebook = nextIndex, updatedAtEpochMs = updatedAtMs))
                nextIndex++
            }
        }
    }

    @Query("UPDATE pages SET needsOcrSync = :needsSync, updatedAtEpochMs = :updatedAtMs WHERE id = :pageId")
    suspend fun markOcrSyncState(pageId: String, needsSync: Boolean, updatedAtMs: Long)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePageById(pageId: String)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query(
        "SELECT tags.* FROM tags " +
            "INNER JOIN notebook_tag_cross_ref ON tags.id = notebook_tag_cross_ref.tagId " +
            "WHERE notebook_tag_cross_ref.notebookId = :notebookId " +
            "ORDER BY tags.name ASC"
    )
    fun observeTagsForNotebook(notebookId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM notebook_tag_cross_ref")
    fun observeAllNotebookTagLinks(): Flow<List<NotebookTagCrossRef>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachTagToNotebook(crossRef: NotebookTagCrossRef)

    @Query("DELETE FROM notebook_tag_cross_ref WHERE notebookId = :notebookId AND tagId = :tagId")
    suspend fun detachTagFromNotebook(notebookId: String, tagId: String)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTagById(tagId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE notebookId = :notebookId ORDER BY createdAtEpochMs DESC")
    fun observeBookmarksForNotebook(notebookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAtEpochMs DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmarkById(bookmarkId: String)
}

@Dao
interface PageTextSearchDao {
    @Insert
    suspend fun insertFtsRow(entry: PageTextFtsEntity)

    @Query("DELETE FROM page_text_fts WHERE pageId = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Transaction
    suspend fun replacePageOcrText(
        pageId: String,
        notebookId: String,
        recognizedText: String
    ) {
        deleteByPageId(pageId)
        val trimmed = recognizedText.trim()
        if (trimmed.isNotEmpty()) {
            insertFtsRow(
                PageTextFtsEntity(
                    pageId = pageId,
                    notebookId = notebookId,
                    rawText = trimmed,
                    asciiFoldedText = AccentNormalizer.foldToAscii(trimmed)
                )
            )
        }
    }

    @Query(
        "SELECT pageId, notebookId, rawText FROM page_text_fts " +
            "WHERE page_text_fts MATCH :ftsMatchQuery"
    )
    suspend fun searchRawMatch(ftsMatchQuery: String): List<PageSearchHit>

    @Query("SELECT rawText FROM page_text_fts WHERE notebookId = :notebookId")
    suspend fun getRecognizedTextsForNotebook(notebookId: String): List<String>

    suspend fun searchAccentInsensitive(userQuery: String): List<PageSearchHit> {
        val matchExpr = AccentNormalizer.buildFts4MatchQuery(userQuery)
        if (matchExpr.isEmpty()) return emptyList()
        return searchRawMatch(matchExpr)
    }
}

/**
 * Primary Room database for StylusNote hierarchy, metadata, covers, tags, bookmarks, and FTS4
 * OCR search (Section 2 & 8). Explicit migrations only (`fallbackToDestructiveMigration` forbidden).
 */
@Database(
    entities = [
        FolderEntity::class,
        NotebookEntity::class,
        ChapterEntity::class,
        PageEntity::class,
        TagEntity::class,
        NotebookTagCrossRef::class,
        BookmarkEntity::class,
        PageTextFtsEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(StylusNoteDatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun pageDao(): PageDao
    abstract fun tagDao(): TagDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun pageTextSearchDao(): PageTextSearchDao

    companion object {
        const val DATABASE_NAME: String = "stylusnote.db"

        /**
         * Explicit forward migration registry (`v1 -> v2`, etc.). Destructive fallback is never enabled.
         */
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

