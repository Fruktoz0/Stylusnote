package hu.bdev.stylusnote.data.export

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncSchedulerTest {

    @Test
    fun unconfiguredProviderDisablesAllAutomaticSchedules() {
        val disabled = CloudSyncSettings(
            providerType = CloudProviderType.NONE,
            scheduleMode = CloudSyncScheduleMode.SMART_AUTO,
            wifiOnly = true
        )
        assertNull(CloudSyncPolicy.resolvePeriodicIntervalMinutes(disabled))
        assertNull(CloudSyncPolicy.resolveIdleDelayMs(disabled))
        assertFalse(CloudSyncPolicy.shouldSyncOnPageTransition(disabled))
        assertFalse(CloudSyncPolicy.shouldSyncOnNotebookOrAppExit(disabled))
    }

    @Test
    fun smartAutoEnablesPageTransitionAppExitThreeMinuteIdleAnd60MinFallback() {
        val smart = CloudSyncSettings(
            providerType = CloudProviderType.GOOGLE_DRIVE,
            googleDriveAccountEmail = "user@gmail.com",
            scheduleMode = CloudSyncScheduleMode.SMART_AUTO,
            wifiOnly = true
        )
        assertEquals(180_000L, CloudSyncPolicy.resolveIdleDelayMs(smart))
        assertEquals(60L, CloudSyncPolicy.resolvePeriodicIntervalMinutes(smart))
        assertTrue(CloudSyncPolicy.shouldSyncOnPageTransition(smart))
        assertTrue(CloudSyncPolicy.shouldSyncOnNotebookOrAppExit(smart))
        assertEquals(NetworkType.UNMETERED, CloudSyncPolicy.resolveBackgroundNetworkType(smart))
    }

    @Test
    fun batterySaverSkipsPerPageAndIdleSyncButKeepsExitAnd30MinPeriodic() {
        val saver = CloudSyncSettings(
            providerType = CloudProviderType.WEBDAV,
            webDavBaseUrl = "https://nas.example.com/dav",
            webDavUsername = "bela",
            scheduleMode = CloudSyncScheduleMode.BATTERY_SAVER,
            wifiOnly = false
        )
        assertNull(CloudSyncPolicy.resolveIdleDelayMs(saver))
        assertEquals(30L, CloudSyncPolicy.resolvePeriodicIntervalMinutes(saver))
        assertFalse(CloudSyncPolicy.shouldSyncOnPageTransition(saver))
        assertTrue(CloudSyncPolicy.shouldSyncOnNotebookOrAppExit(saver))
        assertEquals(NetworkType.CONNECTED, CloudSyncPolicy.resolveBackgroundNetworkType(saver))
    }

    @Test
    fun manualModeDisablesAllAutomaticSyncTriggers() {
        val manual = CloudSyncSettings(
            providerType = CloudProviderType.GOOGLE_DRIVE,
            googleDriveAccountEmail = "user@gmail.com",
            scheduleMode = CloudSyncScheduleMode.MANUAL,
            wifiOnly = false
        )
        assertNull(CloudSyncPolicy.resolveIdleDelayMs(manual))
        assertNull(CloudSyncPolicy.resolvePeriodicIntervalMinutes(manual))
        assertFalse(CloudSyncPolicy.shouldSyncOnPageTransition(manual))
        assertFalse(CloudSyncPolicy.shouldSyncOnNotebookOrAppExit(manual))
    }
}
