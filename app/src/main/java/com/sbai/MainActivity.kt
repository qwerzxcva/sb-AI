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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.sbai.data.RuleStore
import com.sbai.ui.components.BottomBarController
import com.sbai.ui.components.SbGlassBottomBar
import com.sbai.ui.components.SbNavItem
import com.sbai.ui.components.VpnToggleButton
import com.sbai.ui.dns.DnsScreen
import com.sbai.ui.home.HomeScreen
import com.sbai.ui.monitor.MonitorScreen
import com.sbai.ui.routes.RouteRulesScreen
import com.sbai.ui.settings.SettingsScreen
import com.sbai.ui.theme.SbAiTheme

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
            val store = remember { RuleStore.get(this) }
            // 关键：这里绝不能全量订阅 AppState。MainActivity 是最顶层，
            // 全量订阅会导致任何节点/设置的任何字段变化都重组整个 MainScaffold
            // （含 NavHost 与全部 5 个页面）——这是全局卡顿的最大放大器。
            // 只用主题需要的两个字段，distinctUntilChanged 去重。
            val themeMode by remember(store) {
                store.state.map { it.settings.themeMode }.distinctUntilChanged()
            }.collectAsState(initial = remember(store) { store.state.value.settings.themeMode })
            val dynamicColor by remember(store) {
                store.state.map { it.settings.dynamicColor }.distinctUntilChanged()
            }.collectAsState(initial = remember(store) { store.state.value.settings.dynamicColor })
            SbAiTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
            ) {
                MainScaffold()
            }
        }
    }
}

@Composable
private fun MainScaffold() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    val commandConnected by com.sbai.service.SbCommandClient.connectedToService.collectAsState()

    // 连接是应用级资源，不应绑在 HomeScreen 的进入/退出生命周期上。
    // 进入首页时只需显示已有 StateFlow，避免每次返回首页重复 setup + 重试连接。
    LaunchedEffect(commandConnected) {
        if (commandConnected) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.sbai.service.LibboxRuntime.setup(context.applicationContext) }
            com.sbai.service.SbCommandClient.connectWithRetry(attempts = 3, delayMs = 500L)
        }
        // 服务可能在页面加载后才完成启动；重试仍在 IO，且随 MainScaffold 生命周期取消。
        repeat(3) {
            if (com.sbai.service.SbCommandClient.connectedToService.value) return@LaunchedEffect
            kotlinx.coroutines.delay(10_000L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.sbai.service.SbCommandClient.connectWithRetry(attempts = 2, delayMs = 500L)
            }
        }
    }
    val items = listOf(Screen.Home, Screen.Routes, Screen.Dns, Screen.Monitor, Screen.Settings)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 磨砂开关拆成两层，避免为恢复质感重新引入首页卡顿：
    //  - barGlass：底栏自身低半径采样，成本低、提供实时透光磨砂质感。
    //  - pageGlass：把整个 NavHost 页面捕获为纹理，成本高；首页长列表下会明显放大开销，
    //    因此保持关闭。底栏磨砂仍可用，因为底栏采样不需要全页纹理。
    val barGlass = true
    val pageGlass = false
    val pageBackdrop = rememberLayerBackdrop()

    // 下滑隐藏 / 上滑显示底栏；BottomBarController 为跨组件真源，
    // 编辑器关闭时显式恢复（修复「二级页上滑隐藏后返回列表唤不出底栏」bug）
    val barVisible by BottomBarController.visible.collectAsState()
    val barOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (barVisible) 0.dp else 120.dp,
        animationSpec = androidx.compose.animation.core.tween(250),
        label = "bottomBarSlide",
    )
    val nestedScrollConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y < -1f) BottomBarController.hide()
                else if (available.y > 1f) BottomBarController.show()
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .then(if (pageGlass) Modifier.layerBackdrop(pageBackdrop) else Modifier),
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Routes.route) { RouteRulesScreen() }
            composable(Screen.Dns.route) { DnsScreen() }
            composable(Screen.Monitor.route) { MonitorScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }

        // 按可用宽度而非设备型号适配；VPN 保持独立，底栏与按钮共用高度。
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .offset(y = barOffset),
        ) {
            val compact = maxWidth < 420.dp
            val controlHeight = if (compact) 56.dp else 80.dp
            val sidePadding = if (compact) 8.dp else 20.dp
            val gap = if (compact) 8.dp else 12.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SbGlassBottomBar(
                    pageBackdrop = pageBackdrop,
                    items = items.map { SbNavItem(it.route, it.title, it.icon) },
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    barHeight = controlHeight,
                    useGlassBackdrop = barGlass,
                )
                Spacer(modifier = Modifier.size(gap))
                // 保留原权限请求、连接状态与启停行为；窄屏仍有 56dp 独立触摸区。
                VpnToggleButton(size = controlHeight)
            }
        }
    }
}

