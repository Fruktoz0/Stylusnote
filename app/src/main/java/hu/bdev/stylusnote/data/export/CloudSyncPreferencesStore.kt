package hu.bdev.stylusnote.data.export

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hu.bdev.stylusnote.core.ai.AndroidKeystoreAesGcmCipher
import hu.bdev.stylusnote.core.ai.ApiKeyCipher
import hu.bdev.stylusnote.core.ai.KeystoreDecryptionException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets
import java.util.Base64

private val Context.cloudSyncDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cloud_sync_settings"
)

enum class CloudProviderType(val labelHu: String) {
    NONE("Nincs beállítva"),
    GOOGLE_DRIVE("Google Drive"),
    WEBDAV("WebDAV (Nextcloud / NAS)")
}

enum class CloudSyncScheduleMode(
    val labelHu: String,
    val descriptionHu: String
) {
    SMART_AUTO(
        labelHu = "Okos automata",
        descriptionHu = "Oldal-/füzetváltáskor, app elhagyásakor és 3 perc írásszünet után (aktív írás közben nem zavar)."
    ),
    BATTERY_SAVER(
        labelHu = "Energiatakarékos",
        descriptionHu = "Csak füzet/app elhagyásakor és legfeljebb 30 percenként."
    ),
    MANUAL(
        labelHu = "Kézi",
        descriptionHu = "Csak a felhő ikonra koppintva ment."
    )
}

enum class CloudSyncRuntimeStatus {
    DISABLED,
    IDLE_SYNCED,
    PENDING_CHANGES,
    SYNCING,
    NEEDS_REAUTH,
    ERROR
}

data class CloudSyncSettings(
    val providerType: CloudProviderType = CloudProviderType.NONE,
    val scheduleMode: CloudSyncScheduleMode = CloudSyncScheduleMode.SMART_AUTO,
    val wifiOnly: Boolean = true,
    val googleDriveAccountEmail: String? = null,
    val googleDriveFolderId: String? = null,
    val webDavBaseUrl: String = "",
    val webDavUsername: String = "",
    val hasWebDavPassword: Boolean = false,
    val lastSyncEpochMs: Long = 0L,
    val lastErrorMessage: String? = null,
    val needsReauth: Boolean = false,
    val hasPendingLocalChanges: Boolean = false
) {
    val isConfigured: Boolean
        get() = when (providerType) {
            CloudProviderType.NONE -> false
            CloudProviderType.GOOGLE_DRIVE -> !googleDriveAccountEmail.isNullOrBlank()
            CloudProviderType.WEBDAV -> webDavBaseUrl.isNotBlank() && webDavUsername.isNotBlank()
        }

    fun deriveRuntimeStatus(isActivelySyncing: Boolean): CloudSyncRuntimeStatus = when {
        providerType == CloudProviderType.NONE || !isConfigured -> CloudSyncRuntimeStatus.DISABLED
        isActivelySyncing -> CloudSyncRuntimeStatus.SYNCING
        needsReauth -> CloudSyncRuntimeStatus.NEEDS_REAUTH
        !lastErrorMessage.isNullOrBlank() -> CloudSyncRuntimeStatus.ERROR
        hasPendingLocalChanges -> CloudSyncRuntimeStatus.PENDING_CHANGES
        else -> CloudSyncRuntimeStatus.IDLE_SYNCED
    }
}

data class CloudSyncUiState(
    val settings: CloudSyncSettings = CloudSyncSettings(),
    val isSyncing: Boolean = false
) {
    val status: CloudSyncRuntimeStatus
        get() = settings.deriveRuntimeStatus(isSyncing)
}

/**
 * Process-wide reactive status holder updated by [CloudSyncPreferencesStore] and
 * [CloudSyncWorker] so the canvas badge renders live progress without polling.
 */
object CloudSyncStatusBus {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun setSyncing(active: Boolean) {
        _isSyncing.value = active
    }
}

/**
 * DataStore + Android Keystore AES-256-GCM encrypted persistence for cloud sync settings.
 * Lives under `datastore/`, which [BackupManager.isAllowedBackupEntryPath] excludes from backups.
 */
class CloudSyncPreferencesStore(
    private val context: Context,
    private val cipher: ApiKeyCipher = AndroidKeystoreAesGcmCipher(keyAlias = CLOUD_KEY_ALIAS)
) {
    val settingsFlow: Flow<CloudSyncSettings> = context.cloudSyncDataStore.data.map(::decode)

    suspend fun currentSettings(): CloudSyncSettings = settingsFlow.first()

    suspend fun saveGoogleDriveConnection(
        accountEmail: String,
        folderId: String? = null
    ) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_PROVIDER] = CloudProviderType.GOOGLE_DRIVE.name
            prefs[KEY_GDRIVE_EMAIL] = accountEmail.trim()
            if (folderId != null) {
                prefs[KEY_GDRIVE_FOLDER_ID] = folderId
            }
            prefs[KEY_NEEDS_REAUTH] = false
            prefs.remove(KEY_LAST_ERROR)
        }
    }

    suspend fun saveGoogleDriveFolderId(folderId: String) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_GDRIVE_FOLDER_ID] = folderId
        }
    }

    suspend fun saveWebDavConfig(
        baseUrl: String,
        username: String,
        rawPassword: String?
    ) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_PROVIDER] = CloudProviderType.WEBDAV.name
            prefs[KEY_WEBDAV_URL] = baseUrl.trim().trimEnd('/')
            prefs[KEY_WEBDAV_USER] = username.trim()
            if (!rawPassword.isNullOrEmpty()) {
                val encrypted = cipher.encrypt(rawPassword.toByteArray(StandardCharsets.UTF_8))
                prefs[KEY_WEBDAV_ENC_PASS] = Base64.getEncoder().encodeToString(encrypted)
            }
            prefs[KEY_NEEDS_REAUTH] = false
            prefs.remove(KEY_LAST_ERROR)
        }
    }

    suspend fun decryptWebDavPassword(): String? {
        val prefs = context.cloudSyncDataStore.data.first()
        val encoded = prefs[KEY_WEBDAV_ENC_PASS] ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            String(cipher.decrypt(bytes), StandardCharsets.UTF_8)
        } catch (_: KeystoreDecryptionException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun updateScheduleAndNetwork(
        scheduleMode: CloudSyncScheduleMode,
        wifiOnly: Boolean
    ) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_SCHEDULE_MODE] = scheduleMode.name
            prefs[KEY_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun disconnectProvider() {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_PROVIDER] = CloudProviderType.NONE.name
            prefs.remove(KEY_GDRIVE_FOLDER_ID)
            prefs[KEY_NEEDS_REAUTH] = false
            prefs.remove(KEY_LAST_ERROR)
            prefs[KEY_PENDING_CHANGES] = false
        }
    }

    suspend fun markPendingLocalChanges(pending: Boolean) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_PENDING_CHANGES] = pending
        }
    }

    suspend fun recordSyncSucceeded(syncedAtMs: Long = System.currentTimeMillis()) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_MS] = syncedAtMs
            prefs[KEY_PENDING_CHANGES] = false
            prefs[KEY_NEEDS_REAUTH] = false
            prefs.remove(KEY_LAST_ERROR)
        }
    }

    suspend fun recordSyncError(message: String, needsReauth: Boolean = false) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[KEY_NEEDS_REAUTH] = needsReauth
            prefs[KEY_LAST_ERROR] = message.take(240)
        }
    }

    private fun decode(prefs: Preferences): CloudSyncSettings {
        val provider = prefs[KEY_PROVIDER]?.let { name ->
            CloudProviderType.entries.firstOrNull { it.name == name }
        } ?: CloudProviderType.NONE
        val mode = prefs[KEY_SCHEDULE_MODE]?.let { name ->
            CloudSyncScheduleMode.entries.firstOrNull { it.name == name }
        } ?: CloudSyncScheduleMode.SMART_AUTO
        return CloudSyncSettings(
            providerType = provider,
            scheduleMode = mode,
            wifiOnly = prefs[KEY_WIFI_ONLY] ?: true,
            googleDriveAccountEmail = prefs[KEY_GDRIVE_EMAIL],
            googleDriveFolderId = prefs[KEY_GDRIVE_FOLDER_ID],
            webDavBaseUrl = prefs[KEY_WEBDAV_URL].orEmpty(),
            webDavUsername = prefs[KEY_WEBDAV_USER].orEmpty(),
            hasWebDavPassword = !prefs[KEY_WEBDAV_ENC_PASS].isNullOrEmpty(),
            lastSyncEpochMs = prefs[KEY_LAST_SYNC_MS] ?: 0L,
            lastErrorMessage = prefs[KEY_LAST_ERROR],
            needsReauth = prefs[KEY_NEEDS_REAUTH] ?: false,
            hasPendingLocalChanges = prefs[KEY_PENDING_CHANGES] ?: false
        )
    }

    companion object {
        private const val CLOUD_KEY_ALIAS = "stylusnote_cloud_credentials_aes256_gcm"

        private val KEY_PROVIDER = stringPreferencesKey("provider_type")
        private val KEY_SCHEDULE_MODE = stringPreferencesKey("schedule_mode")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_GDRIVE_EMAIL = stringPreferencesKey("gdrive_account_email")
        private val KEY_GDRIVE_FOLDER_ID = stringPreferencesKey("gdrive_folder_id")
        private val KEY_WEBDAV_URL = stringPreferencesKey("webdav_base_url")
        private val KEY_WEBDAV_USER = stringPreferencesKey("webdav_username")
        private val KEY_WEBDAV_ENC_PASS = stringPreferencesKey("webdav_enc_pass_b64")
        private val KEY_LAST_SYNC_MS = longPreferencesKey("last_sync_epoch_ms")
        private val KEY_LAST_ERROR = stringPreferencesKey("last_error_msg")
        private val KEY_NEEDS_REAUTH = booleanPreferencesKey("needs_reauth")
        private val KEY_PENDING_CHANGES = booleanPreferencesKey("has_pending_changes")
    }
}
