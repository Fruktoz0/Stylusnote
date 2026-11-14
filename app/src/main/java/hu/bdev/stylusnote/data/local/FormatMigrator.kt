package hu.bdev.stylusnote.data.local

import hu.bdev.stylusnote.domain.model.BrushToolType

/**
 * Thrown when code attempts to overwrite or compact a page container saved by a newer
 * `formatVersion` that this reader must treat strictly as read-only (Section 9.4).
 */
class ReadOnlyPageFormatException(
    val fileFormatVersion: Int,
    val supportedVersion: Int = PageFileFormat.CURRENT_FORMAT_VERSION
) : IllegalStateException(
    "Az oldal újabb formátumverzióval készült (v$fileFormatVersion > v$supportedVersion), " +
        "ezért csak olvasható módban nyitható meg."
)

/**
 * Outcome of inspecting an existing `SNOT` header against the current reader version.
 */
sealed interface FormatInspectionResult {
    data object UpToDate : FormatInspectionResult
    data class NeedsMigration(
        val fromVersion: Int,
        val toVersion: Int
    ) : FormatInspectionResult

    data class ReadOnlyHigherVersion(
        val fileVersion: Int,
        val minReaderVersion: Int,
        val supportedVersion: Int
    ) : FormatInspectionResult
}

/**
 * Version guard and forward/backward compatibility coordinator for `.snote` and `.journal` files
 * (Section 9.4).
 */
object FormatMigrator {

    fun inspectHeader(
        header: SnotFileHeader,
        supportedVersion: Int = PageFileFormat.CURRENT_FORMAT_VERSION
    ): FormatInspectionResult = when {
        header.formatVersion > supportedVersion || header.minReaderVersion > supportedVersion ->
            FormatInspectionResult.ReadOnlyHigherVersion(
                fileVersion = header.formatVersion,
                minReaderVersion = header.minReaderVersion,
                supportedVersion = supportedVersion
            )

        header.formatVersion < supportedVersion ->
            FormatInspectionResult.NeedsMigration(
                fromVersion = header.formatVersion,
                toVersion = supportedVersion
            )

        else -> FormatInspectionResult.UpToDate
    }

    /**
     * Runs chained `v -> v + 1` transformations over decoded [SnotFrame] payloads so older
     * snapshots can always upgrade cleanly to [targetVersion].
     */
    fun migrateFramesIfNeeded(
        header: SnotFileHeader,
        frames: List<SnotFrame>,
        targetVersion: Int = PageFileFormat.CURRENT_FORMAT_VERSION
    ): List<SnotFrame> {
        val inspection = inspectHeader(header, supportedVersion = targetVersion)
        if (inspection is FormatInspectionResult.ReadOnlyHigherVersion) {
            // Higher versions with valid backward-readable records pass through without mutation.
            return frames
        }

        var currentVersion = header.formatVersion
        var migrated = frames
        while (currentVersion < targetVersion) {
            migrated = migrateStep(currentVersion, migrated)
            currentVersion++
        }
        return migrated
    }

    private fun migrateStep(fromVersion: Int, frames: List<SnotFrame>): List<SnotFrame> =
        when (fromVersion) {
            // Schema v1 is the baseline; future format bumps register step-by-step upgraders here.
            else -> frames
        }

    /**
     * Maps stored brush tool codes back to [BrushToolType], falling back to the closest stock
     * ink brush ([BrushToolType.FOUNTAIN_PEN]) and flagging `usedFallback = true` when a newer
     * release's brush ID is encountered (Section 9.4).
     */
    fun resolveBrushToolWithFallback(rawToolCode: Int): Pair<BrushToolType, Boolean> {
        val matched = when (rawToolCode) {
            0 -> BrushToolType.FOUNTAIN_PEN
            1 -> BrushToolType.BALLPOINT_PEN
            2 -> BrushToolType.PENCIL
            3 -> BrushToolType.HIGHLIGHTER
            4 -> null // Legacy laser pointer retired in Phase II; fall back cleanly
            5 -> BrushToolType.VECTOR_ERASER
            6 -> BrushToolType.PRECISION_ERASER
            7 -> BrushToolType.LASSO
            else -> null
        }
        return if (matched != null) {
            matched to false
        } else {
            BrushToolType.FOUNTAIN_PEN to true
        }
    }

    fun toStableToolCode(toolType: BrushToolType): Int = when (toolType) {
        BrushToolType.FOUNTAIN_PEN -> 0
        BrushToolType.BALLPOINT_PEN -> 1
        BrushToolType.PENCIL -> 2
        BrushToolType.HIGHLIGHTER -> 3
        BrushToolType.VECTOR_ERASER -> 5
        BrushToolType.PRECISION_ERASER -> 6
        BrushToolType.LASSO -> 7
    }
}

