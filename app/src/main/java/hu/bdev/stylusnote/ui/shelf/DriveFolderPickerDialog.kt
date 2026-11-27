package hu.bdev.stylusnote.ui.shelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.bdev.stylusnote.data.export.GoogleDriveAuthorizationHelper
import hu.bdev.stylusnote.data.export.GoogleDriveFolderClient
import hu.bdev.stylusnote.data.export.GoogleDriveFolderItem
import hu.bdev.stylusnote.data.local.FolderEntity
import kotlinx.coroutines.launch

/**
 * Google Drive folder browser for linking a category to a destination folder where readable
 * PDF backups of notebooks in that category are synced automatically while backgrounded.
 * Supports ESC key and backdrop dismissal (Rule 11).
 */
@Composable
fun DriveFolderPickerDialog(
    categoryFolder: FolderEntity,
    onSelectDriveFolder: (driveFolderId: String, driveFolderName: String) -> Unit,
    onUnlinkDriveFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val driveClient = remember(appContext) {
        GoogleDriveFolderClient(
            accessTokenProvider = { GoogleDriveAuthorizationHelper.requireSilentAccessToken(appContext) }
        )
    }

    val breadcrumb = remember {
        mutableStateListOf(GoogleDriveFolderItem(id = "root", name = "Saját meghajtó", parentId = null))
    }
    val currentParent = breadcrumb.last()

    var subfolders by remember { mutableStateOf<List<GoogleDriveFolderItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateBox by remember { mutableStateOf(false) }
    var newSubfolderName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    suspend fun loadFolder(folderId: String) {
        isLoading = true
        errorMessage = null
        runCatching {
            driveClient.listSubfolders(folderId)
        }.onSuccess { list ->
            subfolders = list
        }.onFailure { err ->
            errorMessage = err.message ?: "Nem sikerült betölteni a Google Drive mappákat."
        }
        isLoading = false
    }

    LaunchedEffect(currentParent.id) {
        loadFolder(currentParent.id)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo.isWindowFocused) {
            if (windowInfo.isWindowFocused) {
                runCatching { focusRequester.requestFocus() }
            }
        }
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag("drive_folder_picker_backdrop")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .widthIn(min = 340.dp, max = 560.dp)
                    .heightIn(max = 680.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .testTag("drive_folder_picker_dialog"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Drive PDF mappa: ${categoryFolder.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = categoryFolder.drivePdfFolderName?.let {
                                    "Jelenleg csatolva: $it"
                                } ?: "Nincs csatolt Drive mappa (nem készül automatikus PDF)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Bezárás (ESC)")
                        }
                    }

                    // Breadcrumb + New folder action bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (breadcrumb.size > 1) {
                            IconButton(
                                onClick = { breadcrumb.removeAt(breadcrumb.lastIndex) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Vissza"
                                )
                            }
                        }
                        Text(
                            text = breadcrumb.joinToString(" / ") { it.name },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showCreateBox = !showCreateBox },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = "Új mappa létrehozása"
                            )
                        }
                    }

                    if (showCreateBox) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newSubfolderName,
                                onValueChange = { newSubfolderName = it },
                                label = { Text("Új Drive mappa neve") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                enabled = !isCreating && newSubfolderName.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        isCreating = true
                                        errorMessage = null
                                        runCatching {
                                            driveClient.createSubfolder(currentParent.id, newSubfolderName)
                                        }.onSuccess { created ->
                                            newSubfolderName = ""
                                            showCreateBox = false
                                            breadcrumb.add(created)
                                        }.onFailure { err ->
                                            errorMessage = err.message ?: "Mappa létrehozása sikertelen."
                                        }
                                        isCreating = false
                                    }
                                }
                            ) {
                                Text("Létrehoz")
                            }
                        }
                    }

                    HorizontalDivider()

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 300.dp)
                    ) {
                        when {
                            isLoading -> {
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Mappák betöltése...")
                                }
                            }
                            subfolders.isEmpty() -> {
                                Text(
                                    text = "Ez a mappa nem tartalmaz almappákat. Használd az aktuális mappát vagy hozz létre újat.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(16.dp)
                                )
                            }
                            else -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    subfolders.forEach { folder ->
                                        Surface(
                                            onClick = { breadcrumb.add(folder) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = folder.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    imageVector = Icons.Filled.ChevronRight,
                                                    contentDescription = "Megnyitás",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!categoryFolder.drivePdfFolderId.isNullOrBlank()) {
                            OutlinedButton(onClick = onUnlinkDriveFolder) {
                                Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Leválasztás")
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text("Mégse")
                        }
                        Button(
                            enabled = currentParent.id != "root" && !isLoading,
                            onClick = { onSelectDriveFolder(currentParent.id, currentParent.name) }
                        ) {
                            Text("Mappa kiválasztása")
                        }
                    }
                    if (currentParent.id == "root") {
                        Text(
                            text = "Válassz vagy hozz létre egy almappát a „Saját meghajtó” alatt a PDF-ek rendszerezéséhez.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
