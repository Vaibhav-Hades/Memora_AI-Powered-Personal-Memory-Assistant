package com.memora.app.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.memora.app.data.FileInfo
import com.memora.app.local.ExifDates
import com.memora.app.local.IndexState
import com.memora.app.local.LocalFile
import com.memora.app.local.PhotoMetadata
import com.memora.app.local.RelatedMemory
import com.memora.app.local.TextFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "heic")

private fun humanSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}

private fun kindLabel(ext: String): String = when (ext.lowercase()) {
    "pdf" -> "PDF document"
    "docx" -> "Word document"
    "txt", "md", "log" -> "Text file"
    "csv", "tsv" -> "Spreadsheet data"
    "json", "xml", "html" -> "Data file"
    in PHOTO_EXTS -> "Photo"
    else -> "File"
}

/** The words shown for where a stored file stands, and how much to trust it as offline-searchable. */
fun indexingTag(f: LocalFile): Pair<String, Tone> = when (f.state) {
    IndexState.READY -> "Searchable offline" to Tone.OK
    IndexState.PENDING -> "Stored, not searchable yet" to Tone.CAUTION
    IndexState.EXTRACTING, IndexState.INDEXING -> "Indexing…" to Tone.NEUTRAL
    IndexState.FAILED -> "Indexing failed" to Tone.ERROR
    IndexState.UNSUPPORTED -> "Stored, text not searchable" to Tone.NEUTRAL
}

/**
 * Full-screen detail for a photo or a document. Photos lead with the picture; documents lead with what the file says.
 * Everything shown is either read from the file itself or labelled with where it came from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileViewer(vm: MemoraViewModel, file: ViewedFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by vm.connection.collectAsState()
    val transfers by vm.local.transfers.collectAsState()
    val files by vm.local.files.collectAsState()
    val laptopUp = connection is Connection.Connected

    val isPhoto = file.ext.lowercase() in PHOTO_EXTS
    val stored: LocalFile? = if (file.local) files.firstOrNull { it.id == file.fileId } else null
    val transfer = if (file.local) null else transfers[file.fileId]

    var image by remember(file.fileId, file.local) { mutableStateOf<Bitmap?>(null) }
    var imageLoading by remember(file.fileId, file.local) { mutableStateOf(true) }
    var meta by remember(file.fileId, file.local) { mutableStateOf<PhotoMetadata?>(null) }
    var text by remember(file.fileId, file.local) { mutableStateOf(file.text.orEmpty()) }
    var info by remember(file.fileId, file.local) { mutableStateOf<FileInfo?>(null) }
    var related by remember(file.fileId, file.local) { mutableStateOf<List<RelatedMemory>>(emptyList()) }
    var note by remember(file.fileId, file.local) { mutableStateOf<String?>(null) }
    val photoNote = if (isPhoto && stored != null) {
        // Re-read after every library change (a save, a download, an index run), so the form shows what is stored.
        files.size.let { vm.local.photoNote(stored.id) }
    } else null
    var zoom by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val metaAlpha = rememberAppearAlpha(!imageLoading, delayMs = 120)
    val reducedMotion = LocalReducedMotion.current
    val imageScale by androidx.compose.animation.core.animateFloatAsState(
        if (image != null || reducedMotion) 1f else 0.94f,
        androidx.compose.animation.core.tween(Motion.ms(Motion.Emphasized, reducedMotion), easing = Motion.Out), label = "imageIn",
    )
    val screenPx = LocalWindowInfo.current.containerSize.width.coerceAtLeast(720)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { u -> stored?.let { vm.local.export(it, u) } }
    }

    LaunchedEffect(file.fileId, file.local, stored?.sha256) {
        if (stored != null) {
            image = if (isPhoto) vm.storedThumbnail(stored, screenPx * 2) else null
            imageLoading = false
            withContext(Dispatchers.IO) {
                meta = if (isPhoto) vm.local.metadata(stored) else null
                // For a photo the text shown is what OCR read; the user's notes have their own section.
                val shown = if (isPhoto) vm.local.photoNote(stored.id)?.derived?.ocrText.orEmpty() else vm.local.extractedText(stored.id)
                text = shown.takeIf { it.isNotBlank() } ?: ""
                related = vm.local.related(stored)
            }
        } else if (!file.local) {
            image = if (isPhoto) vm.fullImage(file.fileId, screenPx * 2).getOrElse { note = it.message; null } else vm.thumbnail(file.fileId, 1024)
            imageLoading = false
            info = vm.fileInfo(file.fileId)
        } else {
            imageLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Space.touch)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close and go back")
                    }
                    Text(kindLabel(file.ext), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.lg).padding(bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    // 1. The memory itself.
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 460.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .let { if (image != null && isPhoto) it.clickable(role = Role.Button, onClickLabel = "View full screen") { zoom = true } else it },
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = image
                        when {
                            imageLoading -> Skeleton(Modifier.fillMaxWidth().heightIn(min = 140.dp))
                            b != null -> Image(
                                b.asImageBitmap(), contentDescription = file.name,
                                contentScale = ContentScale.Fit, // whole picture, original proportions, never cropped
                                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).graphicsLayer { scaleX = imageScale; scaleY = imageScale },
                            )
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Space.xl)) {
                                ExtBadge(file.ext)
                                Text("No preview on this phone for this file type.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Everything below the picture fades in once the picture has settled.
                    Column(Modifier.graphicsLayer { alpha = metaAlpha }, verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    // 2. Title and where it lives.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(file.name, style = MaterialTheme.typography.headlineSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (stored != null) {
                                StateTag("On this phone", Tone.OK)
                                indexingTag(stored).let { (label, tone) -> StateTag(label, tone) }
                            } else if (transfer?.finished == true) StateTag("Downloaded", Tone.OK)
                            else StateTag("On the laptop", Tone.NEUTRAL)
                        }
                        if (stored?.state == IndexState.UNSUPPORTED) Notice(stored.error ?: "The file is stored, but its contents can't be searched on the phone.")
                        if (stored?.state == IndexState.FAILED) Notice(stored.error ?: "Indexing failed.", error = true)
                    }

                    // 3. Cited passage (when opened from an answer), then description / summary.
                    if (!file.text.isNullOrBlank() && file.text != text) {
                        SummaryPanel("Passage Memora used", file.text, note = "Text from this file that supports the answer.")
                    }
                    info?.summary?.let {
                        SummaryPanel("AI description", it, note = "Written by the laptop's AI. It can be wrong, so check it against the original.")
                    }
                    if (isPhoto && stored != null) {
                        val laptopText = photoNote?.derived?.laptopSummary?.takeIf { it.isNotBlank() } ?: photoNote?.derived?.laptopCaption?.takeIf { it.isNotBlank() }
                        if (laptopText != null) {
                            SummaryPanel("Description from the laptop", laptopText, note = "Written by the laptop's AI when you downloaded this photo. It can be wrong, so check it against the original.")
                        } else {
                            Text(
                                "This phone reads the text in photos but doesn't describe what they show. Download the photo from the laptop to bring its AI description, or write your own notes below.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (stored.state == IndexState.READY || stored.state == IndexState.FAILED) PhotoNotesSection(vm, stored.id, photoNote)
                    }

                    // 4. Date & time: only what the file records, each with its source.
                    DateSection(stored, meta, isPhoto)

                    if (isPhoto) {
                        SectionHeader("Location")
                        val coords = meta?.coordinates
                        MetaRow("Where", coords, note = if (coords != null) "From the photo's GPS metadata. Shown as coordinates; no map lookup is made." else null)
                    }

                    // 5. Text in the file.
                    if (text.isNotBlank()) {
                        val facts = remember(text) { TextFacts.keyValues(text) }
                        val dates = remember(text) { TextFacts.datesMentioned(text) }
                        val amounts = remember(text) { TextFacts.amountsMentioned(text) }
                        if (facts.isNotEmpty() || dates.isNotEmpty() || amounts.isNotEmpty()) {
                            SectionHeader("Details in the text")
                            facts.forEach { MetaRow(it.label, it.value) }
                            if (dates.isNotEmpty()) MetaRow("Dates mentioned", dates.joinToString("\n"), note = "Exactly as written in the text.")
                            if (amounts.isNotEmpty()) MetaRow("Amounts mentioned", amounts.joinToString("\n"), note = "Exactly as written in the text.")
                            Text("Read directly from the extracted text, not guessed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ExpandableSection(
                            if (isPhoto) "Text in the image" else "Document text",
                            initiallyExpanded = !isPhoto && text.length < 1500,
                            caption = if (isPhoto) "Read from the picture (OCR). May contain mistakes." else null,
                        ) {
                            Text(text, style = if (isPhoto) EvidenceStyle else MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    } else if (stored != null && stored.state == IndexState.READY) {
                        Text("No text was found in this file.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 6. Related memories.
                    if (related.isNotEmpty()) {
                        SectionHeader("Related memories")
                        related.forEach { r ->
                            MemoryRow(
                                vm, ThumbSource.Stored(r.file), r.file.name,
                                subtitle = "Shares: ${r.sharedTerms.joinToString(", ")}",
                                tags = { StateTag("Inferred from similar text", Tone.NEUTRAL) },
                                onClick = { openStored(context, vm, r.file) },
                            )
                        }
                    }

                    // 7. Technical details, kept out of the way.
                    ExpandableSection("File details") {
                        MetaRow("Original filename", file.name)
                        MetaRow("File type", stored?.mime ?: file.ext.uppercase().ifEmpty { null })
                        MetaRow("File size", stored?.let { humanSize(it.size) })
                        if (isPhoto) MetaRow("Dimensions", meta?.let { m -> if (m.width != null && m.height != null) "${m.width} × ${m.height} px" else null })
                        MetaRow("Stored", if (stored != null) "On this phone (private app storage)" else "On the laptop")
                        MetaRow("Origin", stored?.origin?.let { if (it == "phone-import") "Imported on this phone" else "Downloaded from the laptop" })
                        MetaRow("Indexing", stored?.let { stateLabel(it) } ?: "Indexed on the laptop")
                        MetaRow("Download", when {
                            stored != null -> "Verified copy on the phone"
                            transfer?.failed == true -> "Failed"
                            transfer?.finished == true -> "Downloaded"
                            transfer != null -> "In progress"
                            else -> "Not downloaded"
                        })
                    }

                    transfer?.let { t ->
                        TransferStatus(t, onMakeSearchable = t.localId?.let { id -> { vm.local.indexOne(id) } },
                            onOpen = t.localId?.let { id -> { vm.local.file(id)?.let { f -> openStored(context, vm, f) } } })
                    }
                    note?.let { Notice(it) }
                    vm.local.notice.collectAsState().value?.let { Notice(it) }

                    // 8. Actions: only ones that can work right now.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        if (stored != null) {
                            Button(onClick = { openStored(context, vm, stored) }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Open original") }
                            vm.local.shareUri(stored)?.let { uri ->
                                OutlinedButton(
                                    onClick = {
                                        val send = Intent(Intent.ACTION_SEND).setType(stored.mime).putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(Intent.createChooser(send, stored.name))
                                    },
                                    modifier = Modifier.heightIn(min = Space.touch),
                                ) { Text("Share") }
                            }
                            OutlinedButton(onClick = { saver.launch(stored.name) }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Save a copy") }
                            if (stored.state == IndexState.PENDING) OutlinedButton(onClick = { vm.local.indexOne(stored.id) }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Make searchable offline") }
                            if (stored.state == IndexState.FAILED) OutlinedButton(onClick = { vm.local.indexOne(stored.id) }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Retry indexing") }
                            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = Space.touch)) {
                                Text("Remove from phone", color = MaterialTheme.colorScheme.error)
                            }
                        } else if (!file.local) {
                            val busy = transfer != null && !transfer.finished && !transfer.failed
                            if (laptopUp && !busy && transfer?.finished != true) {
                                Button(onClick = { vm.local.downloadById(file.fileId) }, modifier = Modifier.heightIn(min = Space.touch)) {
                                    Text(if (transfer?.failed == true) "Retry download" else "Download to phone")
                                }
                            }
                            if (laptopUp) OutlinedButton(onClick = { scope.launch { note = vm.revealOnLaptop(file.fileId) } }, modifier = Modifier.heightIn(min = Space.touch)) { Text("Show on laptop") }
                            if (!laptopUp) Notice("The laptop isn't connected, so this file can't be downloaded or shown there right now.")
                        }
                    }
                    }
                }
            }
        }
    }

    if (zoom) image?.let { ZoomDialog(it, file.name) { zoom = false } }
    if (confirmDelete && stored != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove from this phone?") },
            text = { Text("The copy stored on the phone and its search index are deleted. The original on the laptop is not touched.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.local.remove(stored.id); onDismiss() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Dates are shown as the file states them. A photo's capture time comes from its metadata; "added" is when Memora stored it. */
@Composable
private fun DateSection(stored: LocalFile?, meta: PhotoMetadata?, isPhoto: Boolean) {
    SectionHeader("Date & time")
    if (isPhoto) {
        val c = meta?.capture
        MetaRow("Captured", c?.let { ExifDates.formatDate(it.date) }, note = if (c != null) "From the photo's metadata" else "The photo doesn't record when it was taken")
        MetaRow("Capture time", c?.time)
        MetaRow("Time zone", c?.offset?.let { "UTC$it" }, note = if (c != null && c.offset == null) "Not recorded in the file; the time is shown as the camera wrote it" else null)
    }
    MetaRow("Added to Memora", stored?.let { ExifDates.formatInstant(it.importedAt) },
        note = if (stored != null) "When this phone stored the file. This is not when it was created." else "Download the file to see dates stored in it.")
    Text(
        "Memora shows only dates written in the file and never guesses when something happened.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Pinch to zoom, drag to pan, double-tap to reset. */
@Composable
private fun ZoomDialog(bitmap: Bitmap, description: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Box(
                Modifier.fillMaxSize().clipToBounds()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
            ) {
                Image(
                    bitmap.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close full screen", tint = Color.White)
            }
        }
    }
}
