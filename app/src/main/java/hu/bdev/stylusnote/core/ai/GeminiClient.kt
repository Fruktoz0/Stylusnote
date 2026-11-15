package hu.bdev.stylusnote.core.ai

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Explicit user actions allowed to invoke the Gemini API (Section 7 & Section 9.5).
 * Background code (such as offline OCR) must never initiate remote calls.
 */
enum class UserInitiatedAiTrigger {
    LASSO_TO_LATEX,
    LASSO_AUTO_FORMAT,
    STEP_BY_STEP_DERIVATION,
    HANDWRITING_TO_MARKDOWN,
    MAGIC_CIRCLE_EXPLAIN,
    EXAM_MODE_GENERATION,
    MANUAL_MODEL_REFRESH
}

class AiConsentRequiredException(message: String) : IllegalStateException(message)

class MissingApiKeyException(message: String) : IllegalStateException(message)

class GeminiRateLimitExceededException(
    val retryAfterMs: Long,
    message: String
) : IOException(message)

class GeminiModelRetiredException(
    val retiredModelId: String,
    val replacementModelId: String?,
    message: String
) : IOException(message)

class GeminiHttpException(
    val statusCode: Int,
    message: String
) : IOException(message)

data class GeminiResponse(
    val text: String,
    val modelIdUsed: String,
    val modelSwitchedNoticeHu: String? = null,
    val promptTokensEstimate: Int = 0
)

/**
 * Central Gemini REST client enforcing:
 * 1. Explicit [AiConsentStore] opt-in & BYOK key verification (Section 9.5)
 * 2. Client-side sliding-window + minimum-interval rate limiting (Section 2 & 7)
 * 3. Dynamic [ModelCatalog] resolution with HTTP 404 retired-model switch + notification
 * 4. HTTP 429 `Retry-After`-aware exponential backoff
 * 5. Inline PNG image scaling (`1024–2048 px`) and pure-Kotlin JSON payload/response handling.
 */
class GeminiClient(
    private val consentStore: AiConsentStore,
    private val modelCatalog: ModelCatalog = ModelCatalog(),
    private val httpClient: OkHttpClient = defaultGeminiHttpClient(),
    private val baseUrl: String = ModelCatalog.DEFAULT_BASE_URL,
    private val minRequestIntervalMs: Long = DEFAULT_MIN_REQUEST_INTERVAL_MS,
    private val maxRequestsPerMinute: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
    private val maxRetriesOn429: Int = DEFAULT_MAX_RETRIES_ON_429,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val clockNowMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { ms -> if (ms > 0L) delay(ms) }
) {
    private val rateLimiterMutex = Mutex()
    private val recentCallTimestampsMs = ArrayDeque<Long>()
    private var lastCallTimestampMs: Long = 0L

    /**
     * Executes a user-initiated Gemini `generateContent` call after verifying explicit opt-in
     * consent, BYOK API key decryption, and rate limits.
     */
    suspend fun generateContent(
        trigger: UserInitiatedAiTrigger,
        promptText: String,
        inlinePngImages: List<ByteArray> = emptyList(),
        responseMimeType: String? = null,
        responseSchemaJson: String? = null,
        systemInstruction: String? = null,
        temperature: Float? = null,
        thinkingBudget: Int? = null
    ): GeminiResponse {
        require(promptText.isNotBlank() || inlinePngImages.isNotEmpty()) {
            "A Gemini kéréshez szöveges utasítás vagy kép szükséges."
        }

        val state = consentStore.currentState()
        if (!state.consentAccepted || state.acceptedPolicyVersion < AiConsentStore.CURRENT_CONSENT_POLICY_VERSION) {
            throw AiConsentRequiredException(
                "Az AI funkciók ($trigger) használatához el kell fogadnod az adatvédelmi hozzájárulást."
            )
        }
        if (!state.aiEnabled) {
            throw AiConsentRequiredException(
                "Az AI Copilot jelenleg ki van kapcsolva a beállításokban."
            )
        }
        val apiKey = consentStore.readDecryptedApiKey()
            ?: throw MissingApiKeyException(
                "Nincs beállítva saját Google AI Studio (BYOK) API-kulcs."
            )

        val (initialCandidates, initialNotice) = modelCatalog.buildCandidateFlashModelChain(
            apiKey = apiKey,
            userOverrideId = state.selectedModelIdOverride
        )
        val candidateQueue = ArrayDeque<String>(initialCandidates)
        val triedModels = LinkedHashSet<String>()
        var switchNotice = initialNotice
        var catalogRefreshedOnFailure = false
        var firstFailedModelId: String? = null
        var lastFailure: Throwable? = null

        while (candidateQueue.isNotEmpty()) {
            val activeModelId = candidateQueue.removeFirst()
            if (!triedModels.add(activeModelId)) continue

            var attempt429 = 0
            var omitThinkingConfig = false
            while (true) {
                val activeThinkingBudget = if (omitThinkingConfig) {
                    null
                } else {
                    resolveEffectiveThinkingBudget(activeModelId, thinkingBudget)
                }
                val payloadJson = buildGenerateContentRequestJson(
                    promptText = promptText,
                    inlinePngImages = inlinePngImages,
                    responseMimeType = responseMimeType,
                    responseSchemaJson = responseSchemaJson,
                    systemInstruction = systemInstruction,
                    temperature = temperature,
                    thinkingBudget = activeThinkingBudget
                )
                acquireRateLimitSlot()

                val endpoint = "${baseUrl.trimEnd('/')}/v1beta/models/$activeModelId:generateContent"
                val request = Request.Builder()
                    .url(endpoint)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val result = try {
                    withContext(Dispatchers.IO) {
                        httpClient.newCall(request).execute().use { response ->
                            val statusCode = response.code
                            val retryAfterHeader = response.header("Retry-After")
                            val bodyText = response.body?.string().orEmpty()
                            HttpCallOutcome(statusCode, retryAfterHeader, bodyText)
                        }
                    }
                } catch (ioe: IOException) {
                    lastFailure = ioe
                    if (firstFailedModelId == null) firstFailedModelId = activeModelId
                    break
                }

                when {
                    result.statusCode in 200..299 -> {
                        val parsed = try {
                            parseGenerateContentResponse(result.bodyText, promptText, inlinePngImages).also {
                                if (it.text.isBlank()) {
                                    throw GeminiHttpException(502, "Üres AI szövegválasz érkezett ($activeModelId).")
                                }
                            }
                        } catch (parseErr: GeminiHttpException) {
                            lastFailure = parseErr
                            if (firstFailedModelId == null) firstFailedModelId = activeModelId
                            break
                        }

                        consentStore.incrementUsageCounters(
                            requestsDelta = 1,
                            tokensDelta = parsed.promptTokensEstimate
                        )
                        val resolvedNotice = switchNotice ?: firstFailedModelId?.let { failed ->
                            "Automatikus modellváltás ($failed → $activeModelId)."
                        }
                        return GeminiResponse(
                            text = parsed.text,
                            modelIdUsed = activeModelId,
                            modelSwitchedNoticeHu = resolvedNotice,
                            promptTokensEstimate = parsed.promptTokensEstimate
                        )
                    }

                    result.statusCode == 404 -> {
                        modelCatalog.markModelRetiredLocally(activeModelId)
                        if (firstFailedModelId == null) firstFailedModelId = activeModelId
                        if (!catalogRefreshedOnFailure) {
                            catalogRefreshedOnFailure = true
                            val updated = runCatching {
                                modelCatalog.onModelNotFound(
                                    retiredModelId = activeModelId,
                                    apiKey = apiKey,
                                    aiConsentStore = consentStore
                                )
                            }.getOrNull()
                            if (updated != null) {
                                switchNotice = updated.retiredModelNoticeHu ?: switchNotice
                                // Prepend newly discovered active models so the refreshed catalog candidate runs next.
                                val freshIds = buildList {
                                    if (updated.selectedModelId !in triedModels) add(updated.selectedModelId)
                                    updated.availableFlashModels.forEach { info ->
                                        if (!info.isRetired && !info.isSpecializedMediaVariant && info.id !in triedModels) {
                                            add(info.id)
                                        }
                                    }
                                }.distinct()
                                for (id in freshIds.asReversed()) {
                                    candidateQueue.remove(id)
                                    candidateQueue.addFirst(id)
                                }
                            }
                        }
                        lastFailure = GeminiModelRetiredException(
                            retiredModelId = activeModelId,
                            replacementModelId = candidateQueue.firstOrNull(),
                            message = "A(z) '$activeModelId' modell nem érhető el (HTTP 404)."
                        )
                        break
                    }

                    result.statusCode == 429 -> {
                        val waitMs = computeRetryDelayMs(result.retryAfterHeader, attempt429)
                        if (attempt429 < maxRetriesOn429) {
                            attempt429++
                            delayMs(waitMs)
                            continue
                        }
                        val err = GeminiRateLimitExceededException(
                            retryAfterMs = waitMs,
                            message = "Gemini API kvóta / rate limit túllépve (HTTP 429). Próbáld újra ${waitMs / 1000} mp múlva."
                        )
                        lastFailure = err
                        if (firstFailedModelId == null) firstFailedModelId = activeModelId
                        break
                    }

                    result.statusCode == 400 &&
                        !omitThinkingConfig &&
                        activeThinkingBudget != null &&
                        isThinkingConfigRejectedError(result.bodyText) -> {
                        omitThinkingConfig = true
                        continue
                    }

                    result.statusCode == 401 || result.statusCode == 403 -> {
                        throw GeminiHttpException(
                            result.statusCode,
                            "Érvénytelen vagy jogosulatlan Gemini API-kulcs (HTTP ${result.statusCode})."
                        )
                    }

                    else -> {
                        lastFailure = GeminiHttpException(
                            result.statusCode,
                            "Gemini API hiba ($activeModelId, HTTP ${result.statusCode}): ${extractApiErrorMessage(result.bodyText)}"
                        )
                        if (firstFailedModelId == null) firstFailedModelId = activeModelId
                        break
                    }
                }
            }
        }

        throw lastFailure ?: GeminiHttpException(
            503,
            "Egyik elérhető Gemini Flash modell sem válaszolt sikeresen."
        )
    }

    private suspend fun acquireRateLimitSlot() {
        var pendingWaitMs = 0L
        rateLimiterMutex.withLock {
            val now = clockNowMs()
            while (recentCallTimestampsMs.isNotEmpty() && now - recentCallTimestampsMs.first() >= 60_000L) {
                recentCallTimestampsMs.removeFirst()
            }

            val intervalWait = if (lastCallTimestampMs > 0L) {
                (lastCallTimestampMs + minRequestIntervalMs) - now
            } else {
                0L
            }
            val windowWait = if (recentCallTimestampsMs.size >= maxRequestsPerMinute) {
                (recentCallTimestampsMs.first() + 60_000L) - now
            } else {
                0L
            }
            pendingWaitMs = max(0L, max(intervalWait, windowWait))
        }

        if (pendingWaitMs > 0L) {
            delayMs(pendingWaitMs)
        }

        rateLimiterMutex.withLock {
            val now = clockNowMs()
            while (recentCallTimestampsMs.isNotEmpty() && now - recentCallTimestampsMs.first() >= 60_000L) {
                recentCallTimestampsMs.removeFirst()
            }
            lastCallTimestampMs = now
            recentCallTimestampsMs.addLast(now)
        }
    }

    private fun computeRetryDelayMs(retryAfterHeader: String?, attemptIndex: Int): Long {
        val headerMs = parseRetryAfterMs(retryAfterHeader)
        val expMs = (baseBackoffMs shl attemptIndex.coerceAtMost(6)).coerceAtMost(maxBackoffMs)
        return max(headerMs ?: 0L, expMs).coerceAtMost(maxBackoffMs)
    }

    private data class HttpCallOutcome(
        val statusCode: Int,
        val retryAfterHeader: String?,
        val bodyText: String
    )

    private data class ParsedCandidate(
        val text: String,
        val promptTokensEstimate: Int
    )

    private fun parseGenerateContentResponse(
        bodyJson: String,
        promptText: String,
        images: List<ByteArray>
    ): ParsedCandidate {
        val root = GeminiJsonCodec.parseObject(bodyJson)
            ?: throw GeminiHttpException(500, "Üres vagy érvénytelen Gemini JSON válasz.")
        val candidates = root["candidates"] as? List<*>
            ?: throw GeminiHttpException(500, "A Gemini válasz nem tartalmaz 'candidates' mezőt.")
        val firstCandidate = candidates.firstOrNull() as? Map<*, *>
            ?: throw GeminiHttpException(500, "A Gemini válasz üres jelöltlistát adott vissza.")
        val content = firstCandidate["content"] as? Map<*, *>
        val parts = content?.get("parts") as? List<*>
        val text = parts
            ?.mapNotNull { part -> (part as? Map<*, *>)?.get("text")?.toString() }
            ?.joinToString(separator = "")
            .orEmpty()

        val usageMetadata = root["usageMetadata"] as? Map<*, *>
        val reportedPromptTokens = (usageMetadata?.get("promptTokenCount") as? Number)?.toInt()
        val fallbackEstimate = (promptText.length / 4).coerceAtLeast(1) + images.size * 258
        return ParsedCandidate(
            text = text,
            promptTokensEstimate = reportedPromptTokens ?: fallbackEstimate
        )
    }

    private fun extractApiErrorMessage(bodyJson: String): String {
        val root = runCatching { GeminiJsonCodec.parseObject(bodyJson) }.getOrNull()
        val errorObj = root?.get("error") as? Map<*, *>
        return errorObj?.get("message")?.toString()?.takeIf { it.isNotBlank() }
            ?: bodyJson.take(240).ifBlank { "Ismeretlen hiba" }
    }

    companion object {
        const val DEFAULT_MIN_REQUEST_INTERVAL_MS = 400L
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 15
        const val DEFAULT_MAX_RETRIES_ON_429 = 3
        const val DEFAULT_BASE_BACKOFF_MS = 1_500L
        const val DEFAULT_MAX_BACKOFF_MS = 60_000L

        internal fun defaultGeminiHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()

        const val DEFAULT_INLINE_IMAGE_LONG_EDGE_PX = 1536
        const val PAGE_FORMAT_IMAGE_EDGE_PX = 1024
        const val DEFAULT_THINKING_BUDGET_DISABLED = 0
        const val MIN_INLINE_IMAGE_LONG_EDGE_PX = 512
        const val MAX_INLINE_IMAGE_LONG_EDGE_PX = 2048

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun parseRetryAfterMs(headerValue: String?): Long? {
            val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
            if (seconds <= 0L) return null
            return seconds * 1_000L
        }

        /**
         * Encodes [bitmap] as a canonical PNG with its longest edge bounded to `targetLongEdgePx`
         * (`1024..2048 px` per Section 2). Does not alter aspect ratio or recycle caller's [bitmap].
         */
        fun prepareInlinePngBytes(
            bitmap: Bitmap,
            targetLongEdgePx: Int = DEFAULT_INLINE_IMAGE_LONG_EDGE_PX
        ): ByteArray {
            val boundedTarget = targetLongEdgePx.coerceIn(
                MIN_INLINE_IMAGE_LONG_EDGE_PX,
                MAX_INLINE_IMAGE_LONG_EDGE_PX
            )
            val width = bitmap.width.coerceAtLeast(1)
            val height = bitmap.height.coerceAtLeast(1)
            val longEdge = max(width, height)

            var scaledBitmap: Bitmap? = null
            val bitmapToCompress = if (longEdge > boundedTarget) {
                val scale = boundedTarget.toFloat() / longEdge.toFloat()
                val dstW = (width * scale).roundToInt().coerceAtLeast(1)
                val dstH = (height * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, dstW, dstH, true).also { scaledBitmap = it }
            } else {
                bitmap
            }

            return try {
                ByteArrayOutputStream().use { out ->
                    bitmapToCompress.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            } finally {
                scaledBitmap?.let { created ->
                    if (created !== bitmap && !created.isRecycled) {
                        created.recycle()
                    }
                }
            }
        }

        /** Strips optional Markdown code fences (e.g. ```` ```json ```` or ```` ```latex ````) returned by LLMs. */
        fun stripMarkdownCodeFences(rawText: String): String {
            var trimmed = rawText.trim()
            if (trimmed.startsWith("```")) {
                val firstNewline = trimmed.indexOf('\n')
                trimmed = if (firstNewline >= 0) {
                    trimmed.substring(firstNewline + 1)
                } else {
                    trimmed.removePrefix("```")
                }
                val closingFence = trimmed.lastIndexOf("```")
                if (closingFence >= 0) {
                    trimmed = trimmed.substring(0, closingFence)
                }
            }
            return trimmed.trim()
        }

        /**
         * Extracts a single string property (such as `"latex"`) from raw or Markdown-fenced JSON
         * without relying on Android `org.json.JSONObject`.
         */
        fun extractJsonStringField(rawOrFencedJson: String, fieldName: String): String? {
            val cleaned = stripMarkdownCodeFences(rawOrFencedJson)
            val parsed = runCatching { GeminiJsonCodec.parseObject(cleaned) }.getOrNull()
                ?: return null
            return parsed[fieldName]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

        internal fun resolveEffectiveThinkingBudget(
            modelId: String,
            explicitBudget: Int? = null
        ): Int? {
            if (explicitBudget != null) return explicitBudget
            val normalized = modelId.lowercase()
            return if (normalized.contains("gemini-2.5") && !normalized.contains("pro")) {
                DEFAULT_THINKING_BUDGET_DISABLED
            } else {
                null
            }
        }

        internal fun isThinkingConfigRejectedError(bodyJson: String): Boolean {
            if (bodyJson.isBlank()) return false
            val lower = bodyJson.lowercase()
            return lower.contains("thinking") || lower.contains("thinking_budget") || lower.contains("thinkingconfig")
        }

        internal fun buildGenerateContentRequestJson(
            promptText: String,
            inlinePngImages: List<ByteArray>,
            responseMimeType: String?,
            responseSchemaJson: String?,
            systemInstruction: String?,
            temperature: Float?,
            thinkingBudget: Int? = null
        ): String {
            val sb = StringBuilder(256)
            sb.append('{')

            if (!systemInstruction.isNullOrBlank()) {
                sb.append("\"systemInstruction\":{\"parts\":[{\"text\":")
                sb.append(GeminiJsonCodec.quote(systemInstruction))
                sb.append("}]},")
            }

            sb.append("\"contents\":[{\"role\":\"user\",\"parts\":[")
            var hasPart = false
            if (promptText.isNotEmpty()) {
                sb.append("{\"text\":")
                sb.append(GeminiJsonCodec.quote(promptText))
                sb.append('}')
                hasPart = true
            }
            for (pngBytes in inlinePngImages) {
                if (hasPart) sb.append(',')
                val b64 = Base64.getEncoder().encodeToString(pngBytes)
                sb.append("{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":")
                sb.append(GeminiJsonCodec.quote(b64))
                sb.append("}}")
                hasPart = true
            }
            sb.append("]}]")

            val hasGenConfig = !responseMimeType.isNullOrBlank() ||
                !responseSchemaJson.isNullOrBlank() ||
                temperature != null ||
                thinkingBudget != null
            if (hasGenConfig) {
                sb.append(",\"generationConfig\":{")
                var needComma = false
                if (!responseMimeType.isNullOrBlank()) {
                    sb.append("\"responseMimeType\":")
                    sb.append(GeminiJsonCodec.quote(responseMimeType))
                    needComma = true
                }
                if (!responseSchemaJson.isNullOrBlank()) {
                    if (needComma) sb.append(',')
                    sb.append("\"responseSchema\":")
                    sb.append(responseSchemaJson.trim())
                    needComma = true
                }
                if (temperature != null) {
                    if (needComma) sb.append(',')
                    sb.append("\"temperature\":")
                    sb.append(temperature)
                    needComma = true
                }
                if (thinkingBudget != null) {
                    if (needComma) sb.append(',')
                    sb.append("\"thinkingConfig\":{\"thinkingBudget\":")
                    sb.append(thinkingBudget)
                    sb.append('}')
                }
                sb.append('}')
            }

            sb.append('}')
            return sb.toString()
        }
    }
}

