package com.memora.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Memora mark: four separate blocks that connect into one picture ("scattered information becomes a memory").
 * [progress] 0 draws the blocks apart and unlinked, 1 draws them assembled and connected. Deterministic: no randomness.
 */
@Composable
fun MemoraMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    progress: Float = 1f,
    link: Color = MaterialTheme.colorScheme.onBackground,
    description: String? = null,
) {
    val sem = if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { }
    Canvas(modifier.size(size).then(sem)) { drawMark(progress.coerceIn(0f, 1f), link) }
}

private data class Block(val color: Color, val x: Float, val y: Float, val s: Float, val fromX: Float, val fromY: Float)

// Positions in a 3.4 x 3.4 unit square; "from" is where each block starts before assembling.
private val BLOCKS = listOf(
    Block(Accent.Coral, 0.0f, 0.0f, 1.5f, -1.6f, -1.2f),
    Block(Accent.Yellow, 2.0f, 0.4f, 1.0f, 1.8f, -1.5f),
    Block(Accent.Sky, 0.3f, 2.1f, 1.0f, -1.4f, 1.7f),
    Block(Accent.Mint, 1.6f, 1.7f, 1.7f, 1.5f, 1.4f),
)

private fun DrawScope.drawMark(p: Float, link: Color) {
    val unit = size.minDimension / 3.4f
    val centers = BLOCKS.map { b ->
        val ox = b.fromX * (1f - p) * unit
        val oy = b.fromY * (1f - p) * unit
        val tl = Offset(b.x * unit + ox, b.y * unit + oy)
        drawRoundRect(b.color, tl, Size(b.s * unit, b.s * unit), CornerRadius(unit * 0.12f), alpha = 0.35f + 0.65f * p)
        Offset(tl.x + b.s * unit / 2, tl.y + b.s * unit / 2)
    }
    if (p > 0.55f) {
        val a = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val stroke = Stroke(width = unit * 0.07f, cap = StrokeCap.Round)
        for ((i, j) in listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3)) drawLine(link.copy(alpha = 0.55f * a), centers[i], centers[j], stroke.width, StrokeCap.Round)
        centers.forEach { drawCircle(link.copy(alpha = a), radius = unit * 0.09f, center = it) }
    }
}

/** A slow, optional drift for decoration. Does nothing under reduced motion, and only runs while it is on screen. */
@Composable
fun GeoCluster(modifier: Modifier = Modifier, size: Dp = 96.dp, alpha: Float = 0.9f) {
    val reduced = LocalReducedMotion.current
    val p = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "geo")
        val v by t.animateFloat(0.94f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
        v
    }
    Box(modifier.size(size).graphicsAlpha(alpha)) { MemoraMark(size = size, progress = p) }
}

private fun Modifier.graphicsAlpha(a: Float): Modifier = this.alpha(a)

/**
 * A small "working" animation: coloured blocks that pulse in turn while something real is in progress.
 * Show it only while a request is actually running. Under reduced motion it is a still row of blocks.
 */
@Composable
fun ThinkingBlocks(label: String, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = listOf(Accent.Coral, Accent.Yellow, Accent.Sky, Accent.Mint)
    val t = rememberInfiniteTransition(label = "think")
    val phase by t.animateFloat(0f, 4f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Canvas(Modifier.size(width = 64.dp, height = 24.dp)) {
            val cell = size.height * 0.62f
            val gap = (size.width - cell * 4) / 3f
            colors.forEachIndexed { i, c ->
                // Each block rises and brightens as the phase passes it.
                val d = if (reduced) 0f else ((phase - i + 4f) % 4f).let { if (it < 1f) 1f - kotlin.math.abs(it - 0.5f) * 2f else 0f }
                val x = i * (cell + gap)
                val y = (size.height - cell) / 2f - d * size.height * 0.16f
                drawRoundRect(c, Offset(x, y), Size(cell, cell), CornerRadius(cell * 0.18f), alpha = 0.55f + 0.45f * d)
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A thin row of four small blocks used as a section divider. */
@Composable
fun GeoDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 56.dp, height = 8.dp).clearAndSetSemantics { }) {
        val cell = size.height
        listOf(Accent.Coral, Accent.Yellow, Accent.Sky, Accent.Mint).forEachIndexed { i, c ->
            drawRoundRect(c, Offset(i * (cell + cell * 0.8f), 0f), Size(cell, cell), CornerRadius(cell * 0.2f))
        }
    }
}

/**
 * The first-launch brand reveal: blocks assemble into the mark, the wordmark and tagline appear, then it hands over.
 * Tap anywhere to skip. Shown once; Settings can replay it. It never blocks the app for longer than about two seconds.
 */
@Composable
fun BrandReveal(onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val assemble = remember { Animatable(if (reduced) 1f else 0f) }
    var text by remember { mutableFloatStateOf(if (reduced) 1f else 0f) }
    val textAlpha = rememberAppearAlpha(text > 0.5f)
    var leaving by remember { mutableStateOf(false) }
    val exit = rememberAppearAlpha(!leaving)
    var finished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun finish() {
        if (finished) return
        finished = true
        leaving = true
        scope.launch { delay(Motion.Standard.toLong()); onDone() }
    }

    LaunchedEffect(Unit) {
        if (!reduced) assemble.animateTo(1f, tween(Motion.Emphasized + 280, easing = Motion.Out))
        text = 1f
        delay(if (reduced) 700L else 900L)
        finish()
    }

    Box(
        Modifier.fillMaxSize().background(Accent.Teal).graphicsAlpha(exit)
            .clickable(onClickLabel = "Skip the intro") { finish() }
            .semantics(mergeDescendants = true) { contentDescription = "Memora. Your visual memory, understood." },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            MemoraMark(size = 132.dp, progress = assemble.value, link = Accent.Ivory)
            Text("MEMORA", style = MaterialTheme.typography.displayMedium, color = Accent.Ivory, modifier = Modifier.graphicsAlpha(textAlpha))
            Text(
                "Your visual memory, understood.", style = MaterialTheme.typography.bodyLarge, color = Accent.Mint,
                modifier = Modifier.graphicsAlpha(textAlpha).padding(horizontal = Space.xl),
            )
        }
    }
}
