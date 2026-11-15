package hu.bdev.stylusnote.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin RFC-8259 JSON parser and string escaper for Gemini REST payloads so all catalog
 * and client code runs cleanly on both Android runtime and desktop JVM unit tests without `org.json`.
 */
internal object GeminiJsonCodec {

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parse(json: String): Any? = Parser(json).parseRoot()

    fun parseObject(json: String): Map<String, Any?>? = (parse(json) as? Map<*, *>)
        ?.entries
        ?.associate { (k, v) -> k.toString() to v }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            if (pos >= src.length) return null
            val result = parseValue()
            skipWhitespace()
            return result
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> parseObjectValue()
                '[' -> parseArrayValue()
                '"' -> parseStringValue()
                't', 'f' -> parseBooleanValue()
                'n' -> parseNullValue()
                '-', in '0'..'9' -> parseNumberValue()
                else -> throw IllegalArgumentException("Unexpected JSON token '${src[pos]}' at $pos")
            }
        }

        private fun parseObjectValue(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWhitespace()
                val key = parseStringValue()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (peek(',')) {
                    pos++
                } else if (peek('}')) {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' in JSON object at $pos")
                }
            }
            return map
        }

        private fun parseArrayValue(): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(parseValue())
                skipWhitespace()
                if (peek(',')) {
                    pos++
                } else if (peek(']')) {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' in JSON array at $pos")
                }
            }
            return list
        }

        private fun parseStringValue(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape at $pos")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) {
                                    throw IllegalArgumentException("Invalid unicode escape at $pos")
                                }
                                val code = src.substring(pos, pos + 4).toInt(16)
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseBooleanValue(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else if (src.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean literal at $pos")
            }
        }

        private fun parseNullValue(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null literal at $pos")
        }

        private fun parseNumberValue(): Number {
            val start = pos
            while (pos < src.length) {
                val ch = src[pos]
                if (ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                    pos++
                } else {
                    break
                }
            }
            val token = src.substring(start, pos)
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDouble()
            } else {
                token.toLong()
            }
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }

        private fun peek(expected: Char): Boolean = pos < src.length && src[pos] == expected

        private fun expect(expected: Char) {
            if (pos >= src.length || src[pos] != expected) {
                throw IllegalArgumentException("Expected '$expected' at index $pos")
            }
            pos++
        }
    }
}

data class GeminiModelInfo(
    val id: String,
    val displayName: String,
    val supportedGenerationMethods: Set<String>,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val isRetired: Boolean = false
) {
    val supportsGenerateContent: Boolean
        get() = supportedGenerationMethods.isEmpty() ||
            "generateContent" in supportedGenerationMethods

    val isFlashFamily: Boolean
        get() = id.contains("flash", ignoreCase = true)

    val isPreviewOrExperimental: Boolean
        get() = id.contains("preview", ignoreCase = true) ||
            id.contains("exp", ignoreCase = true)

    /** Filters out specialized non-JSON/text variants (TTS, native-audio, image-gen, live dialog) from auto failover. */
    val isSpecializedMediaVariant: Boolean
        get() = id.contains("-tts", ignoreCase = true) ||
            id.contains("audio", ignoreCase = true) ||
            id.contains("dialog", ignoreCase = true) ||
            id.contains("-live", ignoreCase = true) ||
            id.contains("-image", ignoreCase = true) ||
            id.contains("image-generation", ignoreCase = true)
}

data class ModelResolutionResult(
    val selectedModelId: String,
    val availableFlashModels: List<GeminiModelInfo>,
    val retiredModelNoticeHu: String? = null
)

class ModelCatalogException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Dynamic `/v1beta/models` catalog for selecting the active Gemini Flash model without hardcoding
 * deprecated model names (Section 0 & Section 2: handles `gemini-2.5-flash` retirement and 404).
 */
class ModelCatalog(
    private val httpClient: OkHttpClient = defaultCatalogHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clockNowMs: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
) {
    private val mutex = Mutex()
    private val dynamicallyRetiredIds = LinkedHashSet<String>()
    private var cachedModels: List<GeminiModelInfo> = emptyList()
    private var lastFetchedMs: Long = 0L

    suspend fun getCachedFlashModels(): List<GeminiModelInfo> = mutex.withLock {
        cachedModels.filter { it.isFlashFamily && it.supportsGenerateContent && !it.isRetired }
    }

    suspend fun refreshCatalog(apiKey: String): ModelResolutionResult {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotEmpty()) { "A modellkatalógus frissítéséhez API-kulcs szükséges." }

        val url = "${baseUrl.trimEnd('/')}/v1beta/models?pageSize=200"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanKey)
            .get()
            .build()

        val bodyString = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ModelCatalogException(
                        "A Gemini modellek lekérdezése sikertelen (HTTP ${response.code})."
                    )
                }
                rawBody
            }
        }

        val retiredSnapshot = mutex.withLock { dynamicallyRetiredIds.toSet() }
        val parsed = parseModelsListJson(bodyString, retiredSnapshot)
        mutex.withLock {
            cachedModels = parsed
            lastFetchedMs = clockNowMs()
        }
        return selectBestFlashModel(parsed, userOverrideId = null)
    }

    suspend fun resolveActiveFlashModel(
        apiKey: String,
        userOverrideId: String? = null,
        forceRefresh: Boolean = false
    ): ModelResolutionResult {
        val normalizedOverride = normalizeModelIdOrNull(userOverrideId)
        val snapshot = mutex.withLock {
            val isStale = cachedModels.isEmpty() || (clockNowMs() - lastFetchedMs) > cacheTtlMs
            Triple(cachedModels, isStale, dynamicallyRetiredIds.toSet())
        }

        val models = if (forceRefresh || snapshot.second) {
            try {
                refreshCatalog(apiKey)
                mutex.withLock { cachedModels }
            } catch (e: IOException) {
                if (snapshot.first.isNotEmpty()) snapshot.first else emptyList()
            }
        } else {
            snapshot.first
        }

        return selectBestFlashModel(models, normalizedOverride, snapshot.third)
    }

    /**
     * Builds an ordered deduplicated failover chain of Flash models for Automatic / resilient
     * execution. Never throws on offline or slow `/v1beta/models` catalog fetches.
     */
    suspend fun buildCandidateFlashModelChain(
        apiKey: String,
        userOverrideId: String? = null,
        forceRefresh: Boolean = false
    ): Pair<List<String>, String?> {
        val resolution = resolveActiveFlashModel(
            apiKey = apiKey,
            userOverrideId = userOverrideId,
            forceRefresh = forceRefresh
        )
        val retiredSnapshot = mutex.withLock { dynamicallyRetiredIds.toSet() }
        val allRetired = (KNOWN_RETIRED_MODEL_IDS + retiredSnapshot).map(::normalizeModelId).toSet()

        val chain = LinkedHashSet<String>()
        if (resolution.selectedModelId.isNotBlank() && resolution.selectedModelId !in allRetired) {
            chain += resolution.selectedModelId
        }
        for (discovered in resolution.availableFlashModels) {
            if (!discovered.isRetired &&
                !discovered.isSpecializedMediaVariant &&
                discovered.id !in allRetired
            ) {
                chain += discovered.id
            }
        }
        for (fallback in FALLBACK_FLASH_MODEL_CHAIN) {
            val norm = normalizeModelId(fallback)
            if (norm !in allRetired) {
                chain += norm
            }
        }
        return chain.toList() to resolution.retiredModelNoticeHu
    }

    suspend fun markModelRetiredLocally(modelId: String) {
        val normalized = normalizeModelId(modelId)
        mutex.withLock {
            dynamicallyRetiredIds += normalized
            cachedModels = cachedModels.map { m ->
                if (m.id.equals(normalized, ignoreCase = true)) m.copy(isRetired = true) else m
            }
        }
    }

    /**
     * Records a 404 model retirement, clears any matching stale user override in [aiConsentStore],
     * refreshes `/v1beta/models` if possible, and picks the best surviving Flash model.
     */
    suspend fun onModelNotFound(
        retiredModelId: String,
        apiKey: String,
        aiConsentStore: AiConsentStore? = null
    ): ModelResolutionResult {
        val normalizedRetired = normalizeModelId(retiredModelId)
        mutex.withLock {
            dynamicallyRetiredIds += normalizedRetired
            cachedModels = cachedModels.map { model ->
                if (model.id.equals(normalizedRetired, ignoreCase = true)) {
                    model.copy(isRetired = true)
                } else {
                    model
                }
            }
        }

        val currentOverride = aiConsentStore?.currentState()?.selectedModelIdOverride
        if (currentOverride != null &&
            normalizeModelId(currentOverride).equals(normalizedRetired, ignoreCase = true)
        ) {
            aiConsentStore.setModelOverride(null)
        }

        val resolution = try {
            refreshCatalog(apiKey)
        } catch (_: IOException) {
            val (modelsSnapshot, retiredSnapshot) = mutex.withLock {
                cachedModels to dynamicallyRetiredIds.toSet()
            }
            selectBestFlashModel(modelsSnapshot, null, retiredSnapshot)
        }

        val notice =
            "A(z) '$normalizedRetired' modell megszűnt vagy nem elérhető (HTTP 404). " +
                "Automatikus váltás erre: '${resolution.selectedModelId}'."
        return resolution.copy(retiredModelNoticeHu = notice)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_FALLBACK_FLASH_MODEL = "gemini-2.5-flash"
        const val DEFAULT_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        val FALLBACK_FLASH_MODEL_CHAIN: List<String> = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.5-flash-lite",
            "gemini-1.5-flash"
        )

        /** Legacy genuinely shut-down Gemini 1.0 model IDs. Active 1.5/2.0/2.5 Flash models remain enabled for auto failover. */
        val KNOWN_RETIRED_MODEL_IDS: Set<String> = setOf(
            "gemini-1.0-pro",
            "gemini-1.0-pro-001",
            "gemini-1.0-pro-vision",
            "gemini-pro-vision"
        )

        internal fun defaultCatalogHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        fun normalizeModelId(raw: String): String =
            raw.trim().removePrefix("models/").lowercase()

        private fun normalizeModelIdOrNull(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeModelId)

        fun parseModelsListJson(
            json: String,
            dynamicallyRetiredIds: Set<String> = emptySet()
        ): List<GeminiModelInfo> {
            val root = GeminiJsonCodec.parseObject(json) ?: return emptyList()
            val modelsList = root["models"] as? List<*> ?: return emptyList()
            val retiredLower = (KNOWN_RETIRED_MODEL_IDS + dynamicallyRetiredIds)
                .map { normalizeModelId(it) }
                .toSet()

            return modelsList.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val rawName = obj["name"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val id = normalizeModelId(rawName)
                val displayName = obj["displayName"]?.toString().orEmpty().ifBlank { id }
                val methods = (obj["supportedGenerationMethods"] as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?.toSet()
                    ?: emptySet()
                val inputLimit = (obj["inputTokenLimit"] as? Number)?.toInt()
                val outputLimit = (obj["outputTokenLimit"] as? Number)?.toInt()
                val retiredFlag = id in retiredLower
                GeminiModelInfo(
                    id = id,
                    displayName = displayName,
                    supportedGenerationMethods = methods,
                    inputTokenLimit = inputLimit,
                    outputTokenLimit = outputLimit,
                    isRetired = retiredFlag
                )
            }
        }

        fun selectBestFlashModel(
            models: List<GeminiModelInfo>,
            userOverrideId: String?,
            dynamicallyRetiredIds: Set<String> = emptySet()
        ): ModelResolutionResult {
            val allRetired = (KNOWN_RETIRED_MODEL_IDS + dynamicallyRetiredIds)
                .map { normalizeModelId(it) }
                .toSet()

            val usableFlashModels = models
                .filter { model ->
                    !model.isRetired &&
                        model.id !in allRetired &&
                        model.isFlashFamily &&
                        model.supportsGenerateContent &&
                        !model.isSpecializedMediaVariant
                }
                .sortedWith(FLASH_RANK_COMPARATOR)

            val bestAutoId = usableFlashModels.firstOrNull()?.id
                ?: DEFAULT_FALLBACK_FLASH_MODEL.takeIf { it !in allRetired }
                ?: "gemini-2.5-flash"

            val overrideId = normalizeModelIdOrNull(userOverrideId)
            if (overrideId != null) {
                if (overrideId in allRetired || models.any { it.id == overrideId && it.isRetired }) {
                    val notice =
                        "A kiválasztott '$overrideId' modell elavult/megszűnt. Automatikus váltás erre: '$bestAutoId'."
                    return ModelResolutionResult(
                        selectedModelId = bestAutoId,
                        availableFlashModels = usableFlashModels,
                        retiredModelNoticeHu = notice
                    )
                }
                return ModelResolutionResult(
                    selectedModelId = overrideId,
                    availableFlashModels = usableFlashModels,
                    retiredModelNoticeHu = null
                )
            }

            return ModelResolutionResult(
                selectedModelId = bestAutoId,
                availableFlashModels = usableFlashModels,
                retiredModelNoticeHu = null
            )
        }

        private val VERSION_REGEX = Regex("""gemini-(\d+)(?:\.(\d+))?""")

        private fun parseVersionPair(modelId: String): Pair<Int, Int> {
            val match = VERSION_REGEX.find(modelId) ?: return 0 to 0
            val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return major to minor
        }

        /** Higher version wins; for the same version, stable beats `-preview`/`-exp`, non-lite beats `-lite`. */
        private val FLASH_RANK_COMPARATOR = Comparator<GeminiModelInfo> { a, b ->
            val (majA, minA) = parseVersionPair(a.id)
            val (majB, minB) = parseVersionPair(b.id)
            if (majA != majB) return@Comparator majB.compareTo(majA)
            if (minA != minB) return@Comparator minB.compareTo(minA)

            val previewA = a.isPreviewOrExperimental
            val previewB = b.isPreviewOrExperimental
            if (previewA != previewB) return@Comparator if (previewA) 1 else -1

            val liteA = a.id.contains("-lite", ignoreCase = true)
            val liteB = b.id.contains("-lite", ignoreCase = true)
            if (liteA != liteB) return@Comparator if (liteA) 1 else -1

            b.id.compareTo(a.id)
        }
    }
}

