package com.memora.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** One set of durations and curves, so every screen moves the same way. */
object Motion {
    const val Micro = 140
    const val Standard = 240
    const val Emphasized = 420

    /** Quick start, soft landing. */
    val Out = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val InOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** Duration to use now: zero when the user (or Android) asked for reduced motion. */
    fun ms(base: Int, reduced: Boolean) = if (reduced) 0 else base
}

/**
 * A click target with a small press response (a slight shrink). With reduced motion it only keeps the ripple.
 * Keeps the touch semantics of `clickable`, including the spoken action label.
 */
fun Modifier.pressClickable(label: String? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.975f else 1f,
        animationSpec = tween(Motion.Micro, easing = Motion.Out), label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
}

/** Fade-and-rise spec for content that appears. Reduced motion becomes a plain, short fade. */
@Composable
fun rememberAppearAlpha(visible: Boolean, delayMs: Int = 0): Float {
    val reduced = LocalReducedMotion.current
    val a by animateFloatAsState(
        if (visible) 1f else 0f,
        animationSpec = tween(if (reduced) Motion.Micro else Motion.Standard, delayMillis = if (reduced) 0 else delayMs, easing = Motion.Out),
        label = "appear",
    )
    return a
}

/** Fades (and rises a few dp) once when it first enters the screen. Under reduced motion it is a short fade only. */
fun Modifier.appearOnEnter(delayMs: Int = 0): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduced = LocalReducedMotion.current
    val a by animateFloatAsState(
        if (shown) 1f else 0f,
        animationSpec = tween(if (reduced) Motion.Micro else Motion.Standard, delayMillis = if (reduced) 0 else delayMs, easing = Motion.Out),
        label = "enter",
    )
    graphicsLayer { alpha = a; translationY = if (reduced) 0f else (1f - a) * 12.dp.toPx() }
}
