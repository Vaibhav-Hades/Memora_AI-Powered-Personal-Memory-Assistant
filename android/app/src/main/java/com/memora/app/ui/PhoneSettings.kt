package com.memora.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.memora.app.local.ModelSpec
import com.memora.app.local.ModelStatus
import com.memora.app.local.OperatingMode

/** Where questions are answered: the user's choice, never changed silently. */
@Composable
fun ModeSection(vm: MemoraViewModel) {
    val mode by vm.local.mode.collectAsState()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OperatingMode.entries.forEach { m ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = mode == m, role = Role.RadioButton, onClick = { vm.setMode(m) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = mode == m, onClick = null)
                Column {
                    Text(m.label, fontWeight = FontWeight.SemiBold)
                    Text(m.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Install, import or remove the on-device AI model. Downloads happen only after the user confirms. */
@Composable
fun ModelSection(vm: MemoraViewModel) {
    val ui by vm.local.model.collectAsState()
    var confirm by remember { mutableStateOf<ModelSpec?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm.local::importModel) }

    run {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = ui.status) {
                is ModelStatus.Installed -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(StatusColors.ok)
                        Text("Installed: ${s.label}", fontWeight = FontWeight.SemiBold)
                    }
                    Text("Works with no internet and no laptop. It loads the first time you ask a question.", style = MaterialTheme.typography.bodySmall)
                    vm.local.modelProblem?.let { Notice(it, error = true) }
                    TextButton(onClick = vm.local::removeModel) { Text("Remove model from this phone") }
                }
                else -> {
                    Text(
                        "No on-device AI model is installed, so the phone can search its files but can't write answers offline. " +
                            "Install one below (needs internet once), or copy a .litertlm model file to the phone and import it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (ui.busy) {
                LinearProgressIndicator(
                    progress = { if (ui.total > 0) (ui.done.toFloat() / ui.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${ui.done / (1024 * 1024)} of ${ui.total / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
            }
            ui.message?.let { Notice(it, error = it.contains("fail", true) || it.contains("Not enough", true) || it.contains("interrupted", true)) }

            if (ui.status !is ModelStatus.Installed || !ui.busy) {
                vm.local.catalog.forEach { spec ->
                    Column {
                        Text(spec.label, fontWeight = FontWeight.Medium)
                        Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { confirm = spec }, enabled = !ui.busy) { Text("Download (${spec.sizeBytes / (1024 * 1024)} MB)") }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.busy) { Text("Import a .litertlm file…") }
            }
        }
    }

    confirm?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download ${spec.label}?") },
            text = {
                Text(
                    "This downloads ${spec.sizeBytes / (1024 * 1024)} MB from huggingface.co over the internet (use Wi-Fi). " +
                        "The model is then stored privately on this phone and used without internet. " +
                        "The download resumes if it is interrupted. Your documents are not uploaded.",
                )
            },
            confirmButton = { Button(onClick = { vm.local.downloadModel(spec); confirm = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
