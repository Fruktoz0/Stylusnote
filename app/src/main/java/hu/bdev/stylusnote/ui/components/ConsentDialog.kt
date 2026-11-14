package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.bdev.stylusnote.core.ai.AiConsentState
import hu.bdev.stylusnote.core.ai.AiConsentStore
import hu.bdev.stylusnote.core.ai.GeminiModelInfo
import kotlinx.coroutines.launch

/**
 * First-use AI Privacy Consent & BYOK Gemini Settings modal (Section 9.5 & UX Rule 11).
 * Closes cleanly via both ESC key and backdrop tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsentDialog(
    currentState: AiConsentState,
    availableFlashModels: List<GeminiModelInfo> = emptyList(),
    initialStatusMessage: String? = null,
    onDismiss: () -> Unit,
    onSaveSettings: suspend (
        consentAccepted: Boolean,
        aiEnabled: Boolean,
        newRawApiKey: String?,
        modelOverride: String?
    ) -> Unit,
    onClearApiKey: suspend () -> Unit,
    onRevokeConsent: suspend () -> Unit,
    onRefreshModels: (suspend (apiKeyForRefresh: String?) -> Result<List<GeminiModelInfo>>)? = null
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var consentChecked by remember(currentState.consentAccepted) {
        mutableStateOf(currentState.consentAccepted)
    }
    var aiEnabledDraft by remember(currentState.aiEnabled) {
        mutableStateOf(currentState.aiEnabled)
    }
    var apiKeyInput by remember { mutableStateOf("") }
    var revealApiKey by remember { mutableStateOf(false) }
    var modelOverrideInput by remember(currentState.selectedModelIdOverride) {
        mutableStateOf(currentState.selectedModelIdOverride.orEmpty())
    }
    var discoveredModels by remember(availableFlashModels) {
        mutableStateOf(availableFlashModels)
    }
    var isBusy by remember { mutableStateOf(false) }
    var feedbackMessage by remember(initialStatusMessage) {
        mutableStateOf(initialStatusMessage)
    }
    var isErrorFeedback by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "AI Copilot – Adatvédelem és Gemini BYOK",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Bezárás"
                            )
                        }
                    }

                    // Mandatory Section 9.5 privacy disclosure (EEA/UK/CH vs. outside Free Tier)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Adatvédelmi tájékoztató (9.5-ös előírás)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "• Az AI funkciók alapértelmezés szerint kikapcsoltak, és csak kifejezett felhasználói műveletre indulnak el. A háttérben soha nem kerül tartalom a Geminihez (a kézírás-OCR 100% offline fut az ML Kit segítségével).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• A kijelölt kézírás vagy jegyzetoldal PNG képként közvetlenül a Google Gemini API-hoz kerül a saját Google AI Studio kulcsoddal (BYOK). A kulcsot az Android Keystore AES-256-GCM titkosítással védi, és nem kerül bele a biztonsági mentésekbe.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• EGT (EEA), Svájc, Egyesült Királyság (UK) és fizetős (Paid) API-szint: a Google fizetős adatkezelési feltételei érvényesek minden szolgáltatásra — a beküldött tartalmat emberi felülvizsgálók nem olvassák, és nem használják modelltanításra.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• Ingyenes (Free) API-szint (EGT/Svájc/UK területén kívül): a beküldött tartalmat emberi felülvizsgálók is olvashatják a Google termékfejlesztése érdekében.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = {
                                runCatching {
                                    uriHandler.openUri(AiConsentStore.GEMINI_TERMS_URL)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Google Gemini API szolgáltatási feltételek megnyitása")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isBusy) {
                                val next = !consentChecked
                                consentChecked = next
                                if (!next) {
                                    aiEnabledDraft = false
                                } else if (!aiEnabledDraft && (currentState.hasConfiguredApiKey || apiKeyInput.isNotBlank())) {
                                    aiEnabledDraft = true
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = consentChecked,
                            onCheckedChange = { checked ->
                                consentChecked = checked
                                if (!checked) aiEnabledDraft = false
                            },
                            enabled = !isBusy
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Elolvastam a fenti adatvédelmi tájékoztatót, és hozzájárulok a kijelölt tartalom képként történő elküldéséhez a saját Gemini API-kulcsommal.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI Copilot funkciók engedélyezése",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (consentChecked) {
                                    "Kikapcsolható bármikor az azonnali hívásblokkoláshoz."
                                } else {
                                    "Bekapcsolás előtt fogadd el a hozzájárulási nyilatkozatot."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = aiEnabledDraft,
                            onCheckedChange = { aiEnabledDraft = it },
                            enabled = consentChecked && !isBusy
                        )
                    }

                    HorizontalDivider()

                    // BYOK API Key Entry
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Saját Google AI Studio API-kulcs (BYOK)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (currentState.hasConfiguredApiKey) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = currentState.maskedApiKeyPreview ?: "Kulcs mentve",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isBusy,
                            label = {
                                Text(
                                    if (currentState.hasConfiguredApiKey) {
                                        "Új API-kulcs megadása (üresen hagyva marad a jelenlegi)"
                                    } else {
                                        "Gemini API-kulcs (AIza...)"
                                    }
                                )
                            },
                            visualTransformation = if (revealApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { revealApiKey = !revealApiKey }) {
                                    Icon(
                                        imageVector = if (revealApiKey) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (revealApiKey) {
                                            "Kulcs elrejtése"
                                        } else {
                                            "Kulcs megjelenítése"
                                        }
                                    )
                                }
                            }
                        )

                        if (currentState.hasConfiguredApiKey) {
                            TextButton(
                                enabled = !isBusy,
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        feedbackMessage = null
                                        runCatching { onClearApiKey() }
                                            .onSuccess {
                                                apiKeyInput = ""
                                                isErrorFeedback = false
                                                feedbackMessage = "A tárolt API-kulcs törölve."
                                            }
                                            .onFailure { err ->
                                                isErrorFeedback = true
                                                feedbackMessage = err.message ?: "Kulcstörlési hiba."
                                            }
                                        isBusy = false
                                    }
                                }
                            ) {
                                Text("Mentett API-kulcs törlése")
                            }
                        }
                    }

                    // Dynamic Flash Model Selection
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = modelOverrideInput,
                                onValueChange = { modelOverrideInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                enabled = !isBusy,
                                label = { Text("Modell felülírása (opcionális, üres = automatikus Flash)") },
                                placeholder = { Text("pl. gemini-3.8-flash") }
                            )
                            if (onRefreshModels != null) {
                                OutlinedButton(
                                    enabled = !isBusy && (apiKeyInput.isNotBlank() || currentState.hasConfiguredApiKey),
                                    onClick = {
                                        scope.launch {
                                            isBusy = true
                                            feedbackMessage = null
                                            val refreshKey = apiKeyInput.trim().ifEmpty { null }
                                            onRefreshModels(refreshKey)
                                                .onSuccess { list ->
                                                    discoveredModels = list
                                                    isErrorFeedback = false
                                                    feedbackMessage = if (list.isEmpty()) {
                                                        "Nem található aktív Flash modell a /v1beta/models listában."
                                                    } else {
                                                        "${list.size} aktív Flash modell betöltve (/v1beta/models)."
                                                    }
                                                }
                                                .onFailure { err ->
                                                    isErrorFeedback = true
                                                    feedbackMessage = err.message ?: "Modell-lekérdezési hiba."
                                                }
                                            isBusy = false
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Modellek")
                                }
                            }
                        }

                        if (discoveredModels.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = modelOverrideInput.isBlank(),
                                    onClick = { modelOverrideInput = "" },
                                    label = { Text("Automatikus (legújabb Flash)") }
                                )
                                discoveredModels.take(6).forEach { model ->
                                    FilterChip(
                                        selected = modelOverrideInput.equals(model.id, ignoreCase = true),
                                        onClick = { modelOverrideInput = model.id },
                                        label = { Text(model.id) }
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Használati számláló: ${currentState.totalRequestsCount} kérés · ~${currentState.totalPromptTokensEstimate} bemeneti token",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    feedbackMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isErrorFeedback) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentState.consentAccepted) {
                            TextButton(
                                enabled = !isBusy,
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        runCatching { onRevokeConsent() }
                                            .onSuccess {
                                                consentChecked = false
                                                aiEnabledDraft = false
                                                isErrorFeedback = false
                                                feedbackMessage = "Hozzájárulás visszavonva, AI kikapcsolva."
                                            }
                                            .onFailure { err ->
                                                isErrorFeedback = true
                                                feedbackMessage = err.message ?: "Hiba visszavonáskor."
                                            }
                                        isBusy = false
                                    }
                                }
                            ) {
                                Text("Hozzájárulás visszavonása")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = onDismiss
                            ) {
                                Text("Mégse")
                            }
                            Button(
                                enabled = !isBusy,
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        feedbackMessage = null
                                        val trimmedKey = apiKeyInput.trim().ifEmpty { null }
                                        val trimmedOverride = modelOverrideInput.trim().ifEmpty { null }
                                        val effectiveAiEnabled = consentChecked && aiEnabledDraft
                                        runCatching {
                                            onSaveSettings(
                                                consentChecked,
                                                effectiveAiEnabled,
                                                trimmedKey,
                                                trimmedOverride
                                            )
                                        }.onSuccess {
                                            onDismiss()
                                        }.onFailure { err ->
                                            isErrorFeedback = true
                                            feedbackMessage = err.message ?: "Mentési hiba."
                                        }
                                        isBusy = false
                                    }
                                }
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text("Beállítások mentése")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

