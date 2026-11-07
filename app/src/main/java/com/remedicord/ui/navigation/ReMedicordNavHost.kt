package com.remedicord.ui.navigation

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.remedicord.ui.history.HistoryScreen
import com.remedicord.ui.lock.LockScreen
import com.remedicord.ui.lock.LockViewModel
import com.remedicord.ui.lock.rememberLocked
import com.remedicord.ui.medications.DetailScreen
import com.remedicord.ui.medications.EditorScreen
import com.remedicord.ui.medications.MedicationsScreen
import com.remedicord.ui.settings.SettingsScreen
import com.remedicord.ui.today.TodayScreen
import kotlinx.serialization.Serializable

// Type-safe routes (PLAN.md §8).
@Serializable object TodayRoute
@Serializable object MedicationsRoute
@Serializable object HistoryRoute
@Serializable object SettingsRoute
@Serializable data class EditorRoute(val id: Long = 0)
@Serializable data class DetailRoute(val id: Long)

private data class Tab(val label: String, val icon: ImageVector, val route: Any)

private val tabs = listOf(
    Tab("Today", Icons.Filled.Home, TodayRoute),
    Tab("Medicines", Icons.Filled.List, MedicationsRoute),
    Tab("History", Icons.Filled.DateRange, HistoryRoute),
    Tab("Settings", Icons.Filled.Settings, SettingsRoute),
)

/** Root: PIN gate → main app. All text input runs in keyboard incognito mode. */
@Composable
fun ReMedicordNavHost() {
    IncognitoKeyboard {
        val lockVm: LockViewModel = hiltViewModel()
        if (rememberLocked(lockVm)) LockScreen(lockVm) else MainApp()
    }
}

@Composable
private fun MainApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = tabs.any { t -> dest?.hasRoute(t.route::class) == true }
    Scaffold(bottomBar = {
        if (showBar) NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = dest?.hasRoute(t.route::class) == true,
                    onClick = {
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(t.label) },
                )
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            NavHost(nav, startDestination = TodayRoute) {
                composable<TodayRoute> { TodayScreen(hiltViewModel(), onOpenMedication = { nav.navigate(DetailRoute(it)) }) }
                composable<MedicationsRoute> {
                    MedicationsScreen(hiltViewModel(), onAdd = { nav.navigate(EditorRoute()) }, onOpen = { nav.navigate(DetailRoute(it)) })
                }
                composable<HistoryRoute> { HistoryScreen(hiltViewModel()) }
                composable<SettingsRoute> { SettingsScreen(hiltViewModel()) }
                composable<EditorRoute> { EditorScreen(hiltViewModel(), onDone = { nav.popBackStack() }) }
                composable<DetailRoute> { DetailScreen(hiltViewModel(), onBack = { nav.popBackStack() }, onEdit = { nav.navigate(EditorRoute(it)) }) }
            }
        }
    }
}

/**
 * PLAN.md §12.1: every text field asks the keyboard not to learn what is typed
 * (IME_FLAG_NO_PERSONALIZED_LEARNING), so medicine names don't end up in keyboard suggestions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IncognitoKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            val incognito = PlatformTextInputMethodRequest { attrs ->
                request.createInputConnection(attrs).also {
                    attrs.imeOptions = attrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            }
            next.startInputMethod(incognito)
        },
        content = content,
    )
}
