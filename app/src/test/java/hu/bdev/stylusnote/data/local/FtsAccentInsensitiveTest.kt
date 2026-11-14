package hu.bdev.stylusnote.data.local

import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.CoverLabelLayout
import hu.bdev.stylusnote.domain.model.CoverTexture
import hu.bdev.stylusnote.domain.model.CoverTypography
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import org.junit.Assert.assertEquals
import org.junit.Test

class FtsAccentInsensitiveTest {

    @Test
    fun accentNormalizerFoldsHungarianDiacriticsAndBuildsSafePrefixQueries() {
        val folded = AccentNormalizer.foldToAscii("Árvíztűrő tükörfúrógép ŐRÜLT ŰRHÁJÓ")
        assertEquals("arvizturo tukorfurogep orult urhajo", folded)

        val ftsQueryAccented = AccentNormalizer.buildFts4MatchQuery("Tükörfúró görbe")
        assertEquals("tukorfuro* gorbe*", ftsQueryAccented)

        val ftsQueryUnaccented = AccentNormalizer.buildFts4MatchQuery("tukorfuro gorbe")
        assertEquals(ftsQueryAccented, ftsQueryUnaccented)

        val sanitized = AccentNormalizer.buildFts4MatchQuery("\"integrál*: (főoldal)\"")
        assertEquals("integral* fooldal*", sanitized)

        assertEquals("", AccentNormalizer.buildFts4MatchQuery("   *:\"'   "))
    }

    @Test
    fun typeConvertersRoundTripAllDatabaseEnumsCleanly() {
        val converters = StylusNoteDatabaseConverters()

        for (mode in CanvasMode.entries) {
            assertEquals(mode, converters.toCanvasMode(converters.fromCanvasMode(mode)))
        }
        for (preset in PageSizePreset.entries) {
            assertEquals(preset, converters.toPageSizePreset(converters.fromPageSizePreset(preset)))
        }
        for (tpl in PaperTemplateType.entries) {
            assertEquals(tpl, converters.toPaperTemplateType(converters.fromPaperTemplateType(tpl)))
        }
        for (tex in CoverTexture.entries) {
            assertEquals(tex, converters.toCoverTexture(converters.fromCoverTexture(tex)))
        }
        for (layout in CoverLabelLayout.entries) {
            assertEquals(layout, converters.toCoverLabelLayout(converters.fromCoverLabelLayout(layout)))
        }
        for (typo in CoverTypography.entries) {
            assertEquals(typo, converters.toCoverTypography(converters.fromCoverTypography(typo)))
        }
        for (lang in OcrLanguageTag.entries) {
            assertEquals(lang, converters.toOcrLanguageTag(converters.fromOcrLanguageTag(lang)))
        }
    }

    @Test
    fun pageSizePresetAndPageEntityPathsFollowSection9Layout() {
        val cfg = PageSizePreset.B5_ISO_PORTRAIT.toPagePaperConfig(
            canvasMode = CanvasMode.INFINITE_CANVAS,
            templateType = PaperTemplateType.CORNELL
        )
        assertEquals(CanvasMode.INFINITE_CANVAS, cfg.canvasMode)
        assertEquals(PageDimensionsPt.B5_ISO_PORTRAIT, cfg.pageDimensions)
        assertEquals(PaperTemplateType.CORNELL, cfg.templateType)

        val page = PageEntity(
            id = "pg-42",
            notebookId = "nb-1",
            pageIndexInNotebook = 0,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L
        )
        assertEquals("pages/pg-42/page.snote", page.snapshotRelativePath)
        assertEquals("pages/pg-42/page.journal", page.journalRelativePath)
    }
}

