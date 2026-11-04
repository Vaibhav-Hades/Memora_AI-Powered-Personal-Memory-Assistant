package com.memora.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memora.app.local.LocalFile

// Reusable building blocks. Screens compose these instead of styling Material parts one by one, so the app
// keeps one rhythm: whitespace and type for hierarchy, a card only where something is genuinely a separate object.

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = Space.sm)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = Space.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
    }
}

/** A label and its value on one row. [note] explains where the value comes from or how certain it is. */
@Composable
fun MetaRow(label: String, value: String?, note: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
        Column(Modifier.weight(0.62f)) {
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            else Text("Not available", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Collapsible group. Collapsed state shows just the title; the toggle is the whole header row. */
@Composable
fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    caption: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.touch)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse $title" else "Expand $title") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Column(Modifier.padding(bottom = Space.sm)) { content() }
    }
}

/** Text that can be long: shows [collapsedLines] and a "Show more" toggle only when it actually overflows. */
@Composable
fun SummaryPanel(label: String, text: String, modifier: Modifier = Modifier, note: String? = null, collapsedLines: Int = 4) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text, style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (overflows || expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
    }
}

/** Empty, first-run, unavailable and no-result states share one calm layout: say what is true, then offer the next step. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Space.xl, horizontal = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        MemoraMark(size = 56.dp, progress = 1f)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) Button(onClick = onAction, modifier = Modifier.heightIn(min = Space.touch)) { Text(actionLabel) }
        if (secondaryLabel != null && onSecondary != null) OutlinedButton(onClick = onSecondary, modifier = Modifier.heightIn(min = Space.touch)) { Text(secondaryLabel) }
    }
}

/** Placeholder block for content that is still loading. No animation: nothing here competes with the user's photos. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = "Loading" })
}

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> StatusColors.ok
    Tone.CAUTION -> StatusColors.warn
    Tone.ERROR -> MaterialTheme.colorScheme.error
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Small status pill: a dot and a label. The detail text is read aloud by TalkBack. */
@Composable
fun ConnectivityPill(status: ConnectivityStatus, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = toneColor(status.tone)
    val base = modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .semantics(mergeDescendants = true) { contentDescription = "${status.label}. ${status.detail}" }
    Row(base.padding(horizontal = Space.md, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color)
        Text(status.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small tag such as "Searchable offline". Colour is only a hint; the words carry the meaning. */
@Composable
fun StateTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Where a thumbnail comes from: a file on the laptop (fetched through the connection) or one stored on the phone. */
sealed interface ThumbSource {
    data class Remote(val fileId: Long, val ext: String) : ThumbSource
    data class Stored(val file: LocalFile) : ThumbSource
}

@Composable
fun FileThumb(vm: MemoraViewModel, source: ThumbSource, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val key = when (source) { is ThumbSource.Remote -> "r${source.fileId}"; is ThumbSource.Stored -> "l${source.file.sha256}" }
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        bitmap = when (source) {
            is ThumbSource.Remote -> vm.thumbnail(source.fileId)
            is ThumbSource.Stored -> vm.storedThumbnail(source.file)
        }
        loaded = true
    }
    val ext = when (source) { is ThumbSource.Remote -> source.ext; is ThumbSource.Stored -> source.file.ext }
    Box(
        modifier.then(if (size == Dp.Unspecified) Modifier else Modifier.size(size)).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        when {
            b != null -> Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            !loaded -> Skeleton(Modifier.fillMaxSize())
            else -> ExtBadge(ext)
        }
    }
}

/**
 * One piece of evidence behind an answer: what the file is, its date if known, the passage that supports the answer
 * and a way to open the original. [ref] is the citation number shown in the answer text.
 */
@Composable
fun EvidenceCard(
    vm: MemoraViewModel,
    source: ThumbSource,
    title: String,
    excerpt: String,
    modifier: Modifier = Modifier,
    ref: Int? = null,
    date: String? = null,
    where: String? = null,
    onOpen: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pressClickable("Open $title", onOpen)
            .padding(Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        FileThumb(vm, source, size = 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ref?.let { Text("[$it]", style = EvidenceStyle, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            val meta = listOfNotNull(date, where).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (excerpt.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "“${excerpt.replace('\n', ' ').trim()}”", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A row in the timeline and recent-memories lists: thumbnail, title, summary line, date. */
@Composable
fun MemoryRow(
    vm: MemoraViewModel, source: ThumbSource, title: String, modifier: Modifier = Modifier,
    subtitle: String? = null, date: String? = null, tags: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Space.touch).pressClickable("Open $title", onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(vm, source, size = 56.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                date?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                tags?.invoke()
            }
        }
    }
}
