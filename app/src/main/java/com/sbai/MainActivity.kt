package com.sbai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sbai.ui.dns.DnsScreen
import com.sbai.ui.home.HomeScreen
import com.sbai.ui.monitor.MonitorScreen
import com.sbai.ui.routes.RouteRulesScreen
import com.sbai.ui.settings.SettingsScreen
import com.sbai.ui.theme.SbAiTheme
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "首页", Icons.Filled.Home)
    data object Routes : Screen("routes", "路由", Icons.Filled.SwapHoriz)
    data object Dns : Screen("dns", "DNS", Icons.Filled.Dns)
    data object Monitor : Screen("monitor", "监控", Icons.Filled.MonitorHeart)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SbAiTheme {
                MainScaffold()
            }
        }
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val items = listOf(Screen.Home, Screen.Routes, Screen.Dns, Screen.Monitor, Screen.Settings)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Kototoro 风格：悬浮玻璃底栏（内容 backdrop blur）
    val hazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .haze(hazeState),
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Routes.route) { RouteRulesScreen() }
            composable(Screen.Dns.route) { DnsScreen() }
            composable(Screen.Monitor.route) { MonitorScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }

        GlassBottomBar(
            hazeState = hazeState,
            items = items,
            currentRoute = currentRoute,
            onSelect = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        )
    }
}

/** Kototoro 风格悬浮玻璃底栏：胶囊形、backdrop 模糊、细描边、无 indicator 背景 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun GlassBottomBar(
    hazeState: HazeState,
    items: List<Screen>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .hazeChild(hazeState) {
                blurRadius = 24.dp
                backgroundColor = colors.surfaceContainer.copy(alpha = 0.72f)
                tints = listOf(HazeTint(colors.surfaceContainer.copy(alpha = 0.30f)))
            }
            .border(
                width = 1.dp,
                color = colors.outlineVariant.copy(alpha = 0.30f),
                shape = CircleShape,
            ),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        ) {
            items.forEach { screen ->
                val selected = currentRoute == screen.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(screen.route) },
                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                    label = { Text(screen.title, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.onSurface,
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
