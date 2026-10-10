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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
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
import com.sbai.ui.settings.SettingsAppearanceScreen
import com.sbai.ui.settings.SettingsAboutScreen
import com.sbai.ui.settings.SettingsAppProxyScreen
import com.sbai.ui.settings.SettingsBackupScreen
import com.sbai.ui.settings.SettingsKernelScreen
import com.sbai.ui.settings.SettingsLoadBalanceScreen
import com.sbai.ui.settings.SettingsScreen
import com.sbai.ui.settings.SettingsSubscriptionScreen
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

    // 连接是应用级资源，不应绑在 HomeScreen 的进入/退出生命周期上。
    // 旧版按 commandConnected 为 key：首次连接失败（服务未启动/ native 未就绪）后
    // key 恒为 false，本作用域内不会再触发 → :core 稍后启动成功时 UI 永远连不上，
    // 表现就是「启动后无任何反应」。现改为常驻轮询：断线期间每 15s 重试一次，
    // 服务（手动启动/自启/重启恢复）任何时刻起来都能被 UI 接管。
    LaunchedEffect(Unit) {
        while (true) {
            if (!com.sbai.service.SbCommandClient.connectedToService.value) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // 主进程跨进程连 :core 内核：设置 remoteContext 后 connectNow 会读 :core 写下的
                    // port+secret 文件，用 newRemoteCommandClient 连上去。:core 刚起来写 config
                    // 后，本 15s 轮询下一次重试就能连上并拿到真实 groups/status/连接。
                    com.sbai.service.SbCommandClient.configureRemote(context.applicationContext)
                    runCatching { com.sbai.service.LibboxRuntime.setup(context.applicationContext) }
                        .onFailure {
                            android.util.Log.e("SbAI_Scaffold", "libbox native setup failed; command channel unavailable", it)
                        }
                    com.sbai.service.SbCommandClient.connectWithRetry(attempts = 3, delayMs = 500L)
                }
            }
            kotlinx.coroutines.delay(15_000L)
        }
    }
    val items = listOf(Screen.Home, Screen.Routes, Screen.Monitor, Screen.Settings)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 实时磨砂玻璃底栏：使用 RenderEffect 硬件加速模糊
    // 下滑隐藏 / 上滑显示底栏；BottomBarController 为跨组件真源，
    // 编辑器关闭时显式恢复（修复「二级页上滑隐藏后返回列表唤不出底栏」bug）
    val barVisible by BottomBarController.visible.collectAsState()
    val barOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (barVisible) 0.dp else 120.dp,
        animationSpec = androidx.compose.animation.core.tween(250),
        label = "bottomBarSlide",
    )
    // 累计同方向滚动量，超过阈值才切换底栏显隐：逐帧 delta 直接触发 hide/show
    // 会让 250ms 位移动画在滚动抖动时反复启停（底栏每帧重组），是上下滑卡顿主因。
    val nestedScrollConnection = remember {
        var accumulated = 0f
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                val y = available.y
                // 方向反转时重置累计量
                if ((y > 0 && accumulated < 0) || (y < 0 && accumulated > 0)) accumulated = 0f
                accumulated += y
                when {
                    accumulated < -24f -> { BottomBarController.hide(); accumulated = 0f }
                    accumulated > 24f -> { BottomBarController.show(); accumulated = 0f }
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // 四主页面横向滑动：main 目的地内嵌 HorizontalPager（首页/路由/监控/设置），
    // 设置二级页（settings_*）仍走 NavHost 独立目的地。底栏选中与 Pager 页双向同步。
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { items.size })
    val scope = rememberCoroutineScope()
    // Pager 当前页 → currentRoute（供底栏高亮）；仅在 main 目的地时有效。
    val mainCurrentRoute = if (currentRoute == "main" || currentRoute == null) {
        items.getOrNull(pagerState.currentPage)?.route
    } else currentRoute

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 根容器背景：设置页等用 containerColor=Transparent 的 Scaffold 透到这里。
            // 之前无背景 → 透出窗口底色（enableEdgeToEdge 下常为黑），是「设置页黑」的根因。
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection),
    ) {
        NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("main") {
                HorizontalPager(
                    state = pagerState,
                    // 只保留当前页，避免首页节点列表和路由规则同时常驻导致点多了卡。
                    beyondViewportPageCount = 0,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (items[page].route) {
                        Screen.Home.route -> HomeScreen()
                        Screen.Routes.route -> RouteRulesScreen()
                        Screen.Monitor.route -> MonitorScreen()
                        Screen.Settings.route -> SettingsScreen(navController = navController)
                    }
                }
            }
            // 二级设置子页面
            composable("settings_kernel") { SettingsKernelScreen(navController) }
            composable("settings_loadbalance") { SettingsLoadBalanceScreen(navController) }
            composable("settings_app_proxy") { SettingsAppProxyScreen(navController) }
            composable("settings_subscription") { SettingsSubscriptionScreen(navController) }
            composable("settings_appearance") { SettingsAppearanceScreen(navController) }
            composable("settings_backup") { SettingsBackupScreen(navController) }
            composable("settings_about") { SettingsAboutScreen(navController) }
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
            // 与底栏同排平齐；宽屏也保持紧凑，56dp 点击区域不随屏宽放大。
            val controlHeight = 56.dp
            val sidePadding = if (compact) 8.dp else 20.dp
            val gap = if (compact) 8.dp else 12.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SbGlassBottomBar(
                    items = items.map { SbNavItem(it.route, it.title, it.icon) },
                    currentRoute = mainCurrentRoute,
                    onSelect = { route ->
                        val page = items.indexOfFirst { it.route == route }
                        if (page >= 0) {
                            // 在二级页时先回 main，再滚动 Pager 到目标页
                            if (currentRoute != "main" && currentRoute != null) {
                                navController.popBackStack("main", inclusive = false)
                            }
                            scope.launch { pagerState.animateScrollToPage(page) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    barHeight = controlHeight,
                    useGlassBackdrop = true,
                )
                Spacer(modifier = Modifier.size(gap))
                // 保留原权限请求、连接状态与启停行为；窄屏仍有 56dp 独立触摸区。
                VpnToggleButton(size = controlHeight)
            }
        }
    }
}

