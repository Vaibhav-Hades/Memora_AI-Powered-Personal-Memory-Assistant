package com.memora.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(val label: String) {
    Home("Home"), Memories("Memories"), Search("Search"), Library("Library"), Settings("Settings"),
}

@Composable
fun MemoraApp(vm: MemoraViewModel = viewModel()) {
    val connection by vm.connection.collectAsState()
    val mode by vm.local.mode.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val ui = LocalUiSettings.current
    val reduced = LocalReducedMotion.current
    // The brand reveal runs once, on the very first launch; Settings can replay it.
    var showIntro by rememberSaveable { mutableStateOf(ui != null && !ui.introSeen) }

    // Back from any section returns to Home before leaving the app.
    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Home has its own larger header; every other section keeps the connection status in view.
            if (tab != Tab.Home) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Memora", style = MaterialTheme.typography.titleLarge)
                    ConnectivityPill(Connectivity.describe(mode, connection, vm.hasLaptopRoute()), onClick = { tab = Tab.Settings })
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Home -> Icons.Default.Home
                                    Tab.Memories -> Icons.Default.DateRange
                                    Tab.Search -> Icons.Default.Search
                                    Tab.Library -> Icons.Default.List
                                    Tab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).then(if (tab == Tab.Home) Modifier.statusBarsPadding() else Modifier).imePadding()) {
            // Sections cross-fade (and drift a few pixels) so moving between them reads as one continuous place.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val d = Motion.ms(Motion.Standard, reduced)
                    val shift = if (reduced) 0 else 24
                    (fadeIn(tween(d, easing = Motion.Out)) + slideInHorizontally(tween(d, easing = Motion.Out)) { if (targetState.ordinal > initialState.ordinal) shift else -shift })
                        .togetherWith(fadeOut(tween(Motion.ms(Motion.Micro, reduced))))
                },
                label = "tab",
            ) { current ->
            when (current) {
                Tab.Home -> HomeScreen(
                    vm, onAsk = { tab = Tab.Search }, onOpenMemories = { tab = Tab.Memories },
                    onOpenLibrary = { tab = Tab.Library }, onOpenSettings = { tab = Tab.Settings },
                )
                Tab.Memories -> MemoriesScreen(vm, onOpenLibrary = { tab = Tab.Library })
                Tab.Search -> AskScreen(vm, onOpenSettings = { tab = Tab.Settings })
                Tab.Library -> LibraryScreen(vm)
                Tab.Settings -> SettingsScreen(vm, onReplayIntro = { showIntro = true })
            }
            }
        }
    }
    if (showIntro) BrandReveal(onDone = { showIntro = false; ui?.markIntroSeen() })
    }
}
