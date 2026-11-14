package hu.bdev.stylusnote.ui.shelf

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
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
            .background(Color.Black.copy(alpha = 0.44f))
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
 * Vector + optional imported-photo cover card rendered for each notebook in the Bookshelf and live
 * inside [CoverDesignerDialog].
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
    val onCoverColor = if (primaryColor.luminance() > 0.45f) Color(0xFF14171C) else Color(0xFFF7F8FA)
    val fontFamily = when (cover.typography) {
        CoverTypography.SANS_SERIF -> FontFamily.SansSerif
        CoverTypography.SERIF -> FontFamily.Serif
        CoverTypography.MONOSPACE -> FontFamily.Monospace
    }

    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)

    Box(
        modifier = modifier
            .aspectRatio(0.72f)
            .clip(shape)
            .background(primaryColor)
            .border(1.dp, Color.Black.copy(alpha = 0.16f), shape)
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
                    .background(Color.Black.copy(alpha = 0.22f))
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                when (cover.texture) {
                    CoverTexture.LEATHER -> {
                        val inset = 12.dp.toPx()
                        drawRoundRect(
                            color = onCoverColor.copy(alpha = 0.26f),
                            topLeft = Offset(inset + 14.dp.toPx(), inset),
                            size = Size(size.width - inset * 2 - 14.dp.toPx(), size.height - inset * 2),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(
                                width = 1.4.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
                            )
                        )
                    }
                    CoverTexture.CANVAS -> {
                        val step = 6.dp.toPx()
                        val weaveColor = onCoverColor.copy(alpha = 0.06f)
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
                            color = Color.White.copy(alpha = 0.12f),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                    CoverTexture.BLUEPRINT -> {
                        val step = 14.dp.toPx()
                        val gridColor = Color.White.copy(alpha = 0.16f)
                        var x = 16.dp.toPx()
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

        // Book spine stripe
        Box(
            modifier = Modifier
                .width(14.dp)
                .fillMaxHeight()
                .background(spineColor)
        )

        val plaqueModifier = when (cover.labelLayout) {
            CoverLabelLayout.CLASSIC_BAND -> Modifier
                .align(Alignment.TopCenter)
                .padding(start = 14.dp, top = 22.dp)
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.90f))
                .padding(horizontal = 12.dp, vertical = 10.dp)

            CoverLabelLayout.CENTER_PLAQUE -> Modifier
                .align(Alignment.Center)
                .padding(start = 22.dp, end = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 10.dp)

            CoverLabelLayout.BOTTOM_CORNER -> Modifier
                .align(Alignment.BottomEnd)
                .padding(start = 24.dp, end = 10.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.56f))
                .padding(horizontal = 10.dp, vertical = 8.dp)

            CoverLabelLayout.MINIMAL_HEADER -> Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, end = 12.dp, top = 16.dp)
        }

        val titleColor = when (cover.labelLayout) {
            CoverLabelLayout.CLASSIC_BAND, CoverLabelLayout.CENTER_PLAQUE -> Color(0xFF14171C)
            CoverLabelLayout.BOTTOM_CORNER -> Color.White
            CoverLabelLayout.MINIMAL_HEADER -> if (customBitmap != null) Color.White else onCoverColor
        }

        Column(modifier = plaqueModifier) {
            Text(
                text = title.ifBlank { "Névtelen jegyzetfüzet" },
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold
                ),
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (cover.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = cover.subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = fontFamily),
                    color = titleColor.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val COVER_PRIMARY_PALETTE = listOf(
    0xFF1F4E79.toInt(),
    0xFF1B4332.toInt(),
    0xFF5A1E2E.toInt(),
    0xFF2B2D42.toInt(),
    0xFF3A5A40.toInt(),
    0xFFD4A373.toInt(),
    0xFF005F73.toInt(),
    0xFF4A4E69.toInt()
)

private val COVER_SPINE_PALETTE = listOf(
    0xFF133352.toInt(),
    0xFF081C15.toInt(),
    0xFF330F19.toInt(),
    0xFF14171C.toInt(),
    0xFF6B4C35.toInt(),
    0xFF0A2E36.toInt()
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
 * Full-featured Cover Designer & Notebook Setup Studio (Section 8).
 * Closes via ESC key and backdrop tap per UI guidelines.
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
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
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
                .padding(24.dp)
                .widthIn(min = 360.dp, max = 720.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Könyvborító Stúdió és Füzetbeállítás",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    NotebookCoverPreviewCard(
                        title = title,
                        cover = cover,
                        modifier = Modifier.width(150.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Jegyzetfüzet címe") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = cover.subtitle,
                            onValueChange = { cover = cover.copy(subtitle = it) },
                            label = { Text("Alcím / Tantárgy") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { imagePickerLauncher.launch("image/*") }
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Galériakép")
                            }
                            if (cover.customImageRelativePath != null) {
                                TextButton(
                                    onClick = { cover = cover.copy(customImageRelativePath = null) }
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Kép törlése")
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text("Borító textúra", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CoverTexture.entries.forEach { tex ->
                        FilterChip(
                            selected = cover.texture == tex,
                            onClick = { cover = cover.copy(texture = tex) },
                            label = { Text(tex.displayName) }
                        )
                    }
                }

                Text("Borító- és gerincszín", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    COVER_PRIMARY_PALETTE.forEach { argb ->
                        val selected = cover.primaryColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f),
                                    shape = CircleShape
                                )
                                .clickable { cover = cover.copy(primaryColorArgb = argb) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (Color(argb).luminance() > 0.45f) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gerinc:", style = MaterialTheme.typography.labelMedium)
                    COVER_SPINE_PALETTE.forEach { argb ->
                        val selected = cover.spineColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f),
                                    shape = CircleShape
                                )
                                .clickable { cover = cover.copy(spineColorArgb = argb) }
                        )
                    }
                }

                Text("Címke elrendezése és betűtípus", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CoverLabelLayout.entries.forEach { layout ->
                        FilterChip(
                            selected = cover.labelLayout == layout,
                            onClick = { cover = cover.copy(labelLayout = layout) },
                            label = { Text(layout.displayName) }
                        )
                    }
                    CoverTypography.entries.forEach { typo ->
                        FilterChip(
                            selected = cover.typography == typo,
                            onClick = { cover = cover.copy(typography = typo) },
                            label = { Text(typo.displayName) }
                        )
                    }
                }

                HorizontalDivider()

                Text("Alapértelmezett vászon, papír és OCR nyelv", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CanvasMode.entries.forEach { mode ->
                        FilterChip(
                            selected = canvasMode == mode,
                            onClick = { canvasMode = mode },
                            label = { Text(mode.displayName) }
                        )
                    }
                    PageSizePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = pageSizePreset == preset,
                            onClick = { pageSizePreset = preset },
                            label = { Text(preset.displayName) }
                        )
                    }
                    PaperTemplateType.entries.forEach { tpl ->
                        FilterChip(
                            selected = templateType == tpl,
                            onClick = { templateType = tpl },
                            label = { Text(tpl.displayName) }
                        )
                    }
                    OcrLanguageTag.entries.forEach { lang ->
                        FilterChip(
                            selected = ocrLanguage == lang,
                            onClick = { ocrLanguage = lang },
                            label = { Text("OCR: ${lang.displayName}") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Mégse")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                NotebookStudioDraft(
                                    title = title.trim().ifEmpty { "Névtelen jegyzetfüzet" },
                                    cover = cover,
                                    canvasMode = canvasMode,
                                    pageSizePreset = pageSizePreset,
                                    templateType = templateType,
                                    ocrLanguage = ocrLanguage
                                )
                            )
                        }
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

