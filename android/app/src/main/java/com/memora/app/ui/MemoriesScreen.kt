package com.memora.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.memora.app.local.DateKind
import com.memora.app.local.Granularity
import com.memora.app.local.IndexState
import com.memora.app.local.KindFilter
import com.memora.app.local.Timeline
import com.memora.app.local.TimelineEntry

/** Chronological view of what is stored on the phone. Dates are capture dates where the photo has one, otherwise the day it was added. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoriesScreen(vm: MemoraViewModel, onOpenLibrary: () -> Unit) {
    val files by vm.local.files.collectAsState()
    var entries by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(KindFilter.ALL) }
    var granularity by rememberSaveable { mutableStateOf(Granularity.MONTH) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ViewedFile?>(null) }

    LaunchedEffect(files) { entries = vm.local.timeline(files); loaded = true }

    val groups = remember(entries, kind, query, granularity, newestFirst) {
        Timeline.group(Timeline.filter(entries, kind, query), granularity, newestFirst)
    }

    val header: @Composable () -> Unit = {
            Column {
                ScreenTitle("Memories", "Your photos and documents in time order.")
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search by file name") }, shape = RoundedCornerShape(14.dp),
                )
                FlowRow(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    KindFilter.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Granularity.entries.forEach { g -> FilterChip(selected = granularity == g, onClick = { granularity = g }, label = { Text(g.label) }) }
                    FilterChip(selected = newestFirst, onClick = { newestFirst = !newestFirst }, label = { Text(if (newestFirst) "Newest first" else "Oldest first") })
                    FilterChip(selected = grid, onClick = { grid = !grid }, label = { Text(if (grid) "Grid view" else "List view") })
                }
                Text(
                    "“Captured” is the date written inside a photo. “Added to Memora” is when this phone stored the file. Memora doesn't guess event dates.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.sm),
                )
            }
    }

    if (grid && loaded && groups.isNotEmpty()) {
        // Gallery: square tiles that fill the width. Thumbnails come from the same cached thumbnail path as the list.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
            groups.forEach { g ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-${g.label}") { SectionHeader(g.label) }
                items(g.entries, key = { "g-${it.file.id}" }) { e ->
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .pressClickable("Open ${e.file.name}") { viewing = ViewedFile(e.file.id, e.file.name, "On this phone", e.file.ext, null, true) },
                    ) {
                        FileThumb(vm, ThumbSource.Stored(e.file), modifier = Modifier.fillMaxSize(), size = Dp.Unspecified)
                        if (e.file.state != IndexState.READY) {
                            indexingTag(e.file).let { (l, t) -> StateTag(l, t, Modifier.align(Alignment.BottomStart).padding(4.dp).background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(6.dp))) }
                        }
                    }
                }
            }
        }
    } else {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        item { header() }
        when {
            !loaded -> items(4) { Skeleton(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(56.dp)) }
            files.isEmpty() -> item {
                EmptyState("No memories yet", "Files you import or download to this phone appear here, in order.", actionLabel = "Open Library", onAction = onOpenLibrary)
            }
            groups.isEmpty() -> item { EmptyState("Nothing matches", "Try another name, or switch the filter back to All.") }
            else -> groups.forEach { g ->
                item(key = "h-${g.label}") { SectionHeader(g.label) }
                items(g.entries, key = { "t-${it.file.id}" }) { e ->
                    MemoryRow(
                        vm, ThumbSource.Stored(e.file), e.file.name,
                        subtitle = e.file.ext.uppercase().ifEmpty { null },
                        date = if (granularity == Granularity.DAY) e.kind.label else "${e.kind.label} ${ExifDatesShort.format(e.date)}",
                        tags = {
                            if (e.kind == DateKind.ADDED) StateTag("No capture date", Tone.NEUTRAL)
                            if (e.file.state != IndexState.READY) indexingTag(e.file).let { (l, t) -> StateTag(l, t) }
                        },
                        onClick = { viewing = ViewedFile(e.file.id, e.file.name, "On this phone", e.file.ext, null, true) },
                    )
                }
            }
        }
    }
    }
    viewing?.let { FileViewer(vm, it) { viewing = null } }
}
