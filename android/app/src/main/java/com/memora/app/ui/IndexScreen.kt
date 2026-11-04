package com.memora.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.memora.app.data.Folder
import kotlinx.coroutines.delay

private const val MAX_PICK = 20

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndexScreen(vm: MemoraViewModel) {
    val uploads by vm.uploads.collectAsState()
    val library by vm.library.collectAsState()
    val connection by vm.connection.collectAsState()
    val connected = connection is Connection.Connected
    val health = (connection as? Connection.Connected)?.health

    // The system Photo Picker: the user chooses photos; the app gets access to only those, no permission prompt.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) vm.addPhotos(uris)
    }

    // Keep folder counts and indexing progress fresh while this screen is open.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        vm.loadLibrary()
        while (true) {
            delay(4000)
            vm.refreshHealth()
        }
    }
    LaunchedEffect(health?.indexingRunning, health?.indexingDone) { if (connected) vm.loadLibrary() }

    val busy = uploads.any { it.status == UploadStatus.Preparing || it.status == UploadStatus.Uploading }
    val toSend = uploads.count { it.status == UploadStatus.Waiting || it.status == UploadStatus.Failed }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Phone photos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose photos to send to Memora on your laptop. They're resized, stripped of location data, " +
                    "saved in Memora's phone-uploads folder on the laptop and read by the local AI. Only the photos you pick are shared.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                ) { Text("Choose photos") }
                Button(onClick = vm::uploadAll, enabled = connected && !busy && toSend > 0) {
                    Text(if (toSend > 0) "Send & index $toSend" else "Send & index")
                }
            }
            if (!connected) Text("Connect to the laptop in Settings to send photos.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        items(uploads, key = { it.uri.toString() }) { item -> UploadRow(vm, item, removable = !busy) }
        if (uploads.any { it.status == UploadStatus.Indexed || it.status == UploadStatus.AlreadyIndexed }) {
            item { TextButton(onClick = vm::clearFinished) { Text("Clear finished") } }
        }

        item {
            Text("Laptop folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp))
            Text(
                "Folders on the laptop that Memora indexes. Other folders can be added on the laptop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (health?.indexingRunning == true) {
            item {
                Column {
                    Text(
                        "Indexing ${health.indexingDone}/${health.indexingTotal}" + (health.indexingCurrent?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { if (health.indexingTotal > 0) health.indexingDone.toFloat() / health.indexingTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val toAdd = library.suggested.filter { !it.approved }
        if (connected && toAdd.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    toAdd.forEach { s -> AssistChip(onClick = { vm.addFolder(s.path) }, label = { Text("+ ${s.name}") }) }
                }
            }
        }
        library.error?.let { item { Notice(it, error = true) } }
        if (library.loading && library.folders.isEmpty()) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        items(library.folders, key = { "folder-${it.id}" }) { f -> FolderRow(f) { vm.rescan(f.id) } }
    }
}

@Composable
private fun UploadRow(vm: MemoraViewModel, item: UploadItem, removable: Boolean) {
    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { thumb = vm.localThumbnail(item.uri, 160) }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val (label, color) = when (item.status) {
                    UploadStatus.Waiting -> "Ready to send" to MaterialTheme.colorScheme.onSurfaceVariant
                    UploadStatus.Preparing -> "Preparing…" to MaterialTheme.colorScheme.onSurfaceVariant
                    UploadStatus.Uploading -> "Sending to laptop…" to MaterialTheme.colorScheme.primary
                    UploadStatus.Indexing -> "On the laptop · AI is reading it…" to StatusColors.warn
                    UploadStatus.Indexed -> "Indexed · searchable now" to StatusColors.ok
                    UploadStatus.AlreadyIndexed -> "Already indexed" to StatusColors.ok
                    UploadStatus.Failed -> "Failed" to MaterialTheme.colorScheme.error
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.status in setOf(UploadStatus.Preparing, UploadStatus.Uploading, UploadStatus.Indexing)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
                    } else {
                        StatusDot(color)
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                }
                item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (removable && item.status in setOf(UploadStatus.Waiting, UploadStatus.Failed)) {
                IconButton(onClick = { vm.removePhoto(item) }) { Icon(Icons.Default.Close, contentDescription = "Remove from list") }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: Folder, onRescan: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (folder.isUploads) "Phone uploads" else folder.path.substringAfterLast('\\').substringAfterLast('/'),
                    fontWeight = FontWeight.Medium)
                Text(folder.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${folder.indexedCount}/${folder.fileCount} indexed" + if (folder.errorCount > 0) " · ${folder.errorCount} failed" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (folder.errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRescan) { Text("Rescan") }
        }
    }
}
