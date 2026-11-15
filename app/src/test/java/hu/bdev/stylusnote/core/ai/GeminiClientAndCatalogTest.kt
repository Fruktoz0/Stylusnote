package hu.bdev.stylusnote.core.ai

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Base64

class GeminiClientAndCatalogTest {

    @Test
    fun aiConsentStoreBlocksCallsUntilConsentAndEncryptsApiKeyWithAesGcm() = runBlocking {
        val storage = InMemoryAiSettingsStorage()
        val cipher = JvmAesGcmCipher()
        val consentStore = AiConsentStore(storage = storage, cipher = cipher, clockNowMs = { 1_700_000_000_000L })

        val initial = consentStore.currentState()
        assertFalse(initial.aiEnabled)
        assertFalse(initial.consentAccepted)
        assertFalse(initial.isReadyForUserInitiatedCall)

        val blockedClient = GeminiClient(
            consentStore = consentStore,
            httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                fail("Network must never be called before explicit consent: ${chain.request().url}")
                error("unreachable")
            }.build()
        )

        try {
            blockedClient.generateContent(
                trigger = UserInitiatedAiTrigger.LASSO_TO_LATEX,
                promptText = "Convert to LaTeX"
            )
            fail("Expected AiConsentRequiredException before consent acceptance")
        } catch (_: AiConsentRequiredException) {
            // Expected gate behavior per Section 9.5
        }

        val rawSecret = "AIzaSyTestSecretKey1234567890"
        consentStore.saveApiKey(rawSecret)
        consentStore.recordConsentAccepted(enableAi = true)

        val storedMap = storage.readAll()
        val encryptedB64 = storedMap["encrypted_api_key_b64"]
        assertNotNull(encryptedB64)
        assertFalse("Ciphertext must not leak plaintext secret", encryptedB64!!.contains(rawSecret))
        assertEquals(rawSecret, consentStore.readDecryptedApiKey())

        val readyState = consentStore.currentState()
        assertTrue(readyState.isReadyForUserInitiatedCall)
        assertEquals("AIza••••7890", readyState.maskedApiKeyPreview)
        assertFalse("State toString must mask secret", readyState.toString().contains(rawSecret))

        // Tamper the ciphertext tag/payload and verify strict GCM rejection + key wipe
        val decodedBytes = Base64.getDecoder().decode(encryptedB64)
        decodedBytes[decodedBytes.lastIndex] = (decodedBytes.last().toInt() xor 0xFF).toByte()
        storage.write(mapOf("encrypted_api_key_b64" to Base64.getEncoder().encodeToString(decodedBytes)))

        try {
            consentStore.readDecryptedApiKey()
            fail("Expected KeystoreDecryptionException on tampered ciphertext")
        } catch (_: KeystoreDecryptionException) {
            // Expected
        }
        assertNull(storage.readAll()["encrypted_api_key_b64"])
        assertFalse(consentStore.currentState().hasConfiguredApiKey)
    }

    @Test
    fun modelCatalogPrioritizesActiveFlashModelsAndExcludesSpecializedOrLegacyVariants() {
        val sampleModelsJson = """
            {
              "models": [
                {
                  "name": "models/gemini-1.0-pro",
                  "displayName": "Gemini 1.0 Pro",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.0-flash",
                  "displayName": "Gemini 2.0 Flash",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.5-flash-preview-tts",
                  "displayName": "Gemini 2.5 Flash TTS",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.5-pro",
                  "displayName": "Gemini 2.5 Pro",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val parsed = ModelCatalog.parseModelsListJson(sampleModelsJson)
        val retired10 = parsed.first { it.id == "gemini-1.0-pro" }
        assertTrue("gemini-1.0-pro must be flagged as retired", retired10.isRetired)

        val autoSelection = ModelCatalog.selectBestFlashModel(parsed, userOverrideId = null)
        assertEquals("gemini-2.5-flash", autoSelection.selectedModelId)
        assertNull(autoSelection.retiredModelNoticeHu)
        assertTrue(autoSelection.availableFlashModels.any { it.id == "gemini-2.5-flash" })
        assertTrue(autoSelection.availableFlashModels.any { it.id == "gemini-2.0-flash" })
        assertFalse(autoSelection.availableFlashModels.any { it.id == "gemini-2.5-flash-preview-tts" })
        assertFalse(autoSelection.availableFlashModels.any { it.id == "gemini-2.5-pro" })

        val overrideRetired = ModelCatalog.selectBestFlashModel(
            models = parsed,
            userOverrideId = "models/gemini-1.0-pro"
        )
        assertEquals("gemini-2.5-flash", overrideRetired.selectedModelId)
        assertNotNull(overrideRetired.retiredModelNoticeHu)
    }

    @Test
    fun automaticModeFailsOverAcrossTimeoutAnd503ToNextFlashModel() = runBlocking {
        val storage = InMemoryAiSettingsStorage()
        val consentStore = AiConsentStore(storage, JvmAesGcmCipher(), clockNowMs = { 10_000L })
        consentStore.saveApiKey("AIzaSyFailoverTestKey")
        consentStore.recordConsentAccepted(enableAi = true)

        var nowMs = 2_000_000L
        val attemptedModels = mutableListOf<String>()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            if (path.endsWith("/v1beta/models")) {
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Catalog temporary unavailable")
                    .body("""{"error":{"message":"unavailable"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }

            attemptedModels += path
            when {
                path.contains("gemini-2.5-flash:") -> throw SocketTimeoutException("Simulated 60s timeout")
                path.contains("gemini-2.0-flash") -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Overloaded")
                    .body("""{"error":{"message":"Model overloaded"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                else -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"candidates":[{"content":{"parts":[{"text":"OK válasz"}]}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val catalog = ModelCatalog(httpClient = httpClient, clockNowMs = { nowMs })
        val client = GeminiClient(
            consentStore = consentStore,
            modelCatalog = catalog,
            httpClient = httpClient,
            minRequestIntervalMs = 0L,
            clockNowMs = { nowMs },
            delayMs = { ms -> nowMs += ms }
        )

        val response = client.generateContent(
            trigger = UserInitiatedAiTrigger.HANDWRITING_TO_MARKDOWN,
            promptText = "Format text"
        )

        assertEquals("OK válasz", response.text)
        assertEquals("gemini-2.5-flash-lite", response.modelIdUsed)
        assertNotNull(response.modelSwitchedNoticeHu)
        assertTrue(attemptedModels.size == 3)
    }

    @Test
    fun geminiClientHandles404ModelRefreshAnd429RetryAfterBackoff() = runBlocking {
        val storage = InMemoryAiSettingsStorage()
        val consentStore = AiConsentStore(storage, JvmAesGcmCipher(), clockNowMs = { 10_000L })
        consentStore.saveApiKey("AIzaSyFakeTestKey9999")
        consentStore.recordConsentAccepted(enableAi = true)
        consentStore.setModelOverride("gemini-3.2-flash")

        val recordedDelays = mutableListOf<Long>()
        val requestedUrls = mutableListOf<String>()
        var nowMs = 1_000_000L

        var callStep = 0
        val fakeInterceptor = Interceptor { chain ->
            val req = chain.request()
            requestedUrls += "${req.method} ${req.url.encodedPath}"
            callStep++

            val (code, retryAfter, jsonBody) = when (callStep) {
                1 -> {
                    // Cold catalog fetch before first generateContent call
                    Triple(
                        200,
                        null,
                        """{"models":[{"name":"models/gemini-3.2-flash","supportedGenerationMethods":["generateContent"]}]}"""
                    )
                }
                2 -> {
                    // First generateContent hits 404 (gemini-3.2-flash retired at runtime)
                    Triple(404, null, """{"error":{"message":"Model not found"}}""")
                }
                3 -> {
                    // ModelCatalog.onModelNotFound refreshes /v1beta/models
                    Triple(
                        200,
                        null,
                        """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}]}"""
                    )
                }
                4 -> {
                    // Retried generateContent on gemini-3.8-flash hits HTTP 429 with Retry-After: 4
                    Triple(429, "4", """{"error":{"message":"Rate limit exceeded"}}""")
                }
                else -> {
                    // Final retry succeeds with Markdown-fenced JSON response
                    Triple(
                        200,
                        null,
                        """
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  {
                                    "text": "```json\n{\"latex\": \"\\\\frac{a}{b}\"}\n```"
                                  }
                                ]
                              }
                            }
                          ],
                          "usageMetadata": {
                            "promptTokenCount": 321
                          }
                        }
                        """.trimIndent()
                    )
                }
            }

            val builder = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(jsonBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
            if (retryAfter != null) {
                builder.header("Retry-After", retryAfter)
            }
            builder.build()
        }

        val httpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val catalog = ModelCatalog(
            httpClient = httpClient,
            clockNowMs = { nowMs }
        )
        val client = GeminiClient(
            consentStore = consentStore,
            modelCatalog = catalog,
            httpClient = httpClient,
            minRequestIntervalMs = 1_000L,
            maxRequestsPerMinute = 15,
            clockNowMs = { nowMs },
            delayMs = { ms ->
                recordedDelays += ms
                nowMs += ms
            }
        )

        val response = client.generateContent(
            trigger = UserInitiatedAiTrigger.LASSO_TO_LATEX,
            promptText = "Return JSON with a single latex field",
            responseMimeType = "application/json"
        )

        assertEquals("gemini-3.8-flash", response.modelIdUsed)
        assertNotNull(response.modelSwitchedNoticeHu)
        assertTrue(response.modelSwitchedNoticeHu!!.contains("gemini-3.2-flash"))
        assertTrue(response.modelSwitchedNoticeHu!!.contains("gemini-3.8-flash"))
        assertEquals(321, response.promptTokensEstimate)

        // Verify Retry-After: 4 caused a >= 4000 ms backoff delay
        assertTrue("Expected 4000 ms backoff from Retry-After: 4", recordedDelays.any { it >= 4_000L })

        // Verify stale retired override was cleared in AiConsentStore and usage counters incremented
        val updatedState = consentStore.currentState()
        assertNull(updatedState.selectedModelIdOverride)
        assertEquals(1L, updatedState.totalRequestsCount)
        assertEquals(321L, updatedState.totalPromptTokensEstimate)

        // Verify Markdown fence stripping + pure-Kotlin JSON field extraction
        val cleanJson = GeminiClient.stripMarkdownCodeFences(response.text)
        assertEquals("""{"latex": "\\frac{a}{b}"}""", cleanJson)
        assertEquals("""\frac{a}{b}""", GeminiClient.extractJsonStringField(response.text, "latex"))

        assertEquals(
            listOf(
                "GET /v1beta/models",
                "POST /v1beta/models/gemini-3.2-flash:generateContent",
                "GET /v1beta/models",
                "POST /v1beta/models/gemini-3.8-flash:generateContent",
                "POST /v1beta/models/gemini-3.8-flash:generateContent"
            ),
            requestedUrls
        )
    }

    @Test
    fun thinkingBudgetZeroIsAppliedToGemini25FlashAndFallsBackCleanlyOnHttp400() = runBlocking {
        assertEquals(0, GeminiClient.resolveEffectiveThinkingBudget("gemini-2.5-flash"))
        assertEquals(0, GeminiClient.resolveEffectiveThinkingBudget("gemini-2.5-flash-lite"))
        assertNull(GeminiClient.resolveEffectiveThinkingBudget("gemini-2.0-flash"))
        assertNull(GeminiClient.resolveEffectiveThinkingBudget("gemini-2.5-pro"))

        val jsonWithThinking = GeminiClient.buildGenerateContentRequestJson(
            promptText = "Format page",
            inlinePngImages = emptyList(),
            responseMimeType = "application/json",
            responseSchemaJson = null,
            systemInstruction = null,
            temperature = 0.1f,
            thinkingBudget = 0
        )
        assertTrue(jsonWithThinking.contains("\"thinkingConfig\":{\"thinkingBudget\":0}"))

        val storage = InMemoryAiSettingsStorage()
        val consentStore = AiConsentStore(storage, JvmAesGcmCipher(), clockNowMs = { 5_000L })
        consentStore.saveApiKey("AIzaSyThinkingFallbackKey")
        consentStore.recordConsentAccepted(enableAi = true)

        val requestBodies = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.url.encodedPath.endsWith("/v1beta/models")) {
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            val sink = okio.Buffer()
            req.body?.writeTo(sink)
            val bodyStr = sink.readUtf8()
            requestBodies += bodyStr
            if (bodyStr.contains("thinkingConfig")) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body("""{"error":{"message":"Unknown name \"thinkingConfig\" at generation_config"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"candidates":[{"content":{"parts":[{"text":"{\"elements\":[]}"}]}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = GeminiClient(
            consentStore = consentStore,
            modelCatalog = ModelCatalog(httpClient = httpClient),
            httpClient = httpClient,
            minRequestIntervalMs = 0L
        )
        val res = client.generateContent(
            trigger = UserInitiatedAiTrigger.HANDWRITING_TO_MARKDOWN,
            promptText = "Format"
        )
        assertEquals("{\"elements\":[]}", res.text)
        assertEquals(2, requestBodies.size)
        assertTrue(requestBodies[0].contains("\"thinkingConfig\":{\"thinkingBudget\":0}"))
        assertFalse(requestBodies[1].contains("thinkingConfig"))
    }
}
