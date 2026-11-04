package com.memora.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.memora.app.data.LaptopRoute
import com.memora.app.data.ServerSettings
import com.memora.app.data.TailscaleConfig
import com.memora.app.local.ExifDates
import com.memora.app.local.IndexState

private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.1f MB".format(bytes / 1048576.0)

@Composable
fun SettingsScreen(vm: MemoraViewModel, onReplayIntro: () -> Unit = {}) {
    val connection by vm.connection.collectAsState()
    val mode by vm.local.mode.collectAsState()
    val files by vm.local.files.collectAsState()
    val ts by vm.tailscale.collectAsState()
    val tsTest by vm.tailscaleTest.collectAsState()
    val last by vm.lastSuccess.collectAsState()
    val saved = vm.savedConfig
    var host by rememberSaveable { mutableStateOf(saved?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((saved?.port ?: ServerSettings.DEFAULT_PORT).toString()) }
    var code by rememberSaveable { mutableStateOf(saved?.code ?: vm.savedCode) }
    var tsHost by rememberSaveable { mutableStateOf(ts.host) }
    var tsPort by rememberSaveable { mutableStateOf(ts.port.toString()) }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.lg, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        ScreenTitle("Settings")
        val status = Connectivity.describe(mode, connection, vm.hasLaptopRoute())
        ConnectivityPill(status)
        Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.sm))

        ExpandableSection("Appearance", caption = LocalUiSettings.current?.theme?.label ?: "Follow phone") {
            val ui = LocalUiSettings.current
            if (ui != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Theme", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        ThemeChoice.entries.forEach { choice ->
                            androidx.compose.material3.FilterChip(
                                selected = ui.theme == choice, onClick = { ui.chooseTheme(choice) }, label = { Text(choice.label) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reduce motion", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (ui.systemReducesMotion()) "Android's \"remove animations\" is on, so motion is already reduced."
                                else "Replaces transitions and decorative animation with simple fades.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = ui.reduceMotion, onCheckedChange = { ui.chooseReduceMotion(it) })
                    }
                    OutlinedButton(onClick = onReplayIntro, modifier = Modifier.heightIn(min = Space.touch)) { Text("Replay the intro") }
                }
            }
        }

        ExpandableSection("Background sync", caption = "Keep laptop files on this phone") { BackgroundSyncSection(mode) }

        ExpandableSection("AI model", caption = "The model that answers on this phone") { ModelSection(vm) }

        ExpandableSection("Offline operation", initiallyExpanded = true, caption = "Where questions are answered") { ModeSection(vm) }

        ExpandableSection("Laptop connection", initiallyExpanded = true, caption = "Same Wi-Fi") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(
                    "Memora's laptop AI runs on your Windows laptop. Start it there with “python -m memora --lan --port 8010”, keep this phone on " +
                        "the same Wi-Fi, and copy the address and pairing code it shows (also in the laptop's Memora page → Library).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Laptop address (IP)") }, placeholder = { Text("192.168.1.20") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase() }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pairing code") }, placeholder = { Text("ABCDE-FGH23") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                Button(
                    onClick = { focus.clearFocus(); vm.connect(host, port, code) },
                    enabled = connection !is Connection.Checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch),
                ) { Text("Test & save connection") }
                ConnectionResult(vm, connection)
                Text(
                    "• Don't use “localhost” or 127.0.0.1 — on the phone that means the phone itself.\n" +
                        "• Guest, campus and some office networks block devices from reaching each other; a home network or the phone's hotspot works.\n" +
                        "• Windows Firewall must allow the Memora port on private networks (see the README).\n" +
                        "• The laptop only accepts paired devices on your local network" + (if (ts.enabled) " or your Tailscale network." else "."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ExpandableSection("Tailscale", initiallyExpanded = ts.enabled, caption = if (ts.enabled) "Remote access is on" else "Optional: reach the laptop from other networks") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Tailscale", style = MaterialTheme.typography.bodyLarge)
                        Text("Try the laptop's Tailscale address when the Wi-Fi route isn't available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = ts.enabled, onCheckedChange = vm::setTailscaleEnabled,
                        enabled = tsHost.isNotBlank() || ts.enabled,
                    )
                }
                if (tsHost.isBlank() && !ts.enabled) {
                    Text("Enter the laptop's Tailscale address and test it first. Saving a working test turns this on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = tsHost, onValueChange = { tsHost = it.trim() }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Laptop's Tailscale address") }, placeholder = { Text("100.101.102.103 or laptop.tailnet.ts.net") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = tsPort, onValueChange = { tsPort = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") }, supportingText = { Text("Memora uses ${TailscaleConfig.DEFAULT_PORT} by default") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (saved == null) {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.uppercase() }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pairing code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    )
                } else {
                    Text("Uses the same pairing code as the Wi-Fi connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { focus.clearFocus(); vm.testTailscale(tsHost, tsPort, code) },
                    enabled = tsTest !is TailscaleTest.Testing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch),
                ) { Text("Test connection") }
                when (val t = tsTest) {
                    TailscaleTest.Idle -> Unit
                    TailscaleTest.Testing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Contacting the laptop over Tailscale…")
                    }
                    is TailscaleTest.Ok -> Notice("Connected to ${t.serverName} through Tailscale. This address is saved.")
                    is TailscaleTest.Failed -> Notice(t.message, error = true)
                }
                last?.let { (route, at) ->
                    Text(
                        "Last successful connection: ${route.label}, ${ExifDates.formatInstant(at)}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "How to set it up\n" +
                        "1. Install the official Tailscale app on this phone and on the laptop, and sign in to the same account.\n" +
                        "2. On the laptop, start Memora with: python -m memora --lan --tailscale --port 8010\n" +
                        "3. Copy the laptop's Tailscale address (100.x.x.x, or its MagicDNS name) from the Tailscale app.\n" +
                        "4. Paste it above and tap Test connection.\n\n" +
                        "Tailscale must be connected on both devices, and the laptop must be on. It doesn't make files available when the laptop is off. " +
                        "“Phone only” mode never contacts the laptop, whatever is configured here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val ready = files.count { it.state == IndexState.READY }
        ExpandableSection("Library and indexing", caption = "$ready of ${files.size} files searchable offline") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                MetaRow("Stored on this phone", "${files.size}")
                MetaRow("Searchable offline", "$ready")
                MetaRow("Needs attention", "${files.count { it.state == IndexState.FAILED || it.state == IndexState.PENDING }}")
                OutlinedButton(onClick = vm.local::retryFailed, modifier = Modifier.heightIn(min = Space.touch)) { Text("Retry failed / pending files") }
            }
        }

        ExpandableSection("Downloads and synchronization", caption = "Checked, resumable, never destructive") {
            Text(
                "Files downloaded from the laptop are checked against the laptop's SHA-256 checksum before they are stored. " +
                    "An interrupted download resumes where it stopped. If a file disappears from the laptop, your copy on the phone is kept. " +
                    "Downloads use whichever route is connected right now: Wi-Fi or Tailscale.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExpandableSection("Storage") {
            MetaRow("Files on this phone", mb(files.sumOf { it.size }))
            MetaRow("AI model", vm.local.modelFile()?.let { mb(it.length()) } ?: "None installed")
        }

        ExpandableSection("Privacy and diagnostics") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    "Files, their text and the offline AI model live in this app's private storage. Phone only mode sends nothing anywhere. " +
                        "Memora doesn't collect analytics. Question history stays on the phone.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetaRow("Current mode", mode.label)
                MetaRow("Connection", status.label, note = status.detail)
                MetaRow("Tailscale", when {
                    !ts.enabled -> "Off"
                    (connection as? Connection.Connected)?.route == LaptopRoute.TAILSCALE -> "In use"
                    else -> "On, not in use"
                })
            }
        }

        ExpandableSection("About Memora") {
            Text("Memora $version", style = MaterialTheme.typography.titleSmall)
            Text("We remember taking the photo. We forget why we took it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your visual memory, understood.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionResult(vm: MemoraViewModel, connection: Connection) {
    when (val c = connection) {
        Connection.NotConfigured -> Notice(if (vm.hasLaptopRoute()) "Not checked. Phone only mode doesn't contact the laptop; press Test & save connection to check it." else "Not set up yet.")
        Connection.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Contacting the laptop…")
        }
        is Connection.Failed -> Notice(c.message, error = true)
        is Connection.Connected -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StatusDot(StatusColors.ok)
                Text("Connected to ${c.info.serverName} via ${c.route.label}", style = MaterialTheme.typography.titleSmall)
            }
            Text("Memora ${c.info.version}", style = MaterialTheme.typography.bodySmall)
            c.health?.let { h ->
                Text("${h.filesIndexed} files indexed on the laptop", style = MaterialTheme.typography.bodySmall)
                if (h.modelOk) Text("Laptop AI ready", style = MaterialTheme.typography.bodySmall, color = StatusColors.ok)
                else Text("AI unavailable on the laptop: ${h.modelError ?: "check Ollama"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BackgroundSyncSection(mode: com.memora.app.local.OperatingMode) {
    val context = LocalContext.current
    val prefs = remember { com.memora.app.local.SyncPrefs(context.applicationContext) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    val phoneOnly = mode == com.memora.app.local.OperatingMode.PHONE_ONLY
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sync laptop files automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (phoneOnly) "Phone only mode never contacts the laptop, so nothing syncs."
                    else "About every 6 hours, on Wi-Fi with enough battery, when the laptop is reachable. Up to 20 files (25 MB each) per run. Nothing is deleted from the phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, enabled = !phoneOnly, onCheckedChange = {
                enabled = it; prefs.enabled = it
                if (it) com.memora.app.local.SyncScheduler.enable(context.applicationContext) else com.memora.app.local.SyncScheduler.disable(context.applicationContext)
            })
        }
        val last = prefs.lastRunAt
        Text(
            if (last == 0L) "Not run yet." else "Last run: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(last)) +
                ". " + prefs.lastMessage + if (prefs.pendingRetry) " Will retry when the laptop is reachable." else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { com.memora.app.local.SyncScheduler.syncNow(context.applicationContext) }, enabled = !phoneOnly,
            modifier = Modifier.heightIn(min = Space.touch),
        ) { Text("Sync now") }
    }
}
