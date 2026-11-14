package hu.bdev.stylusnote.ui.shelf

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.CoverLabelLayout
import hu.bdev.stylusnote.domain.model.CoverTexture
import hu.bdev.stylusnote.domain.model.CoverTypography
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import hu.bdev.stylusnote.ui.theme.StylusIcons
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Reusable modal backdrop container enforcing UX Rule 11: closes cleanly on both **ESC key**
 * and **backdrop click** while consuming clicks inside the dialog surface.
 */
@Composable
internal fun ModalBackdropBox(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * StarNote / Moleskine-inspired book cover card rendered across the Bookshelf grid and live inside
 * [CoverDesignerDialog], complete with left spine crease, right vertical elastic closing strap,
 * textures, and lower belly-band with four-point star emblem (IMG_2726 / IMG_2727).
 */
@Composable
fun NotebookCoverPreviewCard(
    title: String,
    cover: NotebookCover,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val customBitmap: ImageBitmap? = remember(cover.customImageRelativePath) {
        val relPath = cover.customImageRelativePath ?: return@remember null
        val file = File(context.filesDir, relPath)
        if (!file.exists()) return@remember null
        try {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    val primaryColor = Color(cover.primaryColorArgb)
    val spineColor = Color(cover.spineColorArgb)
    val onCoverColor = if (primaryColor.luminance() > 0.45f) Color(0xFF1E232A) else Color(0xFFF7F8FA)
    val fontFamily = when (cover.typography) {
        CoverTypography.SANS_SERIF -> FontFamily.SansSerif
        CoverTypography.SERIF -> FontFamily.Serif
        CoverTypography.MONOSPACE -> FontFamily.Monospace
    }

    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)

    Box(
        modifier = modifier
            .aspectRatio(0.72f)
            .shadow(elevation = 4.dp, shape = shape, clip = false)
            .clip(shape)
            .background(primaryColor)
            .border(0.8.dp, Color.Black.copy(alpha = 0.14f), shape)
    ) {
        if (customBitmap != null) {
            Image(
                bitmap = customBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f))
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                when (cover.texture) {
                    CoverTexture.LEATHER -> {
                        val inset = 10.dp.toPx()
                        drawRoundRect(
                            color = onCoverColor.copy(alpha = 0.24f),
                            topLeft = Offset(inset + 8.dp.toPx(), inset),
                            size = Size(size.width - inset * 2 - 8.dp.toPx(), size.height - inset * 2),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(
                                width = 1.2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f), 0f)
                            )
                        )
                    }
                    CoverTexture.CANVAS -> {
                        val step = 6.dp.toPx()
                        val weaveColor = onCoverColor.copy(alpha = 0.055f)
                        var x = 0f
                        while (x < size.width) {
                            drawLine(weaveColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                            x += step
                        }
                        var y = 0f
                        while (y < size.height) {
                            drawLine(weaveColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            y += step
                        }
                    }
                    CoverTexture.MATTE_MINIMAL -> {
                        drawLine(
                            color = Color.White.copy(alpha = 0.16f),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    CoverTexture.BLUEPRINT -> {
                        val step = 14.dp.toPx()
                        val gridColor = Color.White.copy(alpha = 0.18f)
                        var x = 14.dp.toPx()
                        while (x < size.width) {
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                            x += step
                        }
                        var y = 0f
                        while (y < size.height) {
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            y += step
                        }
                    }
                }
            }
        }

        // Subtle book spine crease on the left edge + highlight line
        Row(modifier = Modifier.fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.14f))
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.24f))
            )
        }

        // Characteristic vertical elastic closing strap on the right side (IMG_2726 / IMG_2727)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .width(10.dp)
                .fillMaxHeight()
                .background(spineColor)
        )

        when (cover.labelLayout) {
            CoverLabelLayout.CLASSIC_BAND -> {
                // Warm cream/off-white belly band overlapping lower middle of cover with 4-point star
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 26.dp)
                        .fillMaxWidth(),
                    color = Color(0xFFF6F6F2).copy(alpha = 0.96f),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title.ifBlank { "Névtelen jegyzet" },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color(0xFF1E232A),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (cover.subtitle.isNotBlank()) {
                                Text(
                                    text = cover.subtitle,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = fontFamily),
                                    color = Color(0xFF6C7582),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(
                            imageVector = StylusIcons.StarFourPoint,
                            contentDescription = null,
                            tint = Color(0xFFD0D5DD),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(14.dp)
                        )
                    }
                }
            }

            CoverLabelLayout.CENTER_PLAQUE -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(start = 16.dp, end = 28.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF6F6F2).copy(alpha = 0.95f))
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                ) {
                    CoverLabelTexts(title, cover.subtitle, fontFamily, Color(0xFF1E232A))
                }
            }

            CoverLabelLayout.BOTTOM_CORNER -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, end = 28.dp, bottom = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E232A).copy(alpha = 0.62f))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    CoverLabelTexts(title, cover.subtitle, fontFamily, Color.White)
                }
            }

            CoverLabelLayout.MINIMAL_HEADER -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, end = 28.dp, top = 16.dp)
                ) {
                    CoverLabelTexts(
                        title = title,
                        subtitle = cover.subtitle,
                        fontFamily = fontFamily,
                        titleColor = if (customBitmap != null) Color.White else onCoverColor
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverLabelTexts(
    title: String,
    subtitle: String,
    fontFamily: FontFamily,
    titleColor: Color
) {
    Text(
        text = title.ifBlank { "Névtelen jegyzet" },
        style = MaterialTheme.typography.titleSmall.copy(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold
        ),
        color = titleColor,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    if (subtitle.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = fontFamily),
            color = titleColor.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val COVER_PRIMARY_PALETTE = listOf(
    0xFFA3ABB2.toInt(), // StarNote Slate Grey
    0xFFD4C5B1.toInt(), // Warm Sand
    0xFF8FA69B.toInt(), // Sage Green
    0xFF5C7E9E.toInt(), // Steel Blue
    0xFFB88B8B.toInt(), // Dusty Rose
    0xFF2B4159.toInt(), // Deep Navy
    0xFF3E434C.toInt(), // Graphite Charcoal
    0xFF3A5A40.toInt()  // Forest Moss
)

private val COVER_SPINE_PALETTE = listOf(
    0xFF52585E.toInt(), // StarNote Charcoal Elastic
    0xFF2B4159.toInt(),
    0xFF6B4C35.toInt(),
    0xFF14171C.toInt(),
    0xFF2B689C.toInt(),
    0xFF334E42.toInt()
)

data class NotebookStudioDraft(
    val title: String,
    val cover: NotebookCover,
    val canvasMode: CanvasMode,
    val pageSizePreset: PageSizePreset,
    val templateType: PaperTemplateType,
    val ocrLanguage: OcrLanguageTag
)

/**
 * Redesigned StarNote-inspired Notebook Creator & Cover Studio (IMG_2727 / IMG_2728).
 * Uses clean `#ECEEF1` grouped cards, borderless rounded inputs, pill selectors, and
 * closes via ESC key and backdrop tap per UI guidelines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverDesignerDialog(
    initialTitle: String,
    initialCover: NotebookCover,
    initialCanvasMode: CanvasMode,
    initialPageSizePreset: PageSizePreset,
    initialTemplateType: PaperTemplateType,
    initialOcrLanguage: OcrLanguageTag,
    confirmLabel: String = "Mentés",
    onConfirm: (NotebookStudioDraft) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalStylusSurfacePalette.current
    var title by remember { mutableStateOf(initialTitle) }
    var cover by remember { mutableStateOf(initialCover) }
    var canvasMode by remember { mutableStateOf(initialCanvasMode) }
    var pageSizePreset by remember { mutableStateOf(initialPageSizePreset) }
    var templateType by remember { mutableStateOf(initialTemplateType) }
    var ocrLanguage by remember { mutableStateOf(initialOcrLanguage) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            File(context.filesDir, "covers").apply { mkdirs() }
            val relative = "covers/cover_${UUID.randomUUID()}.jpg"
            val dest = File(context.filesDir, relative)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                cover = cover.copy(customImageRelativePath = relative)
            } catch (_: Throwable) {
                dest.delete()
            }
        }
    }

    ModalBackdropBox(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(20.dp)
                .widthIn(min = 380.dp, max = 760.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (confirmLabel == "Létrehozás") "Új jegyzetfüzet" else "Füzet és borító beállításai",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Bezárás",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Top preview + name/photo card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    NotebookCoverPreviewCard(
                        title = title,
                        cover = cover,
                        modifier = Modifier.width(136.dp)
                    )

                    StudioGroupCard(
                        backgroundColor = palette.groupedCardBackground,
                        modifier = Modifier.weight(1f)
                    ) {
                        StudioTextInput(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "Jegyzetfüzet címe",
                            label = "Cím"
                        )
                        StudioTextInput(
                            value = cover.subtitle,
                            onValueChange = { cover = cover.copy(subtitle = it) },
                            placeholder = "Tantárgy vagy rövid leírás",
                            label = "Alcím"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Egyéni borítókép",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            if (cover.customImageRelativePath != null) {
                                TextButton(
                                    onClick = { cover = cover.copy(customImageRelativePath = null) }
                                ) {
                                    Text("Kép törlése", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // Colors & Spine Band card
                StudioGroupCard(backgroundColor = palette.groupedCardBackground) {
                    Text(
                        "Borító- és szalagszín",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            COVER_PRIMARY_PALETTE.forEach { argb ->
                                val selected = cover.primaryColorArgb == argb
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(argb))
                                        .border(
                                            width = if (selected) 2.2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { cover = cover.copy(primaryColorArgb = argb) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = if (Color(argb).luminance() > 0.48f) Color(0xFF1E232A) else Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Szalag:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            COVER_SPINE_PALETTE.forEach { argb ->
                                val selected = cover.spineColorArgb == argb
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(argb))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { cover = cover.copy(spineColorArgb = argb) }
                                )
                            }
                        }
                    }

                    Text(
                        "Textúra és címkestílus",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CoverTexture.entries.forEach { tex ->
                            StudioPillOption(
                                label = tex.displayName,
                                selected = cover.texture == tex,
                                onClick = { cover = cover.copy(texture = tex) }
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CoverLabelLayout.entries.forEach { layout ->
                            StudioPillOption(
                                label = layout.displayName,
                                selected = cover.labelLayout == layout,
                                onClick = { cover = cover.copy(labelLayout = layout) }
                            )
                        }
                        CoverTypography.entries.forEach { typo ->
                            StudioPillOption(
                                label = typo.displayName,
                                selected = cover.typography == typo,
                                onClick = { cover = cover.copy(typography = typo) }
                            )
                        }
                    }
                }

                // Paper & Canvas Defaults card
                StudioGroupCard(backgroundColor = palette.groupedCardBackground) {
                    Text(
                        "Vászon, papírsablon és OCR nyelv",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CanvasMode.entries.forEach { mode ->
                            StudioPillOption(
                                label = mode.displayName,
                                selected = canvasMode == mode,
                                onClick = { canvasMode = mode }
                            )
                        }
                        if (canvasMode == CanvasMode.FIXED_PAGE) {
                            PageSizePreset.entries.forEach { preset ->
                                StudioPillOption(
                                    label = preset.displayName,
                                    selected = pageSizePreset == preset,
                                    onClick = { pageSizePreset = preset }
                                )
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PaperTemplateType.entries.forEach { tpl ->
                            StudioPillOption(
                                label = tpl.displayName,
                                selected = templateType == tpl,
                                onClick = { templateType = tpl }
                            )
                        }
                        OcrLanguageTag.entries.forEach { lang ->
                            StudioPillOption(
                                label = "OCR: ${lang.displayName}",
                                selected = ocrLanguage == lang,
                                onClick = { ocrLanguage = lang }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Mégse", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                NotebookStudioDraft(
                                    title = title.trim().ifEmpty { "Névtelen jegyzet" },
                                    cover = cover,
                                    canvasMode = canvasMode,
                                    pageSizePreset = pageSizePreset,
                                    templateType = templateType,
                                    ocrLanguage = ocrLanguage
                                )
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioGroupCard(
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun StudioTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StudioPillOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
