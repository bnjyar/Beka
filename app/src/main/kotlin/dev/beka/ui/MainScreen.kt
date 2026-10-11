package dev.beka.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.beka.BekaApp
import dev.beka.R
import dev.beka.SettingsActions
import dev.beka.memory.MemoryScreen
import dev.beka.rules.RulesScreen
import dev.beka.settings.SettingsScreen

object MainTab {
    const val HOME = 0
    const val MEMORY = 1
    const val RULES = 2
    const val SETTINGS = 3
}

private class TabSpec(val label: String, val icon: ImageVector)

@Composable
fun MainScreen(app: BekaApp, resumeTick: Int, actions: SettingsActions) {
    var tab by rememberSaveable { mutableIntStateOf(MainTab.HOME) }
    val tabs = listOf(
        TabSpec(stringResource(R.string.tab_home), BekaIcons.Home),
        TabSpec(stringResource(R.string.tab_memory), BekaIcons.Memory),
        TabSpec(stringResource(R.string.tab_proactive), BekaIcons.Bolt),
        TabSpec(stringResource(R.string.tab_settings), BekaIcons.Settings),
    )
    BackHandler(enabled = tab != MainTab.HOME) { tab = MainTab.HOME }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, spec ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(spec.icon, contentDescription = spec.label) },
                        label = { Text(spec.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
            when (tab) {
                MainTab.HOME -> HomeScreen(app, resumeTick, actions, onOpenTab = { tab = it })
                MainTab.MEMORY -> MemoryScreen(app)
                MainTab.RULES -> RulesScreen(app)
                else -> SettingsScreen(app, resumeTick, actions)
            }
        }
    }
}
