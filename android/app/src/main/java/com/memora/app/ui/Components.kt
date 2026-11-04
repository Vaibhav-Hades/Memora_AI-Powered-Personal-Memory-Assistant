package com.memora.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private val IMAGE_EXTS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff")

/** Preview of a file that lives on the laptop: a thumbnail when the server has one, otherwise a type badge. */
@Composable
fun RemoteThumbnail(vm: MemoraViewModel, fileId: Long, ext: String, size: Dp = 72.dp) {
    var bitmap by remember(fileId) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(fileId) { mutableStateOf(false) }
    LaunchedEffect(fileId) {
        bitmap = vm.thumbnail(fileId)
        loaded = true
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        when {
            b != null -> Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            !loaded -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> ExtBadge(ext)
        }
    }
}

@Composable
fun ExtBadge(ext: String) {
    Text(
        ext.removePrefix(".").uppercase().ifEmpty { "FILE" },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Rounded message box for errors and notices. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the viewer needs to know about a source file. */
data class ViewedFile(val fileId: Long, val name: String, val path: String, val ext: String, val text: String?, val local: Boolean = false)
