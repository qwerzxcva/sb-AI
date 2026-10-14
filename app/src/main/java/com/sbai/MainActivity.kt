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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sbai.data.RuleStore
import com.sbai.ui.components.BottomBarController
import com.sbai.ui.components.SbGlassBottomBar
import com.sbai.ui.components.SbNavItem
import com.sbai.ui.components.VpnToggleButton
import com.sbai.ui.home.HomeScreen
import com.sbai.ui.monitor.MonitorScreen
import com.sbai.ui.routes.RouteRulesScreen
import com.sbai.ui.settings.SettingsScreen
import com.sbai.ui.theme.SbAiTheme
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "首页", Icons.Filled.Home)
    data object Routes : Screen("routes", "路由", Icons.Filled.SwapHoriz)
    data object Monitor : Screen("monitor", "监控", Icons.Filled.MonitorHeart)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val store = remember { RuleStore.get(this) }
            val state by store.state.collectAsState()
            SbAiTheme(
                themeMode = state.settings.themeMode,
                dynamicColor = state.settings.dynamicColor,
            ) {
                MainScaffold()
            }
        }
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    // 设置页跳转到路由页时指定初始 Tab：0=路由规则，1=DNS
    var routesInitialTab by remember { mutableIntStateOf(0) }
    // 由设置页触发：跳转到路由页后自动打开「规则集与资源」对话框
    var showRouteRuleSetManager by remember { mutableStateOf(false) }
    // 首页跳转到设置页时要求落地的分类 key（null = 设置索引页）
    var settingsInitialPage by remember { mutableStateOf<String?>(null) }
    val items = listOf(Screen.Home, Screen.Routes, Screen.Monitor, Screen.Settings)
    // navItems 提为 remember：否则每次重组都新建 list + SbNavItem 实例，
    // SbGlassBottomBar 的参数永远不相等，底栏会跟着 VpnToggleButton 的
    // 1 秒状态轮询一起重组。
    val navItems = remember(items) { items.map { SbNavItem(it.route, it.title, it.icon) } }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

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
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(onOpenSettingsLoadBalance = {
                    settingsInitialPage = "proxy"
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(Screen.Routes.route) {
                RouteRulesScreen(
                    initialTab = routesInitialTab,
                    openRuleSetManager = showRouteRuleSetManager,
                )
            }
            composable(Screen.Monitor.route) { MonitorScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    // 首页「更多负载均衡参数」要求直达：跳到设置时落到「代理出口」分类
                    initialPage = settingsInitialPage,
                    onOpenDnsAdvanced = {
                        routesInitialTab = 1
                        navController.navigate(Screen.Routes.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenRouteResources = {
                        showRouteRuleSetManager = true
                        navController.navigate(Screen.Routes.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }

        // FlClash 风格：底栏胶囊 + 右侧独立圆形 VPN 开关按钮
        // 下滑隐藏时整行不参与绘制（barVisible 是离散状态，不用浮点中间值比较）。
        if (barVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp)
                    // 下滑隐藏：整体下移出屏幕
                    .offset(y = barOffset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SbGlassBottomBar(
                    items = navItems,
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        // 离开设置页时清掉直达分类，下次进入回到索引页
                        if (route != Screen.Settings.route) settingsInitialPage = null
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.size(12.dp))
                // 与 NavigationBar 等高（80dp），上下平齐；圆形按钮直径 = 底栏高度
                VpnToggleButton(size = 80.dp)
            }
        }
    }
}

