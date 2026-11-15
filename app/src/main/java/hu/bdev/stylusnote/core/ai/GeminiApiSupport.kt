package hu.bdev.stylusnote.core.ai

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Shared sanitization, Google Cloud Android-restriction header extraction, and REST error
 * parsing used consistently across [ModelCatalog], [GeminiClient], [AiConsentStore], and UI.
 */
object GeminiApiSupport {

    const val HEADER_API_KEY = "x-goog-api-key"
    const val HEADER_ANDROID_PACKAGE = "X-Android-Package"
    const val HEADER_ANDROID_CERT = "X-Android-Cert"

    private val INVISIBLE_AND_WHITESPACE_REGEX = Regex("[\\s\\u200B-\\u200D\\uFEFF]+")
    private val QUERY_KEY_PARAM_REGEX = Regex("(?:^|[?&])key=([^&]+)", RegexOption.IGNORE_CASE)

    /**
     * Normalizes copied API keys: removes zero-width chars, accidental quotes, `Bearer ` prefix,
     * `?key=` URL fragments, and any whitespace/newlines from wrapped copies.
     */
    fun sanitizeApiKey(raw: String): String {
        var working = raw.trim()
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", "")
        if ((working.startsWith('"') && working.endsWith('"')) ||
            (working.startsWith('\'') && working.endsWith('\''))
        ) {
            if (working.length >= 2) {
                working = working.substring(1, working.length - 1).trim()
            }
        }
        if (working.startsWith("Bearer ", ignoreCase = true)) {
            working = working.substring(7).trim()
        }
        QUERY_KEY_PARAM_REGEX.find(working)?.groupValues?.getOrNull(1)?.let { extracted ->
            working = extracted
        }
        return working.replace(INVISIBLE_AND_WHITESPACE_REGEX, "")
    }

    /**
     * Ensures [cleanKey] contains only printable US-ASCII characters allowed inside HTTP headers
     * so OkHttp does not throw an opaque low-level header validation error.
     */
    fun validateHeaderSafeApiKey(raw: String): String {
        val clean = sanitizeApiKey(raw)
        require(clean.isNotEmpty()) { "A művelethez érvényes Gemini API-kulcs szükséges." }
        val badChar = clean.firstOrNull { ch -> ch.code !in 0x21..0x7E }
        require(badChar == null) {
            "Az API-kulcs érvénytelen nem ASCII karaktert tartalmaz ('${badChar}'). Ellenőrizd a kimásolt kulcsot."
        }
        return clean
    }

    /**
     * Extracts Google Cloud `X-Android-Package` and colon-free lowercase 40-hex SHA-1
     * `X-Android-Cert` headers so API keys with Android app restrictions work seamlessly.
     */
    fun extractAndroidAppHeaders(context: Context): Map<String, String> = runCatching {
        val appContext = context.applicationContext
        val packageName = appContext.packageName
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signingInfo = packageInfo.signingInfo ?: return@runCatching emptyMap()
        val signer = signingInfo.apkContentsSigners?.firstOrNull()
            ?: signingInfo.signingCertificateHistory?.firstOrNull()
            ?: return@runCatching emptyMap()
        val sha1Bytes = MessageDigest.getInstance("SHA-1").digest(signer.toByteArray())
        val hexCert = buildString(sha1Bytes.size * 2) {
            for (b in sha1Bytes) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
        mapOf(
            HEADER_ANDROID_PACKAGE to packageName,
            HEADER_ANDROID_CERT to hexCert
        )
    }.getOrDefault(emptyMap())

    data class GoogleErrorDetails(
        val message: String? = null,
        val status: String? = null,
        val reason: String? = null
    )

    fun parseGoogleErrorDetails(rawBody: String): GoogleErrorDetails {
        if (rawBody.isBlank()) return GoogleErrorDetails()
        val root = runCatching { GeminiJsonCodec.parseObject(rawBody) }.getOrNull()
            ?: return GoogleErrorDetails()
        val errorObj = root["error"] as? Map<*, *> ?: return GoogleErrorDetails()
        val message = errorObj["message"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val status = errorObj["status"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val detailsList = errorObj["details"] as? List<*>
        val reason = detailsList
            ?.asSequence()
            ?.mapNotNull { (it as? Map<*, *>)?.get("reason")?.toString()?.trim() }
            ?.firstOrNull { it.isNotEmpty() }
        return GoogleErrorDetails(message = message, status = status, reason = reason)
    }

    /**
     * Produces a diagnostic Hungarian error message containing the actual Google REST API
     * `error.message` / `reason` instead of swallowing non-2xx responses behind a bare HTTP code.
     */
    fun formatGoogleApiHttpError(
        statusCode: Int,
        rawBody: String,
        operationLabelHu: String,
        androidHeaders: Map<String, String> = emptyMap()
    ): String {
        val parsed = parseGoogleErrorDetails(rawBody)
        val rawFallback = rawBody.replace(Regex("\\s+"), " ").trim().take(240)
        val detail = parsed.message ?: rawFallback.ifEmpty { "Ismeretlen szerverhiba" }
        val reason = parsed.reason.orEmpty()
        val lowerDetail = detail.lowercase()

        if (reason.equals("API_KEY_INVALID", ignoreCase = true) ||
            lowerDetail.contains("api key not valid")
        ) {
            return "$operationLabelHu (HTTP $statusCode): Érvénytelen API-kulcs — $detail"
        }

        if (statusCode == 403 &&
            (reason.contains("ANDROID", ignoreCase = true) ||
                lowerDetail.contains("android client"))
        ) {
            val pkg = androidHeaders[HEADER_ANDROID_PACKAGE] ?: "hu.bdev.stylusnote"
            val cert = androidHeaders[HEADER_ANDROID_CERT]?.let(::formatColonFingerprint)
            val certSuffix = if (cert != null) " / SHA-1: $cert" else ""
            return "$operationLabelHu (HTTP 403): A kulcs Android-alkalmazáskorlátozása blokkolta ($pkg$certSuffix) — $detail"
        }

        if (statusCode == 403 &&
            (reason.contains("REFERRER", ignoreCase = true) ||
                reason.contains("IP_ADDRESS", ignoreCase = true) ||
                lowerDetail.contains("referer") ||
                lowerDetail.contains("ip address"))
        ) {
            return "$operationLabelHu (HTTP 403): A kulcson webes (HTTP referrer) vagy IP-korlátozás van beállítva, ami mobilon tiltja a kérést — $detail"
        }

        if (statusCode == 403 &&
            (reason.equals("SERVICE_DISABLED", ignoreCase = true) ||
                lowerDetail.contains("has not been used in project") ||
                lowerDetail.contains("is disabled"))
        ) {
            return "$operationLabelHu (HTTP 403): A Generative Language API nincs engedélyezve a Google Cloud projektben — $detail"
        }

        val statusSuffix = parsed.status?.let { " [$it]" }.orEmpty()
        return "$operationLabelHu (HTTP $statusCode$statusSuffix): $detail"
    }

    private fun formatColonFingerprint(hex40: String): String =
        hex40.uppercase().chunked(2).joinToString(":")
}
