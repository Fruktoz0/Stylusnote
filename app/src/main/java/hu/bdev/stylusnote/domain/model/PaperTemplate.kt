package hu.bdev.stylusnote.domain.model

import hu.bdev.stylusnote.core.math.PageDimensionsPt

/**
 * Canvas boundary mode selectable per notebook or page (Section 8).
 */
enum class CanvasMode(val displayName: String) {
    FIXED_PAGE("Fix oldal"),
    INFINITE_CANVAS("Végtelen vászon")
}

/**
 * Vector paper templates drawn zoom-synchronously in PDF-point document space (Section 8).
 */
enum class PaperTemplateType(val displayName: String) {
    BLANK("Üres"),
    DOTTED_5MM("Pontozott (5 mm)"),
    GRID_SIMPLE_5MM("Sima négyzethálós"),
    GRID_5MM("Négyzethálós (5 mm)"),
    RULED_8MM("Vonalas (8 mm)"),
    CORNELL("Cornell"),
    MUSIC_STAFF("Kottapapír")
}

/**
 * Page geometry and background template configuration for Layer 1 ([PaperBackgroundRenderer]).
 */
data class PagePaperConfig(
    val canvasMode: CanvasMode = CanvasMode.FIXED_PAGE,
    val pageDimensions: PageDimensionsPt = PageDimensionsPt.A4_PORTRAIT,
    val templateType: PaperTemplateType = PaperTemplateType.GRID_5MM
)

