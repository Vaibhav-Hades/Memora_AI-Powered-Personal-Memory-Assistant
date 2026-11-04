package com.memora.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.memora.app.local.IndexState
import com.memora.app.local.LocalFile
import com.memora.app.local.RemoteFile
import com.memora.app.local.SyncEntry
import com.memora.app.local.SyncState

/** Opens the phone's stored copy in whatever app can show it. */
fun openStored(context: Context, vm: MemoraViewModel, file: LocalFile) {
    val uri = vm.local.shareUri(file)
    if (uri == null) {
        vm.local.retryFailed()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (_: ActivityNotFoundException) {
        // No installed app opens this type: nothing else to do but say so.
    }
}

fun stateLabel(f: LocalFile): String = when (f.state) {
    IndexState.READY -> "On phone · searchable"
    IndexState.PENDING -> "On phone · not searchable yet"
    IndexState.EXTRACTING -> "Reading text…"
    IndexState.INDEXING -> "Indexing…"
    IndexState.FAILED -> "Indexing failed: ${f.error ?: "unknown error"}"
    IndexState.UNSUPPORTED -> "On phone · ${f.error ?: "contents can't be searched"}"
}

private enum class LibraryFilter(val label: String) {
    ALL("All"), PHOTOS("Photos"), DOCUMENTS("Documents"), SEARCHABLE("Searchable offline"), ATTENTION("Needs attention"),
}

private enum class LibraryPane(val label: String) { PHONE("On this phone"), LAPTOP("On the laptop") }

/** The library has two places: files stored on this phone (with sync from the laptop) and the laptop's own folders. */
@Composable
fun LibraryScreen(vm: MemoraViewModel) {
    var pane by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(LibraryPane.PHONE) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LibraryPane.entries.forEach { p ->
                androidx.compose.material3.FilterChip(selected = pane == p, onClick = { pane = p }, label = { Text(p.label) })
            }
        }
        when (pane) {
            LibraryPane.PHONE -> PhoneLibrary(vm)
            LibraryPane.LAPTOP -> IndexScreen(vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneLibrary(vm: MemoraViewModel) {
    val context = LocalContext.current
    val files by vm.local.files.collectAsState()
    val sync by vm.local.sync.collectAsState()
    val transfers by vm.local.transfers.collectAsState()
    val notice by vm.local.notice.collectAsState()
    val connection by vm.connection.collectAsState()
    var exporting by remember { mutableStateOf<LocalFile?>(null) }
    var viewing by remember { mutableStateOf<ViewedFile?>(null) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var newestFirst by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.local.importFiles(uris) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> uri?.let { u -> exporting?.let { vm.local.export(it, u) } }; exporting = null }

    LaunchedEffect(Unit) { vm.local.refresh() }

    val ready = files.count { it.state == IndexState.READY }
    val shown = remember(files, filter, query, newestFirst) {
        val q = query.trim().lowercase()
        files.filter { f ->
            val photo = f.mime.startsWith("image/") || f.ext in com.memora.app.local.DefaultExtractor.IMAGE_EXTS
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.PHOTOS -> photo
                LibraryFilter.DOCUMENTS -> !photo
                LibraryFilter.SEARCHABLE -> f.state == IndexState.READY
                LibraryFilter.ATTENTION -> f.state == IndexState.FAILED || f.state == IndexState.PENDING || f.state == IndexState.UNSUPPORTED
            } && (q.isEmpty() || f.name.lowercase().contains(q))
        }.let { l -> if (newestFirst) l.sortedByDescending { it.importedAt } else l.sortedBy { it.importedAt } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.lg, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        item {
            Column {
                ScreenTitle(
                    "Library",
                    "${files.size} file${if (files.size == 1) "" else "s"} stored privately on this phone, $ready searchable offline. Stored is not the same as searchable: a file must be indexed first.",
                )
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search by file name") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                )
                FlowRow(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    LibraryFilter.entries.forEach { f -> androidx.compose.material3.FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                    androidx.compose.material3.FilterChip(selected = newestFirst, onClick = { newestFirst = !newestFirst }, label = { Text(if (newestFirst) "Newest first" else "Oldest first") })
                }
            }
        }
        notice?.let { n -> item { Notice(n); TextButton(onClick = vm.local::dismissNotice) { Text("Dismiss") } } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Import files") }
                if (files.any { it.state == IndexState.FAILED || it.state == IndexState.PENDING }) {
                    OutlinedButton(onClick = vm.local::retryFailed, modifier = Modifier.heightIn(min = Space.touch)) { Text("Retry failed / pending") }
                }
            }
        }
        if (files.isEmpty()) {
            item {
                EmptyState(
                    "Nothing on this phone yet",
                    "Import files from this phone, or download them from the laptop below. Files must be on the phone and searchable before they work offline.",
                    actionLabel = "Import files", onAction = { importer.launch(arrayOf("*/*")) },
                )
            }
        } else if (shown.isEmpty()) {
            item { EmptyState("Nothing matches", "No stored file fits this filter.") }
        }
        items(shown, key = { "local-${it.id}" }) { f ->
            Column(Modifier.fillMaxWidth()) {
                MemoryRow(
                    vm, ThumbSource.Stored(f), f.name, subtitle = stateLabel(f),
                    date = "Added " + com.memora.app.local.ExifDates.formatInstant(f.importedAt),
                    tags = { indexingTag(f).let { (l, t) -> StateTag(l, t) } },
                    onClick = { viewing = ViewedFile(f.id, f.name, "On this phone", f.ext, null, true) },
                )
                if (f.state == IndexState.EXTRACTING || f.state == IndexState.INDEXING) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openStored(context, vm, f) }) { Text("Open") }
                    if (f.state == IndexState.PENDING || f.state == IndexState.FAILED) {
                        TextButton(onClick = { vm.local.indexOne(f.id) }) { Text(if (f.state == IndexState.FAILED) "Retry indexing" else "Make searchable offline") }
                    }
                    TextButton(onClick = { exporting = f; saver.launch(f.name) }) { Text("Save a copy…") }
                    TextButton(onClick = { vm.local.remove(f.id) }) { Text("Remove from phone") }
                }
            }
        }

        item {
            SectionHeader("Get files from the laptop")
            Text(
                "Pick laptop files to keep on the phone. They are checked against the laptop's checksum before being stored. " +
                    "Nothing is deleted from the phone if a file disappears from the laptop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm.local::loadSync, enabled = connection is Connection.Connected && !sync.loading) {
                Text(if (sync.loading) "Checking the laptop…" else "Check the laptop for files")
            }
            if (connection !is Connection.Connected) Notice("The laptop isn't connected, so it can't be checked right now. Files already on the phone keep working.")
        }
        sync.error?.let { item { Notice(it, error = true) } }
        items(sync.entries, key = { "sync-${it.remote?.id ?: -(it.local?.id ?: 0)}-${it.name}" }) { e ->
            SyncRow(vm, e, transfers[e.remote?.id], context)
        }
    }
    viewing?.let { FileViewer(vm, it) { viewing = null } }
}

@Composable
private fun SyncRow(vm: MemoraViewModel, e: SyncEntry, transfer: TransferUi?, context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (e.state) {
                    SyncState.LAPTOP_ONLY -> "Only on the laptop"
                    SyncState.OFFLINE -> "Available offline"
                    SyncState.CHANGED -> "Changed on the laptop since you downloaded it"
                    SyncState.REMOVED_ON_LAPTOP -> "No longer on the laptop (your copy is kept)"
                    SyncState.UNAVAILABLE -> "The original is missing on the laptop"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            transfer?.let { TransferStatus(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val remote: RemoteFile? = e.remote
                val busy = transfer != null && !transfer.finished && !transfer.failed
                if (remote != null && (e.state == SyncState.LAPTOP_ONLY || e.state == SyncState.CHANGED) && !busy) {
                    Button(onClick = { vm.local.download(remote) }) {
                        Text(if (transfer?.failed == true) "Retry download" else if (e.state == SyncState.CHANGED) "Update on phone" else "Download to phone")
                    }
                }
                e.local?.let { l -> TextButton(onClick = { openStored(context, vm, l) }) { Text("Open") } }
            }
        }
    }
}

/** Progress, failure and success of one transfer, with the follow-up actions for a finished download. */
@Composable
fun TransferStatus(t: TransferUi, onMakeSearchable: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    when {
        t.failed -> Notice(t.message ?: "The download failed.", error = true)
        t.finished -> {
            Text("Downloaded and verified.", style = MaterialTheme.typography.bodySmall, color = StatusColors.ok)
            Row {
                onOpen?.let { TextButton(onClick = it) { Text("Open") } }
                onMakeSearchable?.let { TextButton(onClick = it) { Text("Make searchable offline") } }
            }
        }
        else -> {
            LinearProgressIndicator(
                progress = { if (t.total > 0) (t.done.toFloat() / t.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${t.done / 1024} of ${t.total / 1024} KB", style = MaterialTheme.typography.bodySmall)
        }
    }
}
