package com.memora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.memora.app.local.DateKind
import com.memora.app.local.IndexState
import com.memora.app.local.ModelStatus
import com.memora.app.local.OperatingMode
import com.memora.app.local.RelatedMemory
import com.memora.app.local.TimelineEntry
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(vm: MemoraViewModel, onAsk: () -> Unit, onOpenMemories: () -> Unit, onOpenLibrary: () -> Unit, onOpenSettings: () -> Unit) {
    val files by vm.local.files.collectAsState()
    val connection by vm.connection.collectAsState()
    val mode by vm.local.mode.collectAsState()
    val model by vm.local.model.collectAsState()
    val ask by vm.ask.collectAsState()
    val focus = LocalFocusManager.current
    var viewing by remember { mutableStateOf<ViewedFile?>(null) }
    var entries by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var insights by remember { mutableStateOf<List<Pair<com.memora.app.local.LocalFile, RelatedMemory>>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.local.importFiles(uris) }

    LaunchedEffect(files) {
        entries = vm.local.timeline(files)
        // Only relationships the text itself supports: files that share at least two distinctive words.
        insights = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            files.filter { it.state == IndexState.READY }.sortedByDescending { it.importedAt }.take(6)
                .flatMap { f -> vm.local.related(f).take(1).map { f to it } }
                .distinctBy { setOf(it.first.id, it.second.file.id) }.take(2)
        }
    }

    val photos = entries.count { it.isPhoto }
    val searchable = files.count { it.state == IndexState.READY }
    val indexing = files.count { it.state == IndexState.PENDING || it.state == IndexState.EXTRACTING || it.state == IndexState.INDEXING }
    val hasRoute = vm.hasLaptopRoute()
    val status = Connectivity.describe(mode, connection, hasRoute)
    val modelMissing = model.status is ModelStatus.NotInstalled

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                MemoraMark(size = 28.dp)
                Text("MEMORA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                ConnectivityPill(status, onClick = onOpenSettings)
            }
        }
        item {
            // The headline invites a question; the cluster of blocks is decoration only and sits behind nothing.
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(end = 60.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        "${greeting()} · ${LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("What do you want to remember?", style = MaterialTheme.typography.displaySmall)
                    Text("Your visual memory, understood.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GeoCluster(Modifier.align(Alignment.TopEnd), size = 64.dp)
            }
        }
        item {
            val go = { focus.clearFocus(); if (query.isNotBlank()) { vm.submit(query); onAsk() } }
            var focused by remember { mutableStateOf(false) }
            val reduced = LocalReducedMotion.current
            val border by animateColorAsState(
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                tween(Motion.ms(Motion.Micro, reduced)), label = "searchBorder",
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                placeholder = { Text("Ask anything about your memories...") }, singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = border, focusedBorderColor = border),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
                trailingIcon = { IconButton(onClick = { go() }, enabled = query.isNotBlank()) { Icon(Icons.Default.Search, contentDescription = "Ask") } },
            )
        }
        item {
            // A real entry point: opens the Search section, where questions are answered with evidence.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary)
                    .pressClickable("Ask Memora a question", onAsk).padding(Space.lg),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                MemoraMark(size = 40.dp, link = MaterialTheme.colorScheme.onPrimary)
                Column(Modifier.weight(1f)) {
                    Text("Ask Memora", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        if (mode == OperatingMode.PHONE_ONLY) "Answers come from the files on this phone." else "Answers cite the files they came from.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
            }
        }

        if (modelMissing && mode != OperatingMode.LAPTOP) item {
            Notice("Offline answers need an AI model on this phone. Install one in Settings, or connect the laptop.")
            TextButton(onClick = onOpenSettings) { Text("Set up the AI model") }
        }
        if (indexing > 0) item { Notice("Making $indexing file${if (indexing == 1) "" else "s"} searchable on this phone…") }

        if (files.isEmpty()) {
            item {
                EmptyState(
                    title = "Your archive is empty",
                    body = "Import photos and documents to keep them on this phone. Memora reads their text so you can search and ask questions offline.",
                    actionLabel = "Import files", onAction = { importer.launch(arrayOf("*/*")) },
                    secondaryLabel = if (hasRoute) "Get files from the laptop" else "Connect a laptop", onSecondary = onOpenLibrary.takeIf { hasRoute } ?: onOpenSettings,
                )
            }
        } else {
            // Library overview: real counts, laid out as type rather than boxes.
            item {
                Row(Modifier.fillMaxWidth().padding(top = Space.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(photos, "Photos")
                    Stat(entries.size - photos, "Documents")
                    Stat(searchable, "Searchable")
                    Stat(files.size, "On this phone")
                }
                (connection as? Connection.Connected)?.health?.let {
                    Text(
                        "The laptop has indexed ${it.filesIndexed} file${if (it.filesIndexed == 1) "" else "s"}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm),
                    )
                }
            }

            val recentPhotos = entries.filter { it.isPhoto }.sortedByDescending { it.file.importedAt }.take(8)
            if (recentPhotos.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), contentPadding = PaddingValues(vertical = Space.xs)) {
                        items(recentPhotos, key = { "strip-${it.file.id}" }) { e ->
                            Column(
                                Modifier.width(132.dp).pressClickable("Open ${e.file.name}") { viewing = ViewedFile(e.file.id, e.file.name, "On this phone", e.file.ext, null, true) },
                                verticalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                FileThumb(vm, ThumbSource.Stored(e.file), size = 132.dp)
                                Text(e.file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item {
                SectionHeader("Recent memories") { TextButton(onClick = onOpenMemories) { Text("See all") } }
            }
            items(entries.sortedByDescending { it.file.importedAt }.take(5), key = { "recent-${it.file.id}" }) { e ->
                MemoryRow(
                    vm, ThumbSource.Stored(e.file), e.file.name,
                    date = "${e.kind.label} ${ExifDatesShort.format(e.date)}",
                    tags = { indexingTag(e.file).let { (l, t) -> if (e.file.state != IndexState.READY) StateTag(l, t) } },
                    onClick = { viewing = ViewedFile(e.file.id, e.file.name, "On this phone", e.file.ext, null, true) },
                )
            }

            if (insights.isNotEmpty()) {
                item { SectionHeader("Connections Memora noticed") }
                items(insights, key = { "ins-${it.first.id}-${it.second.file.id}" }) { (a, r) ->
                    MemoryRow(
                        vm, ThumbSource.Stored(a), "${a.name}  ↔  ${r.file.name}",
                        subtitle = "Both mention: ${r.sharedTerms.joinToString(", ")}",
                        tags = { StateTag("Inferred from similar text", Tone.NEUTRAL) },
                        onClick = { viewing = ViewedFile(a.id, a.name, "On this phone", a.ext, null, true) },
                    )
                }
            }
        }
    }
    viewing?.let { FileViewer(vm, it) { viewing = null } }
}

/** Short locale-aware date for lists. */
object ExifDatesShort {
    fun format(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

@Composable
private fun Stat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text("$value", style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
