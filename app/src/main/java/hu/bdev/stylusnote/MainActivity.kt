package hu.bdev.stylusnote

import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.ui.shelf.BookshelfScreen
import hu.bdev.stylusnote.ui.shelf.OpenedPageTarget
import hu.bdev.stylusnote.ui.shelf.SplitScreenCanvasWorkspace
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private data class FrameTimingStats(
    val fps: Float = 0f,
    val lastFrameMs: Float = 0f,
    val p95FrameMs: Float = 0f,
    val displayRefreshHz: Float = 60f,
    val lockedModeHz: Float = 60f
)

class MainActivity : ComponentActivity() {

    private var lockedRefreshRateHz by mutableFloatStateOf(60f)
    private var reportedRefreshRateHz by mutableFloatStateOf(60f)
    private var penButtonToggleTick by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureHighRefreshRateDisplay()
        val benchmarkStrokeCount = intent?.getIntExtra(EXTRA_BENCHMARK_STROKE_COUNT, 0) ?: 0

        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }
            val isOledDeepBlack by remember { mutableStateOf(true) }
            var showDebugOverlay by remember { mutableStateOf(false) }

            StylusNoteTheme(
                darkTheme = isDarkTheme,
                oledDeepBlack = isOledDeepBlack
            ) {
                StylusNoteAppRoot(
                    penButtonToggleTick = penButtonToggleTick,
                    benchmarkStrokeCount = benchmarkStrokeCount,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme },
                    showDebugOverlay = showDebugOverlay,
                    onToggleDebugOverlay = { showDebugOverlay = !showDebugOverlay },
                    lockedRefreshRateHz = lockedRefreshRateHz,
                    reportedRefreshRateHz = reportedRefreshRateHz,
                    currentDisplayHzProvider = { resolveActiveDisplay()?.refreshRate ?: reportedRefreshRateHz }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        configureHighRefreshRateDisplay()
    }

    /**
     * Focus Pen Pro primary button hook: toggles between the active brush and the most
     * recently used eraser without reacting to key repeats.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY && event?.repeatCount == 0) {
            penButtonToggleTick++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Locks preferredDisplayModeId to the highest refresh rate mode matching the active
     * physical resolution (144 Hz on Xiaomi Pad 8) and applies the API 35+ frame-rate hint.
     */
    private fun configureHighRefreshRateDisplay() {
        val activeDisplay = resolveActiveDisplay() ?: return
        val currentMode = activeDisplay.mode
        reportedRefreshRateHz = activeDisplay.refreshRate

        val bestMode = activeDisplay.supportedModes
            .filter { mode ->
                mode.physicalWidth == currentMode.physicalWidth &&
                    mode.physicalHeight == currentMode.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: currentMode

        lockedRefreshRateHz = bestMode.refreshRate
        if (window.attributes.preferredDisplayModeId != bestMode.modeId) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = bestMode.modeId
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveActiveDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display ?: windowManager.defaultDisplay
        } else {
            windowManager.defaultDisplay
        }

    companion object {
        const val EXTRA_BENCHMARK_STROKE_COUNT = "hu.bdev.stylusnote.EXTRA_BENCHMARK_STROKE_COUNT"
        const val BENCHMARK_NOTEBOOK_ID = "__benchmark_nb__"
        const val BENCHMARK_PAGE_ID = "__benchmark_page__"
    }
}

@Composable
private fun StylusNoteAppRoot(
    penButtonToggleTick: Long,
    benchmarkStrokeCount: Int,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    showDebugOverlay: Boolean,
    onToggleDebugOverlay: () -> Unit,
    lockedRefreshRateHz: Float,
    reportedRefreshRateHz: Float,
    currentDisplayHzProvider: () -> Float
) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getInstance(context.applicationContext) }

    var primaryTarget by remember(benchmarkStrokeCount) {
        mutableStateOf<OpenedPageTarget?>(
            if (benchmarkStrokeCount > 0) {
                OpenedPageTarget(
                    notebookId = MainActivity.BENCHMARK_NOTEBOOK_ID,
                    pageId = MainActivity.BENCHMARK_PAGE_ID
                )
            } else {
                null
            }
        )
    }
    var secondaryTarget by remember { mutableStateOf<OpenedPageTarget?>(null) }
    var selectingSecondaryForSplit by remember { mutableStateOf(false) }

    // Startup crash recovery & initial starter notebook provisioning.
    LaunchedEffect(database, benchmarkStrokeCount) {
        if (benchmarkStrokeCount > 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            StrokeJournal.recoverAllPagesOnStartup(
                pagesRootDir = File(context.filesDir, "pages"),
                compactRecoveredPages = true
            )
            val existing = database.notebookDao().observeAllNotebooks().first()
            if (existing.isEmpty()) {
                val now = System.currentTimeMillis()
                val nbId = UUID.randomUUID().toString()
                val pageId = UUID.randomUUID().toString()
                database.notebookDao().upsertNotebook(
                    NotebookEntity(
                        id = nbId,
                        title = "Első jegyzetfüzet",
                        lastOpenedPageId = pageId,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
                database.pageDao().upsertPage(
                    PageEntity(
                        id = pageId,
                        notebookId = nbId,
                        pageIndexInNotebook = 0,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val activePrimary = primaryTarget
        if (activePrimary == null || selectingSecondaryForSplit) {
            BookshelfScreen(
                database = database,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onOpenSinglePage = { target ->
                    if (selectingSecondaryForSplit && primaryTarget != null) {
                        secondaryTarget = target
                        selectingSecondaryForSplit = false
                    } else {
                        primaryTarget = target
                        secondaryTarget = null
                    }
                },
                onOpenSplitScreen = { left, right ->
                    primaryTarget = left
                    secondaryTarget = right
                    selectingSecondaryForSplit = false
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SplitScreenCanvasWorkspace(
                database = database,
                primaryTarget = activePrimary,
                secondaryTarget = secondaryTarget,
                penButtonToggleTick = penButtonToggleTick,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onExitToBookshelf = {
                    primaryTarget = null
                    secondaryTarget = null
                    selectingSecondaryForSplit = false
                },
                onCloseSecondaryPane = {
                    secondaryTarget = null
                    selectingSecondaryForSplit = false
                },
                onSelectSecondaryFromBookshelf = {
                    selectingSecondaryForSplit = true
                },
                showDebugOverlay = showDebugOverlay,
                onToggleDebugOverlay = onToggleDebugOverlay,
                benchmarkStrokeCount = benchmarkStrokeCount,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showDebugOverlay) {
            FrameDebugOverlay(
                lockedRefreshRateHz = lockedRefreshRateHz,
                reportedRefreshRateHz = reportedRefreshRateHz,
                currentDisplayHzProvider = currentDisplayHzProvider,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun FrameDebugOverlay(
    lockedRefreshRateHz: Float,
    reportedRefreshRateHz: Float,
    currentDisplayHzProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    // Scope frame timing polling inside this leaf composable so root layout never recomposes every 250 ms.
    val stats = rememberFrameTimingStats(
        enabled = true,
        lockedRefreshRateHz = lockedRefreshRateHz,
        reportedRefreshRateHz = reportedRefreshRateHz,
        currentDisplayHzProvider = currentDisplayHzProvider
    )
    val budgetMet = stats.p95FrameMs <= 6.94f
    val statusColor = if (budgetMet) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Display: ${stats.displayRefreshHz.roundToInt()} Hz (lock ${stats.lockedModeHz.roundToInt()} Hz)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "FPS: ${stats.fps.roundToInt()} | frame: ${"%.2f".format(stats.lastFrameMs)} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "p95: ${"%.2f".format(stats.p95FrameMs)} ms (cél ≤ 6.94 ms)",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }
    }
}

@Composable
private fun rememberFrameTimingStats(
    enabled: Boolean,
    lockedRefreshRateHz: Float,
    reportedRefreshRateHz: Float,
    currentDisplayHzProvider: () -> Float
): FrameTimingStats {
    var stats by remember {
        mutableStateOf(
            FrameTimingStats(
                displayRefreshHz = reportedRefreshRateHz,
                lockedModeHz = lockedRefreshRateHz
            )
        )
    }

    val windowSize = 120
    val sortScratch = remember(windowSize) { FloatArray(windowSize) }

    DisposableEffect(enabled, lockedRefreshRateHz, reportedRefreshRateHz) {
        if (!enabled) return@DisposableEffect onDispose {}

        val choreographer = Choreographer.getInstance()
        val frameDurationsMs = FloatArray(windowSize)
        var filled = 0
        var writeIdx = 0
        var lastFrameNanos = 0L
        var lastUiUpdateNanos = 0L

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos != 0L) {
                    val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                    frameDurationsMs[writeIdx] = deltaMs
                    writeIdx = (writeIdx + 1) % windowSize
                    if (filled < windowSize) filled++

                    if (frameTimeNanos - lastUiUpdateNanos >= 250_000_000L && filled > 0) {
                        lastUiUpdateNanos = frameTimeNanos
                        System.arraycopy(frameDurationsMs, 0, sortScratch, 0, filled)
                        sortScratch.sort(0, filled)
                        val p95Index = ((filled - 1) * 0.95f).toInt().coerceIn(0, filled - 1)
                        var sumMs = 0f
                        for (i in 0 until filled) sumMs += sortScratch[i]
                        val avgMs = sumMs / filled
                        val fps = if (avgMs > 0f) 1000f / avgMs else 0f

                        stats = FrameTimingStats(
                            fps = fps,
                            lastFrameMs = deltaMs,
                            p95FrameMs = sortScratch[p95Index],
                            displayRefreshHz = currentDisplayHzProvider(),
                            lockedModeHz = lockedRefreshRateHz
                        )
                    }
                }
                lastFrameNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
            }
        }

        choreographer.postFrameCallback(callback)
        onDispose {
            choreographer.removeFrameCallback(callback)
        }
    }

    return stats
}
