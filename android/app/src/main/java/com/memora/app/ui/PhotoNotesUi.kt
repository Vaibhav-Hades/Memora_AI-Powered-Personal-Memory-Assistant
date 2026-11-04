package com.memora.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.memora.app.local.PhotoNote
import com.memora.app.local.PhotoText
import com.memora.app.local.PhotoUserFields
import kotlinx.coroutines.launch

/**
 * The user's own words about a photo. These are saved on the phone, searched offline, and never overwritten by
 * indexing or by a download from the laptop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoNotesSection(vm: MemoraViewModel, fileId: Long, note: PhotoNote?) {
    val scope = rememberCoroutineScope()
    val saved = note?.user ?: PhotoUserFields()
    var caption by remember(fileId, saved) { mutableStateOf(saved.caption) }
    var notes by remember(fileId, saved) { mutableStateOf(saved.notes) }
    var tags by remember(fileId, saved) { mutableStateOf(saved.tags.joinToString(", ")) }
    var event by remember(fileId, saved) { mutableStateOf(saved.eventTitle) }
    var eventDate by remember(fileId, saved) { mutableStateOf(saved.eventDate) }
    var place by remember(fileId, saved) { mutableStateOf(saved.locationLabel) }
    var message by remember(fileId) { mutableStateOf<String?>(null) }
    var isError by remember(fileId) { mutableStateOf(false) }

    SectionHeader("Your notes")
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            "Written by you and kept on this phone. They're searchable offline and are never replaced by indexing or sync.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(caption, { caption = it.take(300) }, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
        OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(event, { event = it.take(200) }, label = { Text("Event") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            eventDate, { eventDate = it.take(10) }, label = { Text("Event date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            supportingText = { Text("This is your own date. It is not the date the photo was taken.") },
        )
        OutlinedTextField(place, { place = it.take(200) }, label = { Text("Place name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(
                onClick = {
                    scope.launch {
                        val err = vm.local.savePhotoFields(
                            fileId, PhotoUserFields(caption, notes, PhotoText.parseTags(tags), event, eventDate, place, saved.favorite),
                        )
                        isError = err != null
                        message = err ?: "Saved. This photo is now searchable by what you wrote."
                    }
                },
                modifier = Modifier.heightIn(min = Space.touch),
            ) { Text("Save notes") }
            OutlinedButton(onClick = { vm.local.setFavorite(fileId, !saved.favorite) }, modifier = Modifier.heightIn(min = Space.touch)) {
                Text(if (saved.favorite) "Remove from favourites" else "Add to favourites")
            }
        }
        message?.let { Notice(it, error = isError) }
    }
}
