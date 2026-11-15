package hu.bdev.stylusnote.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.aiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "stylusnote_ai_settings"
)

/**
 * Abstract key-value storage used by [AiConsentStore].
 * Production code uses [DataStoreAiSettingsStorage] (`datastore/stylusnote_ai_settings.preferences_pb`,
 * which [hu.bdev.stylusnote.data.export.BackupManager] explicitly excludes from backups).
 */
interface AiSettingsStorage {
    val preferencesFlow: Flow<Map<String, String>>
    suspend fun readAll(): Map<String, String>
    suspend fun write(updates: Map<String, String?>)
}

class DataStoreAiSettingsStorage(private val context: Context) : AiSettingsStorage {
    override val preferencesFlow: Flow<Map<String, String>> =
        context.aiSettingsDataStore.data.map { prefs -> toStringMap(prefs) }

    override suspend fun readAll(): Map<String, String> =
        toStringMap(context.aiSettingsDataStore.data.first())

    override suspend fun write(updates: Map<String, String?>) {
        context.aiSettingsDataStore.edit { prefs ->
            updates.forEach { (key, value) ->
                val prefKey = stringPreferencesKey(key)
                if (value == null) {
                    prefs.remove(prefKey)
                } else {
                    prefs[prefKey] = value
                }
            }
        }
    }

    private fun toStringMap(prefs: Preferences): Map<String, String> = buildMap {
        prefs.asMap().forEach { (prefKey, value) ->
            if (value is String) {
                put(prefKey.name, value)
            }
        }
    }
}

/** Deterministic in-memory [AiSettingsStorage] implementation for unit tests. */
class InMemoryAiSettingsStorage(initial: Map<String, String> = emptyMap()) : AiSettingsStorage {
    private val state = MutableStateFlow(initial)
    override val preferencesFlow: Flow<Map<String, String>> = state.asStateFlow()

    override suspend fun readAll(): Map<String, String> = state.value

    override suspend fun write(updates: Map<String, String?>) {
        state.update { current ->
            current.toMutableMap().apply {
                updates.forEach { (key, value) ->
                    if (value == null) remove(key) else put(key, value)
                }
            }
        }
    }
}

/** Encryption contract for securing the user's BYOK Gemini API key before persisting to DataStore. */
interface ApiKeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ivAndCiphertext: ByteArray): ByteArray
}

class KeystoreDecryptionException(
    message: String,
    cause: Throwable? = null
) : GeneralSecurityException(message, cause)

private object AesGcmWireCodec {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_LENGTH_BYTES = 12
    const val TAG_LENGTH_BITS = 128

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH_BYTES) {
            "Expected $IV_LENGTH_BYTES-byte GCM IV, got ${iv.size}"
        }
        return ByteArray(IV_LENGTH_BYTES + ciphertext.size).apply {
            System.arraycopy(iv, 0, this, 0, IV_LENGTH_BYTES)
            System.arraycopy(ciphertext, 0, this, IV_LENGTH_BYTES, ciphertext.size)
        }
    }

    fun decrypt(secretKey: SecretKey, ivAndCiphertext: ByteArray): ByteArray {
        if (ivAndCiphertext.size <= IV_LENGTH_BYTES) {
            throw KeystoreDecryptionException("Titkosított kulcs csonka vagy érvénytelen hosszúságú.")
        }
        val iv = ivAndCiphertext.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = ivAndCiphertext.copyOfRange(IV_LENGTH_BYTES, ivAndCiphertext.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (t: GeneralSecurityException) {
            throw KeystoreDecryptionException("Az API-kulcs visszafejtése sikertelen.", t)
        }
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM cipher. Key material never leaves the Keystore;
 * only the `[12-byte IV][ciphertext+tag]` payload is written into DataStore.
 */
class AndroidKeystoreAesGcmCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : ApiKeyCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AesGcmWireCodec.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return AesGcmWireCodec.pack(iv, ciphertext)
    }

    override fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        val secretKey = getExistingSecretKey()
            ?: throw KeystoreDecryptionException(
                "A titkosító Keystore kulcs nem található; kérjük, add meg újra az API-kulcsot."
            )
        return AesGcmWireCodec.decrypt(secretKey, ivAndCiphertext)
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "stylusnote_gemini_byok_aes256_gcm"
    }
}

/**
 * Pure JCE AES-GCM cipher mirroring [AndroidKeystoreAesGcmCipher] wire format for desktop JVM tests.
 */
class JvmAesGcmCipher(
    private val secretKey: SecretKey = generateRandomAes256Key(),
    private val secureRandom: SecureRandom = SecureRandom()
) : ApiKeyCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(AesGcmWireCodec.IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AesGcmWireCodec.TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(AesGcmWireCodec.TAG_LENGTH_BITS, iv)
        )
        val ciphertext = cipher.doFinal(plaintext)
        return AesGcmWireCodec.pack(iv, ciphertext)
    }

    override fun decrypt(ivAndCiphertext: ByteArray): ByteArray =
        AesGcmWireCodec.decrypt(secretKey, ivAndCiphertext)

    companion object {
        fun generateRandomAes256Key(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

/**
 * Snapshot of the user's explicit AI opt-in, policy acknowledgement, BYOK key status, and usage counters.
 * AI features default to `aiEnabled = false` and `consentAccepted = false` per Section 9.5.
 */
data class AiConsentState(
    val aiEnabled: Boolean = false,
    val consentAccepted: Boolean = false,
    val consentAcceptedEpochMs: Long = 0L,
    val acceptedPolicyVersion: Int = 0,
    val hasConfiguredApiKey: Boolean = false,
    val maskedApiKeyPreview: String? = null,
    val selectedModelIdOverride: String? = null,
    val totalRequestsCount: Long = 0L,
    val totalPromptTokensEstimate: Long = 0L
) {
    val isReadyForUserInitiatedCall: Boolean
        get() = aiEnabled &&
            consentAccepted &&
            acceptedPolicyVersion >= AiConsentStore.CURRENT_CONSENT_POLICY_VERSION &&
            hasConfiguredApiKey

    override fun toString(): String =
        "AiConsentState(aiEnabled=$aiEnabled, consentAccepted=$consentAccepted, " +
            "hasConfiguredApiKey=$hasConfiguredApiKey, masked=${maskedApiKeyPreview ?: "none"}, " +
            "modelOverride=$selectedModelIdOverride, requests=$totalRequestsCount)"
}

/**
 * Stores explicit user consent (Section 9.5), AI enablement, BYOK model override, usage counters,
 * and AES-GCM-encrypted Gemini API key material outside backupable storage.
 */
class AiConsentStore(
    private val storage: AiSettingsStorage,
    private val cipher: ApiKeyCipher,
    private val clockNowMs: () -> Long = { System.currentTimeMillis() }
) {
    constructor(context: Context) : this(
        storage = DataStoreAiSettingsStorage(context.applicationContext),
        cipher = AndroidKeystoreAesGcmCipher()
    )

    val stateFlow: Flow<AiConsentState> = storage.preferencesFlow.map(::decodeState)

    suspend fun currentState(): AiConsentState = decodeState(storage.readAll())

    suspend fun recordConsentAccepted(enableAi: Boolean = true) {
        storage.write(
            mapOf(
                KEY_CONSENT_ACCEPTED to "true",
                KEY_CONSENT_EPOCH_MS to clockNowMs().toString(),
                KEY_CONSENT_POLICY_VERSION to CURRENT_CONSENT_POLICY_VERSION.toString(),
                KEY_AI_ENABLED to enableAi.toString()
            )
        )
    }

    suspend fun revokeConsentAndDisableAi(clearApiKey: Boolean = false) {
        val updates = mutableMapOf<String, String?>(
            KEY_AI_ENABLED to "false",
            KEY_CONSENT_ACCEPTED to "false",
            KEY_CONSENT_EPOCH_MS to "0",
            KEY_CONSENT_POLICY_VERSION to "0"
        )
        if (clearApiKey) {
            updates[KEY_ENCRYPTED_API_KEY_B64] = null
            updates[KEY_MASKED_API_KEY_PREVIEW] = null
        }
        storage.write(updates)
    }

    suspend fun setAiEnabled(enabled: Boolean) {
        if (enabled) {
            val state = currentState()
            require(
                state.consentAccepted &&
                    state.acceptedPolicyVersion >= CURRENT_CONSENT_POLICY_VERSION
            ) {
                "AI engedélyezése előtt a felhasználónak el kell fogadnia az adatvédelmi hozzájárulást."
            }
        }
        storage.write(mapOf(KEY_AI_ENABLED to enabled.toString()))
    }

    suspend fun saveApiKey(rawApiKey: String) {
        val clean = GeminiApiSupport.validateHeaderSafeApiKey(rawApiKey)
        val encrypted = cipher.encrypt(clean.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        storage.write(
            mapOf(
                KEY_ENCRYPTED_API_KEY_B64 to encoded,
                KEY_MASKED_API_KEY_PREVIEW to maskApiKey(clean)
            )
        )
    }

    /**
     * Decrypts the stored API key via [cipher]. On [GeneralSecurityException] (e.g., Keystore reset
     * or tampered payload), removes the invalid ciphertext and throws [KeystoreDecryptionException]
     * rather than falling back to plaintext.
     */
    suspend fun readDecryptedApiKey(): String? {
        val map = storage.readAll()
        val encoded = map[KEY_ENCRYPTED_API_KEY_B64] ?: return null
        val encrypted = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            clearApiKey()
            throw KeystoreDecryptionException("Érvénytelen Base64 kulcsformátum; az API-kulcs törölve.", e)
        }
        return try {
            val plainBytes = cipher.decrypt(encrypted)
            val key = GeminiApiSupport.sanitizeApiKey(String(plainBytes, StandardCharsets.UTF_8))
            plainBytes.fill(0)
            key.ifEmpty { null }
        } catch (e: GeneralSecurityException) {
            clearApiKey()
            if (e is KeystoreDecryptionException) throw e
            throw KeystoreDecryptionException(
                "Az API-kulcs visszafejtése sikertelen volt, ezért biztonsági okból törölve lett.",
                e
            )
        }
    }

    suspend fun clearApiKey() {
        storage.write(
            mapOf(
                KEY_ENCRYPTED_API_KEY_B64 to null,
                KEY_MASKED_API_KEY_PREVIEW to null
            )
        )
    }

    suspend fun setModelOverride(modelId: String?) {
        val clean = modelId?.trim()?.removePrefix("models/")?.takeIf { it.isNotEmpty() }
        storage.write(mapOf(KEY_MODEL_OVERRIDE to clean))
    }

    suspend fun incrementUsageCounters(requestsDelta: Int = 1, tokensDelta: Int = 0) {
        if (requestsDelta <= 0 && tokensDelta <= 0) return
        val current = storage.readAll()
        val prevReqs = current[KEY_TOTAL_REQUESTS]?.toLongOrNull() ?: 0L
        val prevTokens = current[KEY_TOTAL_TOKENS]?.toLongOrNull() ?: 0L
        storage.write(
            mapOf(
                KEY_TOTAL_REQUESTS to (prevReqs + requestsDelta.coerceAtLeast(0)).toString(),
                KEY_TOTAL_TOKENS to (prevTokens + tokensDelta.coerceAtLeast(0)).toString()
            )
        )
    }

    private fun decodeState(raw: Map<String, String>): AiConsentState {
        val encryptedPresent = !raw[KEY_ENCRYPTED_API_KEY_B64].isNullOrBlank()
        return AiConsentState(
            aiEnabled = raw[KEY_AI_ENABLED]?.toBooleanStrictOrNull() ?: false,
            consentAccepted = raw[KEY_CONSENT_ACCEPTED]?.toBooleanStrictOrNull() ?: false,
            consentAcceptedEpochMs = raw[KEY_CONSENT_EPOCH_MS]?.toLongOrNull() ?: 0L,
            acceptedPolicyVersion = raw[KEY_CONSENT_POLICY_VERSION]?.toIntOrNull() ?: 0,
            hasConfiguredApiKey = encryptedPresent,
            maskedApiKeyPreview = if (encryptedPresent) raw[KEY_MASKED_API_KEY_PREVIEW] else null,
            selectedModelIdOverride = raw[KEY_MODEL_OVERRIDE]?.takeIf { it.isNotBlank() },
            totalRequestsCount = raw[KEY_TOTAL_REQUESTS]?.toLongOrNull() ?: 0L,
            totalPromptTokensEstimate = raw[KEY_TOTAL_TOKENS]?.toLongOrNull() ?: 0L
        )
    }

    companion object {
        const val CURRENT_CONSENT_POLICY_VERSION = 1
        const val GEMINI_TERMS_URL = "https://ai.google.dev/gemini-api/terms"

        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_CONSENT_ACCEPTED = "consent_accepted"
        private const val KEY_CONSENT_EPOCH_MS = "consent_accepted_epoch_ms"
        private const val KEY_CONSENT_POLICY_VERSION = "consent_policy_version"
        private const val KEY_ENCRYPTED_API_KEY_B64 = "encrypted_api_key_b64"
        private const val KEY_MASKED_API_KEY_PREVIEW = "masked_api_key_preview"
        private const val KEY_MODEL_OVERRIDE = "selected_model_override"
        private const val KEY_TOTAL_REQUESTS = "total_requests_count"
        private const val KEY_TOTAL_TOKENS = "total_prompt_tokens_estimate"

        internal fun maskApiKey(rawKey: String): String {
            if (rawKey.length <= 8) return "••••••••"
            return "${rawKey.take(4)}••••${rawKey.takeLast(4)}"
        }
    }
}

