package hu.bdev.stylusnote.core.hardware

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-Kotlin snapshot of an Android [InputDevice] used so Focus Pen Pro device-scoping and
 * hardware button matching are 100% JVM-unit-testable without Android framework mocks.
 */
data class StylusInputDeviceMeta(
    val vendorId: Int = 0,
    val productId: Int = 0,
    val name: String = "",
    val sources: Int = 0
)

/**
 * Central hardware shortcut detector for standard Android 14+ stylus buttons and Xiaomi Focus Pen
 * Pro HID signals (`Vendor 0x0022 / Product 0x5081`).
 */
object StylusHardwareShortcutDetector {

    const val XIAOMI_FOCUS_PEN_PRO_VENDOR_ID: Int = 0x0022
    const val XIAOMI_FOCUS_PEN_PRO_PRODUCT_ID: Int = 0x5081

    // Android 16 (API 36) keycodes KEYCODE_F13..KEYCODE_F19; kept numeric to compile on compileSdk 35.
    const val KEYCODE_F13_API36: Int = 326
    const val KEYCODE_F14_API36: Int = 327
    const val KEYCODE_F15_API36: Int = 328
    const val KEYCODE_F16_API36: Int = 329
    const val KEYCODE_F17_API36: Int = 330
    const val KEYCODE_F18_API36: Int = 331
    const val KEYCODE_F19_API36: Int = 332

    private const val STYLUS_BUTTON_MASK: Int =
        MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

    private const val FOCUS_PEN_HID_MOUSE_BUTTON_MASK: Int =
        STYLUS_BUTTON_MASK or
            MotionEvent.BUTTON_SECONDARY or
            MotionEvent.BUTTON_TERTIARY or
            MotionEvent.BUTTON_BACK or
            MotionEvent.BUTTON_FORWARD

    fun isXiaomiFocusPenOrStylusDevice(meta: StylusInputDeviceMeta?): Boolean {
        if (meta == null) return false
        if (meta.vendorId == XIAOMI_FOCUS_PEN_PRO_VENDOR_ID &&
            meta.productId == XIAOMI_FOCUS_PEN_PRO_PRODUCT_ID
        ) {
            return true
        }
        if ((meta.sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) {
            return true
        }
        val lowerName = meta.name.lowercase()
        return lowerName.contains("focus pen") ||
            lowerName.contains("xiaomi pen") ||
            lowerName.contains("smart pen") ||
            lowerName.contains("stylus")
    }

    /**
     * Returns `true` when [keyCode] represents a pen button / pinch shortcut that should toggle
     * between pen and eraser. Non-standard HID keys (`BUTTON_7..10`, `F13..F19`, `NUMPAD_ADD`,
     * `PAGE_UP`, `PAGE_DOWN`) are strictly scoped to Focus Pen / stylus devices so normal physical
     * keyboards or volume keys are never hijacked.
     */
    fun shouldToggleEraserForKeyDown(
        keyCode: Int,
        repeatCount: Int = 0,
        deviceMeta: StylusInputDeviceMeta? = null
    ): Boolean {
        if (repeatCount != 0) return false
        when (keyCode) {
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL -> return true
        }
        if (!isXiaomiFocusPenOrStylusDevice(deviceMeta)) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_7,
            KeyEvent.KEYCODE_BUTTON_8,
            KeyEvent.KEYCODE_BUTTON_9,
            KeyEvent.KEYCODE_BUTTON_10,
            KEYCODE_F13_API36,
            KEYCODE_F14_API36,
            KEYCODE_F15_API36,
            KEYCODE_F16_API36,
            KEYCODE_F17_API36,
            KEYCODE_F18_API36,
            KEYCODE_F19_API36,
            KeyEvent.KEYCODE_NUMPAD_ADD,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> true
            else -> false
        }
    }

    fun relevantMotionButtonMask(deviceMeta: StylusInputDeviceMeta?): Int =
        if (isXiaomiFocusPenOrStylusDevice(deviceMeta)) {
            FOCUS_PEN_HID_MOUSE_BUTTON_MASK
        } else {
            STYLUS_BUTTON_MASK
        }

    fun hasNewlyPressedButton(
        previousButtonState: Int,
        currentButtonState: Int,
        deviceMeta: StylusInputDeviceMeta? = null
    ): Boolean {
        val mask = relevantMotionButtonMask(deviceMeta)
        val prevMasked = previousButtonState and mask
        val currMasked = currentButtonState and mask
        return (currMasked and prevMasked.inv()) != 0
    }

    fun shouldToggleEraserForActionButtonPress(
        actionButton: Int,
        deviceMeta: StylusInputDeviceMeta? = null
    ): Boolean {
        val mask = relevantMotionButtonMask(deviceMeta)
        return (actionButton and mask) != 0
    }
}

fun InputDevice?.toStylusDeviceMeta(fallbackSources: Int = 0): StylusInputDeviceMeta? {
    if (this == null && fallbackSources == 0) return null
    return StylusInputDeviceMeta(
        vendorId = this?.vendorId ?: 0,
        productId = this?.productId ?: 0,
        name = this?.name.orEmpty(),
        sources = this?.sources ?: fallbackSources
    )
}

/**
 * Prevents a single physical Focus Pen squeeze/click from toggling twice when Android delivers
 * both a `MotionEvent` button press and a `KeyEvent` within a few milliseconds.
 */
class StylusToggleDebouncer(
    private val minIntervalMs: Long = 180L
) {
    private var lastAcceptedTimestampMs: Long = Long.MIN_VALUE

    @Synchronized
    fun tryAcquireToggle(nowElapsedMs: Long): Boolean {
        val last = lastAcceptedTimestampMs
        if (last != Long.MIN_VALUE && (nowElapsedMs - last) in 0 until minIntervalMs) {
            return false
        }
        lastAcceptedTimestampMs = nowElapsedMs
        return true
    }

    @Synchronized
    fun reset() {
        lastAcceptedTimestampMs = Long.MIN_VALUE
    }
}

/**
 * Lightweight zero-allocation-when-idle diagnostic tracker displayed only when Debug HUD is active.
 */
object StylusShortcutDebugBus {
    private val latestSummaryRef = AtomicReference<String?>(null)

    @Volatile
    var captureEnabled: Boolean = false

    fun recordKeyEvent(keyCode: Int, deviceMeta: StylusInputDeviceMeta?, matchedToggle: Boolean) {
        if (!captureEnabled) return
        val dev = deviceMeta?.name?.takeIf { it.isNotBlank() } ?: "dev"
        val vidPid = if (deviceMeta != null && (deviceMeta.vendorId != 0 || deviceMeta.productId != 0)) {
            " 0x%04x:%04x".format(deviceMeta.vendorId, deviceMeta.productId)
        } else {
            ""
        }
        val keyName = KeyEvent.keyCodeToString(keyCode)
        latestSummaryRef.set("Pen key: $keyName ($dev$vidPid)${if (matchedToggle) " -> Váltás" else ""}")
    }

    fun recordMotionButtonEvent(buttonMask: Int, deviceMeta: StylusInputDeviceMeta?, matchedToggle: Boolean) {
        if (!captureEnabled) return
        val dev = deviceMeta?.name?.takeIf { it.isNotBlank() } ?: "stylus"
        latestSummaryRef.set(
            "Pen btn: 0x${buttonMask.toString(16)} ($dev)${if (matchedToggle) " -> Váltás" else ""}"
        )
    }

    fun currentSummary(): String? = latestSummaryRef.get()
}
