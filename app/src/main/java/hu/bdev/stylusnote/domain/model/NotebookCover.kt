package hu.bdev.stylusnote.domain.model

import hu.bdev.stylusnote.core.math.PageDimensionsPt

/**
 * Surface textures available in the Cover Designer Studio (Section 8).
 */
enum class CoverTexture(val displayName: String) {
    LEATHER("Bőr"),
    CANVAS("Vászon"),
    MATTE_MINIMAL("Matt minimalista"),
    BLUEPRINT("Műszaki tervrajz")
}

/**
 * Title and badge placement styles on a notebook cover.
 */
enum class CoverLabelLayout(val displayName: String) {
    CLASSIC_BAND("Klasszikus szalag"),
    CENTER_PLAQUE("Középső címke"),
    BOTTOM_CORNER("Alsó sarok"),
    MINIMAL_HEADER("Minimalista fejléc")
}

/**
 * Font family categories configurable per notebook cover.
 */
enum class CoverTypography(val displayName: String) {
    SANS_SERIF("Sans-serif"),
    SERIF("Serif"),
    MONOSPACE("Monospace")
}

/**
 * Notebook cover styling embedded in Room `NotebookEntity` and rendered in the bookshelf and
 * Cover Studio (Section 8).
 */
data class NotebookCover(
    val texture: CoverTexture = CoverTexture.MATTE_MINIMAL,
    val primaryColorArgb: Int = DEFAULT_PRIMARY_COLOR_ARGB,
    val spineColorArgb: Int = DEFAULT_SPINE_COLOR_ARGB,
    val labelLayout: CoverLabelLayout = CoverLabelLayout.CLASSIC_BAND,
    val typography: CoverTypography = CoverTypography.SANS_SERIF,
    val customImageRelativePath: String? = null,
    val subtitle: String = ""
) {
    companion object {
        const val DEFAULT_PRIMARY_COLOR_ARGB: Int = 0xFF1F4E79.toInt()
        const val DEFAULT_SPINE_COLOR_ARGB: Int = 0xFF133352.toInt()
    }
}

/**
 * Persisted fixed-page size presets mapped cleanly onto PDF-point [PageDimensionsPt].
 */
enum class PageSizePreset(
    val displayName: String,
    val dimensionsPt: PageDimensionsPt
) {
    A4_PORTRAIT("A4 álló", PageDimensionsPt.A4_PORTRAIT),
    B5_ISO_PORTRAIT("B5 álló", PageDimensionsPt.B5_ISO_PORTRAIT),
    LANDSCAPE_16_10("16:10 fekvő", PageDimensionsPt.LANDSCAPE_16_10);

    fun toPagePaperConfig(
        canvasMode: CanvasMode = CanvasMode.FIXED_PAGE,
        templateType: PaperTemplateType = PaperTemplateType.GRID_5MM
    ): PagePaperConfig = PagePaperConfig(
        canvasMode = canvasMode,
        pageDimensions = dimensionsPt,
        templateType = templateType
    )
}

/**
 * Notebook-level offline Digital Ink OCR language selector (Section 7).
 */
enum class OcrLanguageTag(
    val bcp47Tag: String,
    val displayName: String
) {
    HUNGARIAN("hu", "Magyar"),
    ENGLISH_US("en-US", "Angol (US)")
}

