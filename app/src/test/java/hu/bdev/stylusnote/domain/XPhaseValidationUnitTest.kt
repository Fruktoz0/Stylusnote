package hu.bdev.stylusnote.domain

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import hu.bdev.stylusnote.core.hardware.StylusAction
import hu.bdev.stylusnote.core.hardware.StylusHardwareShortcutDetector
import hu.bdev.stylusnote.core.hardware.StylusInputDeviceMeta
import hu.bdev.stylusnote.core.hardware.StylusInputRouter
import hu.bdev.stylusnote.core.hardware.StylusMotionFrame
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.StylusPointerSnapshot
import hu.bdev.stylusnote.core.hardware.StylusToggleDebouncer
import hu.bdev.stylusnote.core.hardware.WetStrokeSink
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.OneEuroFilter
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.data.export.BackupManager
import hu.bdev.stylusnote.data.export.BackupManifest
import hu.bdev.stylusnote.data.export.ImportedPdfDocument
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XPhaseValidationUnitTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("xphase_unit_").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private class NoopWetStrokeSink : WetStrokeSink<Unit> {
        override fun onStartRawStroke(event: Unit, pointerId: Int, isEraserTool: Boolean) = Unit
        override fun onAddToRawStroke(realEvent: Unit, predictedEvent: Unit?, pointerId: Int) = Unit
        override fun onFinishRawStroke(event: Unit, pointerId: Int) = Unit
        override fun onStartBatchStroke(firstSample: StylusPointSample, toolType: InputToolType) = Unit
        override fun onAddBatchToStroke(
            realSamples: List<StylusPointSample>,
            predictedSamples: List<StylusPointSample>,
            toolType: InputToolType
        ) = Unit
        override fun onFinishBatchStroke(finalSamples: List<StylusPointSample>, toolType: InputToolType) = Unit
        override fun onCancelActiveStroke() = Unit
    }

    @Test
    fun fitPageToViewportCentersLandscapeAndPortraitPdfsSymmetrically() {
        val matrix = CanvasTransformMatrix()
        val landscape = PageDimensionsPt.LANDSCAPE_16_10
        val vpW = 1200f
        val vpH = 1800f

        matrix.fitPageToViewport(
            pageWidthPt = landscape.widthPt,
            pageHeightPt = landscape.heightPt,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            paddingPx = 48f
        )

        val topLeft = matrix.docToScreen(0f, 0f)
        val bottomRight = matrix.docToScreen(landscape.widthPt, landscape.heightPt)
        val center = matrix.docToScreen(landscape.widthPt * 0.5f, landscape.heightPt * 0.5f)

        assertEquals(vpW - bottomRight.xPx, topLeft.xPx, 1e-2f)
        assertEquals(vpH - bottomRight.yPx, topLeft.yPx, 1e-2f)
        assertEquals(vpW * 0.5f, center.xPx, 1e-2f)
        assertEquals(vpH * 0.5f, center.yPx, 1e-2f)
        assertEquals(100, matrix.relativeZoomPercent)
    }

    @Test
    fun savedPageLocalViewportRoundTripsAcrossContinuousPageAndInfiniteCanvas() {
        val pageSize = PageDimensionsPt.A4_PORTRAIT
        val vpW = 1800f
        val vpH = 1120f
        val slot2OriginXPt = 160f
        val slot2OriginYPt = 1840f

        val original = CanvasTransformMatrix()
        original.fitPageToViewport(
            pageWidthPt = pageSize.widthPt,
            pageHeightPt = pageSize.heightPt,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            paddingPx = 48f
        )
        // Pan viewport center over slot 2's lower-right quadrant (local 412, 620) and zoom ~2.25x.
        val targetLocalX = 412f
        val targetLocalY = 620f
        val worldX = slot2OriginXPt + targetLocalX
        val worldY = slot2OriginYPt + targetLocalY
        val screenBeforeZoom = original.docToScreen(worldX, worldY)
        original.panBy(vpW * 0.5f - screenBeforeZoom.xPx, vpH * 0.5f - screenBeforeZoom.yPx)
        original.zoomAroundScreenPoint(vpW * 0.5f, vpH * 0.5f, 2.25f)

        val anchor = original.capturePageLocalViewportAnchor(
            pageId = "page-2",
            pageOriginXPt = slot2OriginXPt,
            pageOriginYPt = slot2OriginYPt,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH
        )
        assertNotNull(anchor)
        assertEquals("page-2", anchor!!.pageId)
        assertEquals(targetLocalX, anchor.localCenterXPt, 0.05f)
        assertEquals(targetLocalY, anchor.localCenterYPt, 0.05f)
        assertEquals(2.25f, anchor.relativeZoomFactor, 0.01f)

        val restored = CanvasTransformMatrix()
        assertTrue(
            restored.applySavedPageLocalViewport(
                pageOriginXPt = slot2OriginXPt,
                pageOriginYPt = slot2OriginYPt,
                pageWidthPt = pageSize.widthPt,
                pageHeightPt = pageSize.heightPt,
                localCenterXPt = anchor.localCenterXPt,
                localCenterYPt = anchor.localCenterYPt,
                relativeZoomFactor = anchor.relativeZoomFactor,
                viewportWidthPx = vpW,
                viewportHeightPx = vpH,
                paddingPx = 48f
            )
        )
        val restoredScreenCenterDoc = restored.screenToDoc(vpW * 0.5f, vpH * 0.5f)
        assertEquals(worldX, restoredScreenCenterDoc.xPt, 0.05f)
        assertEquals(worldY, restoredScreenCenterDoc.yPt, 0.05f)
        assertEquals(original.scale, restored.scale, 1e-3f)

        // Infinite canvas can have negative local center coordinates.
        val infMatrix = CanvasTransformMatrix()
        assertTrue(
            infMatrix.applySavedPageLocalViewport(
                pageOriginXPt = 0f,
                pageOriginYPt = 0f,
                pageWidthPt = PageDimensionsPt.A4_PORTRAIT.widthPt,
                pageHeightPt = PageDimensionsPt.A4_PORTRAIT.heightPt,
                localCenterXPt = -340f,
                localCenterYPt = -185f,
                relativeZoomFactor = 1.6f,
                viewportWidthPx = vpW,
                viewportHeightPx = vpH
            )
        )
        val infCenterDoc = infMatrix.screenToDoc(vpW * 0.5f, vpH * 0.5f)
        assertEquals(-340f, infCenterDoc.xPt, 0.05f)
        assertEquals(-185f, infCenterDoc.yPt, 0.05f)

        // Invalid relativeZoomFactor or non-finite inputs leave matrix untouched.
        val beforeScale = infMatrix.scale
        assertFalse(
            infMatrix.applySavedPageLocalViewport(
                pageOriginXPt = 0f,
                pageOriginYPt = 0f,
                pageWidthPt = 595f,
                pageHeightPt = 842f,
                localCenterXPt = Float.NaN,
                localCenterYPt = 100f,
                relativeZoomFactor = 1.0f,
                viewportWidthPx = vpW,
                viewportHeightPx = vpH
            )
        )
        assertFalse(
            infMatrix.applySavedPageLocalViewport(
                pageOriginXPt = 0f,
                pageOriginYPt = 0f,
                pageWidthPt = 595f,
                pageHeightPt = 842f,
                localCenterXPt = 100f,
                localCenterYPt = 100f,
                relativeZoomFactor = 0f,
                viewportWidthPx = vpW,
                viewportHeightPx = vpH
            )
        )
        assertEquals(beforeScale, infMatrix.scale, 1e-6f)
    }

    @Test
    fun classifyPdfPageDimensionsPtAndImportedDocumentLookupMatchMixedOrientations() {
        assertEquals(PageDimensionsPt.LANDSCAPE_16_10, PdfTileRenderer.classifyPdfPageDimensionsPt(1280, 800))
        assertEquals(PageDimensionsPt.B5_ISO_PORTRAIT, PdfTileRenderer.classifyPdfPageDimensionsPt(499, 709))
        assertEquals(PageDimensionsPt.A4_PORTRAIT, PdfTileRenderer.classifyPdfPageDimensionsPt(595, 842))

        val doc = ImportedPdfDocument(
            pdfAssetId = "pdf-mixed",
            relativeFilePath = "pdfs/pdf-mixed.pdf",
            pageCount = 3,
            firstPageDimensionsPt = PageDimensionsPt.A4_PORTRAIT,
            perPageDimensionsPt = listOf(
                PageDimensionsPt.A4_PORTRAIT,
                PageDimensionsPt.LANDSCAPE_16_10,
                PageDimensionsPt.B5_ISO_PORTRAIT
            )
        )
        assertEquals(PageDimensionsPt.A4_PORTRAIT, doc.dimensionsForPage(0))
        assertEquals(PageDimensionsPt.LANDSCAPE_16_10, doc.dimensionsForPage(1))
        assertEquals(PageDimensionsPt.B5_ISO_PORTRAIT, doc.dimensionsForPage(2))
        assertEquals(PageDimensionsPt.A4_PORTRAIT, doc.dimensionsForPage(99))
    }

    @Test
    fun hardwareShortcutDetectorScopesFocusPenHidSignalsAndRejectsGenericKeyboard() {
        val focusPenHid = StylusInputDeviceMeta(
            vendorId = StylusHardwareShortcutDetector.XIAOMI_FOCUS_PEN_PRO_VENDOR_ID,
            productId = StylusHardwareShortcutDetector.XIAOMI_FOCUS_PEN_PRO_PRODUCT_ID,
            name = "Xiaomi Focus Pen Pro Keyboard",
            sources = InputDevice.SOURCE_KEYBOARD
        )
        val genericKeyboard = StylusInputDeviceMeta(
            vendorId = 0x046d,
            productId = 0xb342,
            name = "Logitech K380 Keyboard",
            sources = InputDevice.SOURCE_KEYBOARD
        )

        // Standard Android stylus buttons work regardless of attached InputDevice metadata.
        listOf(
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL
        ).forEach { code ->
            assertTrue("Expected keyCode=$code to toggle without device meta",
                StylusHardwareShortcutDetector.shouldToggleEraserForKeyDown(code, repeatCount = 0, deviceMeta = null))
            assertFalse("Repeats must not re-toggle keyCode=$code",
                StylusHardwareShortcutDetector.shouldToggleEraserForKeyDown(code, repeatCount = 1, deviceMeta = null))
        }

        // Focus Pen Pro HID codes trigger only for Focus Pen / stylus devices.
        listOf(
            KeyEvent.KEYCODE_BUTTON_7,
            KeyEvent.KEYCODE_BUTTON_8,
            KeyEvent.KEYCODE_BUTTON_9,
            KeyEvent.KEYCODE_BUTTON_10,
            StylusHardwareShortcutDetector.KEYCODE_F13_API36,
            StylusHardwareShortcutDetector.KEYCODE_F19_API36,
            KeyEvent.KEYCODE_NUMPAD_ADD,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN
        ).forEach { code ->
            assertTrue("Focus Pen HID keyCode=$code should match",
                StylusHardwareShortcutDetector.shouldToggleEraserForKeyDown(code, 0, focusPenHid))
            assertFalse("Generic keyboard keyCode=$code must be ignored",
                StylusHardwareShortcutDetector.shouldToggleEraserForKeyDown(code, 0, genericKeyboard))
        }

        // Motion button edge & Focus Pen mouse buttons.
        assertTrue(
            StylusHardwareShortcutDetector.hasNewlyPressedButton(
                previousButtonState = 0,
                currentButtonState = MotionEvent.BUTTON_STYLUS_SECONDARY,
                deviceMeta = null
            )
        )
        assertTrue(
            StylusHardwareShortcutDetector.shouldToggleEraserForActionButtonPress(
                actionButton = MotionEvent.BUTTON_BACK,
                deviceMeta = focusPenHid
            )
        )
        assertFalse(
            StylusHardwareShortcutDetector.shouldToggleEraserForActionButtonPress(
                actionButton = MotionEvent.BUTTON_BACK,
                deviceMeta = genericKeyboard
            )
        )
    }

    @Test
    fun stylusInputRouterConsumesRepeatsAvoidsDoubleEdgeAndDebouncesRapidToggles() {
        val focusPenDevice = StylusInputDeviceMeta(
            vendorId = StylusHardwareShortcutDetector.XIAOMI_FOCUS_PEN_PRO_VENDOR_ID,
            productId = StylusHardwareShortcutDetector.XIAOMI_FOCUS_PEN_PRO_PRODUCT_ID,
            name = "Xiaomi Focus Pen Pro",
            sources = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_KEYBOARD
        )
        var toggleCount = 0
        val router = StylusInputRouter<Unit>(
            transformMatrix = CanvasTransformMatrix(),
            oneEuroFilter = OneEuroFilter(0f),
            wetStrokeSink = NoopWetStrokeSink(),
            onTogglePenEraser = { toggleCount++ }
        )

        // Repeat key downs must be consumed (return true) without firing additional toggles.
        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, repeatCount = 0, deviceMeta = null))
        assertTrue(router.onKeyDown(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, repeatCount = 1, deviceMeta = null))
        assertEquals(1, toggleCount)

        // ACTION_BUTTON_PRESS fires once and suppresses duplicate edge on following hover frame with same mask.
        assertTrue(
            router.processGenericMotionButtonEvent(
                actionMasked = MotionEvent.ACTION_BUTTON_PRESS,
                actionButton = MotionEvent.BUTTON_STYLUS_SECONDARY,
                buttonState = MotionEvent.BUTTON_STYLUS_SECONDARY,
                deviceMeta = focusPenDevice
            )
        )
        assertEquals(2, toggleCount)
        router.processHoverFrame(
            StylusMotionFrame(
                action = StylusAction.HOVER_MOVE,
                actionIndex = 0,
                flags = 0,
                buttonState = MotionEvent.BUTTON_STYLUS_SECONDARY,
                eventTimeMs = 100L,
                eventTimeNanos = 100_000_000L,
                pointers = listOf(
                    StylusPointerSnapshot(1, MotionEvent.TOOL_TYPE_STYLUS, 100f, 100f, 0f, 0f, 0f)
                ),
                deviceMeta = focusPenDevice
            )
        )
        assertEquals(2, toggleCount)

        val debouncer = StylusToggleDebouncer(minIntervalMs = 180L)
        assertTrue(debouncer.tryAcquireToggle(1000L))
        assertFalse(debouncer.tryAcquireToggle(1040L))
        assertFalse(debouncer.tryAcquireToggle(1179L))
        assertTrue(debouncer.tryAcquireToggle(1180L))
        debouncer.reset()
        assertTrue(debouncer.tryAcquireToggle(1190L))
    }

    @Test
    fun backupManifestV3RoundTripsViewportFieldsWhileV1AndV2DecodeWithNullViewport() {
        val mgr = BackupManager(filesDir = tempDir)

        val v3 = BackupManifest(
            formatVersion = BackupManifest.MANIFEST_VERSION_V3,
            createdAtEpochMs = 9999L,
            notebooks = listOf(
                NotebookEntity(
                    id = "nb-with-vp",
                    title = "Viewporttal",
                    lastOpenedPageId = "pg-3",
                    lastViewportPageId = "pg-3",
                    lastViewportCenterXPt = 312.5f,
                    lastViewportCenterYPt = -48.25f,
                    lastViewportZoomFactor = 1.85f,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                ),
                NotebookEntity(
                    id = "nb-without-vp",
                    title = "Üres viewport",
                    lastOpenedPageId = null,
                    lastViewportPageId = null,
                    lastViewportCenterXPt = null,
                    lastViewportCenterYPt = null,
                    lastViewportZoomFactor = null,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                )
            ),
            pages = listOf(
                PageEntity(
                    id = "pg-3",
                    notebookId = "nb-with-vp",
                    pageIndexInNotebook = 2,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                )
            )
        )

        val decodedV3 = mgr.decodeManifest(mgr.encodeManifest(v3))
        assertEquals(BackupManifest.MANIFEST_VERSION_V3, decodedV3.formatVersion)
        val restoredWithVp = decodedV3.notebooks.first { it.id == "nb-with-vp" }
        assertEquals("pg-3", restoredWithVp.lastViewportPageId)
        assertEquals(312.5f, restoredWithVp.lastViewportCenterXPt ?: 0f, 1e-4f)
        assertEquals(-48.25f, restoredWithVp.lastViewportCenterYPt ?: 0f, 1e-4f)
        assertEquals(1.85f, restoredWithVp.lastViewportZoomFactor ?: 0f, 1e-4f)

        val restoredWithoutVp = decodedV3.notebooks.first { it.id == "nb-without-vp" }
        assertNull(restoredWithoutVp.lastViewportPageId)
        assertNull(restoredWithoutVp.lastViewportCenterXPt)
        assertNull(restoredWithoutVp.lastViewportCenterYPt)
        assertNull(restoredWithoutVp.lastViewportZoomFactor)

        val v2 = BackupManifest(
            formatVersion = BackupManifest.MANIFEST_VERSION_V2,
            createdAtEpochMs = 7777L,
            notebooks = listOf(
                NotebookEntity(
                    id = "nb-v2",
                    title = "Régi v2",
                    lastOpenedPageId = "pg-v2",
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                )
            ),
            pages = listOf(
                PageEntity(
                    id = "pg-v2",
                    notebookId = "nb-v2",
                    pageIndexInNotebook = 0,
                    title = "Első oldal",
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                )
            )
        )
        val decodedV2 = mgr.decodeManifest(mgr.encodeManifest(v2))
        assertEquals(BackupManifest.MANIFEST_VERSION_V2, decodedV2.formatVersion)
        val v2Notebook = decodedV2.notebooks.single()
        assertEquals("pg-v2", v2Notebook.lastOpenedPageId)
        assertNull(v2Notebook.lastViewportPageId)
        assertNull(v2Notebook.lastViewportCenterXPt)
        assertNull(v2Notebook.lastViewportCenterYPt)
        assertNull(v2Notebook.lastViewportZoomFactor)
    }
}
