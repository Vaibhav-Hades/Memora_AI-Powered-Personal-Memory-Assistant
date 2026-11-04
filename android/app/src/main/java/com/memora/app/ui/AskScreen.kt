package com.memora.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.memora.app.data.Answer
import com.memora.app.data.SearchHit
import com.memora.app.data.Source
import com.memora.app.local.AnswerLayout
import com.memora.app.local.OperatingMode
import com.memora.app.local.TextFacts

private val EXAMPLES = listOf(
    "Find the photo of my warranty card",
    "Which product has a two-year warranty, and where is its invoice?",
    "How much did I pay for the headphones?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(vm: MemoraViewModel, onOpenSettings: () -> Unit) {
    val state by vm.ask.collectAsState()
    val connection by vm.connection.collectAsState()
    val mode by vm.local.mode.collectAsState()
    val focus = LocalFocusManager.current
    var viewing by remember { mutableStateOf<ViewedFile?>(null) }
    val submit = { q: String -> focus.clearFocus(); vm.submit(q) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            Column {
                ScreenTitle("Search", "Ask a question, or look for a file.")
                OutlinedTextField(
                    value = state.question,
                    onValueChange = vm::setQuestion,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask anything about your memories...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(state.question) }),
                    trailingIcon = {
                        IconButton(onClick = { submit(state.question) }, enabled = state.question.isNotBlank() && !state.answering) {
                            Icon(Icons.Default.Search, contentDescription = "Ask")
                        }
                    },
                )
            }
        }

        if (!vm.hasLaptopRoute() && mode != OperatingMode.PHONE_ONLY) {
            item {
                Notice("Using the files and AI on this phone. Connect a laptop in Settings to also search its files.")
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }

        val idle = state.hits.isEmpty() && state.answer == null && !state.searching && !state.answering &&
            state.searchError == null && state.answerError == null
        if (idle) {
            item {
                Text("Try asking", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    EXAMPLES.forEach { ex -> AssistChip(onClick = { vm.setQuestion(ex); submit(ex) }, label = { Text(ex) }) }
                }
            }
        }

        if (state.answering) {
            // Shown only while a real request is running; it disappears the moment the answer, an error or a cancel arrives.
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ThinkingBlocks(
                        if (state.where?.contains("phone") == true) "Reading the matching files on this phone…" else "Reading the matching files on your laptop…",
                        Modifier.weight(1f).padding(vertical = Space.sm),
                    )
                    TextButton(onClick = vm::cancelAsk) { Text("Stop") }
                }
            }
        }
        state.answerError?.let { err ->
            item {
                Column {
                    Notice(err, error = true)
                    if (state.question.isNotBlank() && !state.answering) TextButton(onClick = { submit(state.question) }) { Text("Try again") }
                }
            }
        }

        state.answer?.let { answer ->
            item { Column(Modifier.appearOnEnter()) { AnswerBlock(answer, state.where) } }
            val cited = answer.sources.filter { it.cited }
            val facts = if (answer.sufficient) cited.flatMap { s -> TextFacts.keyValues(s.snippet, 3).map { s.ref to it } }.take(4) else emptyList()
            if (facts.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader("Key facts")
                        facts.forEach { (ref, f) -> MetaRow(f.label, f.value, note = "From the text of [$ref]") }
                    }
                }
            }
            if (cited.isNotEmpty()) {
                item { SectionHeader(if (cited.size == 1) "Evidence" else "Evidence · ${cited.size} files") }
                items(cited, key = { "src-${it.ref}" }) { s -> EvidenceItem(vm, s) { viewing = ViewedFile(s.fileId, s.name, s.path, s.ext, s.snippet, s.local) } }
            }
        }

        if (state.searching && !state.answering) {
            item { ThinkingBlocks("Searching your files…", Modifier.padding(vertical = Space.sm)) }
        }
        state.searchError?.takeIf { it != state.answerError }?.let { item { Notice(it, error = true) } } // the same failure is not shown twice
        val citedIds = state.answer?.sources?.filter { it.cited }?.map { it.fileId }?.toSet().orEmpty()
        val others = state.hits.filter { it.fileId !in citedIds }
        if (others.isNotEmpty()) {
            item {
                SectionHeader(
                    when {
                        state.answer == null -> "Matching files"
                        state.answer?.sufficient == false -> "Closest matches"
                        else -> "Related memories"
                    },
                )
            }
            items(others, key = { "hit-${it.fileId}-${it.local}" }) { h -> HitItem(vm, h) { viewing = ViewedFile(h.fileId, h.name, h.path, h.ext, h.text, h.local) } }
        } else if (!state.searching && state.searchError == null && state.question.isNotBlank() && state.hits.isEmpty() && !idle && !state.answering) {
            item {
                EmptyState(
                    "No matching files",
                    "Nothing in ${if (state.where?.contains("phone") == true) "this phone's searchable files" else "the indexed files"} matches that. Try different words, or import more files.",
                )
            }
        }
    }

    viewing?.let { FileViewer(vm, it) { viewing = null } }
}

/** The answer first, large; then the explanation. Where it was produced is always stated. Uncertain answers are marked as such. */
@Composable
private fun AnswerBlock(answer: Answer, where: String?) {
    val weak = !answer.sufficient
    val parts = remember(answer.text) { AnswerLayout.split(answer.text) }
    val accent = MaterialTheme.colorScheme.primary
    fun styled(text: String) = buildAnnotatedString {
        // Citation markers like [2] are shown bold so they can be matched to the numbered evidence below.
        Regex("\\[(\\d+)]|[^\\[]+|\\[").findAll(text).forEach { m ->
            if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append("[${m.groupValues[1]}]") }
            else append(m.value)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (weak) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StateTag("Not enough evidence", Tone.CAUTION)
            }
            Text(
                "Memora couldn't find enough in your files to answer this confidently. Here is what it found:",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(styled(parts.direct), style = if (weak) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall)
        if (parts.explanation.isNotEmpty()) Text(styled(parts.explanation), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        where?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!weak) {
            Text(
                "Based on the files below. Check the originals for anything important.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A short, honest date for a file stored on the phone, or null for laptop files (their metadata isn't downloaded). */
@Composable
private fun dateLabel(vm: MemoraViewModel, fileId: Long, local: Boolean): State<String?> = produceState<String?>(null, fileId, local) {
    value = if (!local) null else vm.local.file(fileId)?.let { f ->
        vm.local.timeline(listOf(f)).firstOrNull()?.let { "${it.kind.label} ${ExifDatesShort.format(it.date)}" }
    }
}

@Composable
private fun EvidenceItem(vm: MemoraViewModel, s: Source, onOpen: () -> Unit) {
    val date by dateLabel(vm, s.fileId, s.local)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        EvidenceCard(
            vm, thumbSource(vm, s.fileId, s.ext, s.local), s.name, s.snippet, ref = s.ref, date = date,
            where = if (s.local) "On this phone" else "On the laptop", onOpen = onOpen,
        )
        DownloadControls(vm, s.fileId, s.local)
    }
}

@Composable
private fun HitItem(vm: MemoraViewModel, h: SearchHit, onOpen: () -> Unit) {
    val date by dateLabel(vm, h.fileId, h.local)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MemoryRow(
            vm, thumbSource(vm, h.fileId, h.ext, h.local), h.name, subtitle = h.text, date = date ?: if (h.local) "On this phone" else "On the laptop",
            onClick = onOpen,
        )
        DownloadControls(vm, h.fileId, h.local)
    }
}

private fun thumbSource(vm: MemoraViewModel, fileId: Long, ext: String, local: Boolean): ThumbSource =
    if (local) vm.local.file(fileId)?.let { ThumbSource.Stored(it) } ?: ThumbSource.Remote(-1, ext) else ThumbSource.Remote(fileId, ext)

/** Download state for a laptop file shown in results. Nothing is shown when no action is possible. */
@Composable
private fun DownloadControls(vm: MemoraViewModel, fileId: Long, local: Boolean) {
    if (local) return
    val transfers by vm.local.transfers.collectAsState()
    val connection by vm.connection.collectAsState()
    val context = LocalContext.current
    val t = transfers[fileId]
    if ((t == null || t.failed) && connection is Connection.Connected) {
        TextButton(onClick = { vm.local.downloadById(fileId) }) { Text(if (t?.failed == true) "Retry download" else "Download to phone") }
    }
    t?.let {
        TransferStatus(
            it,
            onMakeSearchable = it.localId?.let { id -> { vm.local.indexOne(id) } },
            onOpen = it.localId?.let { id -> { vm.local.file(id)?.let { f -> openStored(context, vm, f) } } },
        )
    }
}
