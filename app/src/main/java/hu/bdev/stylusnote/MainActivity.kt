package hu.bdev.stylusnote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.ui.platform.LocalView
import hu.bdev.stylusnote.core.telemetry.FrameTimingAccumulator
import hu.bdev.stylusnote.core.telemetry.FrameTimingSnapshot
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
import hu.bdev.stylusnote.data.export.CloudSyncScheduler
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.ui.shelf.BookshelfScreen
import hu.bdev.stylusnote.ui.shelf.NotebookWorkspaceScreen
import hu.bdev.stylusnote.ui.shelf.OpenedPageTarget
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private tailrec fun findHostWindow(context: Context?): Window? = when (context) {
    is Activity -> context.window
    is ContextWrapper -> findHostWindow(context.baseContext)
    else -> null
}

class MainActivity : ComponentActivity() {

    private var lockedRefreshRateHz by mutableFloatStateOf(60f)
    private var reportedRefreshRateHz by mutableFloatStateOf(60f)
    private var penButtonToggleTick by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyImmersiveFullscreen()
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
        applyImmersiveFullscreen()
        configureHighRefreshRateDisplay()
    }

    override fun onStop() {
        super.onStop()
        CloudSyncScheduler.onAppBackgrounded(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
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
            CloudSyncScheduler.syncPeriodicSchedule(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val activePrimary = primaryTarget
        if (activePrimary == null) {
            BookshelfScreen(
                database = database,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onOpenSinglePage = { target ->
                    primaryTarget = target
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NotebookWorkspaceScreen(
                database = database,
                target = activePrimary,
                penButtonToggleTick = penButtonToggleTick,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onSelectPageTarget = { nextTarget ->
                    primaryTarget = nextTarget
                },
                onExitToBookshelf = {
                    primaryTarget = null
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
    val statusColor = if (stats.isRenderBudgetMet) {
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
                text = "Display: ${stats.displayRefreshHz.roundToInt()} Hz (lock ${stats.lockedModeHz.roundToInt()} Hz) | VSYNC: ${stats.vsyncFps.roundToInt()} FPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val cpuText = if (stats.hasRenderWorkSamples) {
                "${"%.2f".format(stats.cpuWorkP50Ms)} / ${"%.2f".format(stats.cpuWorkP95Ms)} ms"
            } else {
                "— / — ms"
            }
            Text(
                text = "VSYNC p95: ${"%.2f".format(stats.vsyncP95Ms)} ms | CPU p50/p95: $cpuText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val renderP95Text = if (stats.hasRenderWorkSamples) {
                "${"%.2f".format(stats.renderWorkP95Ms)} ms"
            } else {
                "— ms"
            }
            val gpuText = if (stats.hasGpuSamples) {
                " | GPU p95: ${"%.2f".format(stats.gpuWorkP95Ms)} ms"
            } else {
                ""
            }
            Text(
                text = "Render p95: $renderP95Text$gpuText (cél ≤ ${"%.2f".format(stats.frameBudgetMs)} ms)",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }
    }
}

private fun extractCpuAndGpuWorkNanos(frameMetrics: FrameMetrics): Pair<Long, Long> {
    // Sum UI + RenderThread work stages directly instead of TOTAL_DURATION, which includes
    // VSYNC queue wait and triple-buffer swap-buffer stall (~25–40 ms at 144 Hz).
    val drawNs = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
    val syncNs = frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
    val commandNs = frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
    val cpuStagesSupported = drawNs >= 0L || syncNs >= 0L || commandNs >= 0L
    val cpuStagesNs =
        frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION).coerceAtLeast(0L) +
            frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION).coerceAtLeast(0L) +
            frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).coerceAtLeast(0L) +
            drawNs.coerceAtLeast(0L) +
            syncNs.coerceAtLeast(0L) +
            commandNs.coerceAtLeast(0L)
    val resolvedCpuNs = if (cpuStagesSupported) {
        cpuStagesNs
    } else {
        frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
    }
    val gpuNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val rawGpu = frameMetrics.getMetric(FrameMetrics.GPU_DURATION)
        if (rawGpu >= 0L) rawGpu else -1L
    } else {
        -1L
    }
    return resolvedCpuNs to gpuNs
}

@Composable
private fun rememberFrameTimingStats(
    enabled: Boolean,
    lockedRefreshRateHz: Float,
    reportedRefreshRateHz: Float,
    currentDisplayHzProvider: () -> Float
): FrameTimingSnapshot {
    var stats by remember {
        mutableStateOf(
            FrameTimingSnapshot(
                displayRefreshHz = reportedRefreshRateHz,
                lockedModeHz = lockedRefreshRateHz
            )
        )
    }

    val hostWindow = findHostWindow(LocalView.current.context)

    DisposableEffect(enabled, hostWindow, lockedRefreshRateHz, reportedRefreshRateHz) {
        if (!enabled) return@DisposableEffect onDispose {}

        val accumulator = FrameTimingAccumulator()
        val choreographer = Choreographer.getInstance()
        val mainHandler = Handler(Looper.getMainLooper())
        var lastUiUpdateNanos = 0L

        val metricsListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            if (frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val (cpuNs, gpuNs) = extractCpuAndGpuWorkNanos(frameMetrics)
            if (cpuNs > 0L || gpuNs >= 0L) {
                accumulator.recordFrameMetrics(
                    cpuWorkNanos = cpuNs,
                    gpuDurationNanos = gpuNs
                )
            }
        }

        hostWindow?.addOnFrameMetricsAvailableListener(metricsListener, mainHandler)

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                accumulator.recordVsyncTimestampNanos(frameTimeNanos)
                if (frameTimeNanos - lastUiUpdateNanos >= 250_000_000L) {
                    lastUiUpdateNanos = frameTimeNanos
                    stats = accumulator.snapshot(
                        displayRefreshHz = currentDisplayHzProvider(),
                        lockedModeHz = lockedRefreshRateHz
                    )
                }
                choreographer.postFrameCallback(this)
            }
        }

        choreographer.postFrameCallback(callback)
        onDispose {
            choreographer.removeFrameCallback(callback)
            hostWindow?.let { w ->
                runCatching { w.removeOnFrameMetricsAvailableListener(metricsListener) }
            }
        }
    }

    return stats
}
