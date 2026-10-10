package com.sbai.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.sbai.data.RuleStore
import com.sbai.ui.components.BottomBarController
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 设置目录页（一级页面）：
 * 每个大分类一行卡片 → 点击进入二级子页面（settings_* 路由）。
 * 分类：内核 / 负载均衡 / 分应用代理 / 订阅身份 / 外观 / 备份与恢复 / 关于。
 * 高频开关（开机自启）保留在一级页，免去跳转。
 * 资源管理已并入「路由 / DNS」页第 3 页，设置页不再重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current

    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })
    val loadBalance by remember(store) {
        store.state.map { it.loadBalance }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.loadBalance })
    // 一级页对话框：配置预览 / 负载均衡模式（从二级页上移，用户要求放一级页）
    var showConfigPreview by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = tokens.screenHorizontalPadding),
        ) {
            item { Spacer(Modifier.height(16.dp)) }
            item {
                SbGroup(title = "设置") {
                    item {
                        SbItem(
                            title = "内核设置",
                            subtitle = "TUN / MTU / 网络栈 / IPv6 / 严格路由 / DPI 硬化",
                            icon = Icons.Filled.Shield,
                            onClick = { navController.navigate("settings_kernel") },
                        )
                    }
                    item {
                        SbItem(
                            title = "负载均衡",
                            subtitle = "总开关 / 参与节点 / 高级参数",
                            icon = Icons.Filled.Balance,
                            onClick = { navController.navigate("settings_loadbalance") },
                        )
                    }
                    item {
                        SbItem(
                            title = "负载均衡模式",
                            subtitle = loadBalance.mode.displayName,
                            icon = Icons.Filled.Router,
                            onClick = { showModeDialog = true },
                        )
                    }
                    item {
                        SbItem(
                            title = "配置预览",
                            subtitle = "查看生成的 sing-box 配置",
                            icon = Icons.Filled.Code,
                            onClick = { showConfigPreview = true },
                        )
                    }
                    item {
                        SbItem(
                            title = "分应用代理",
                            subtitle = "白名单 / 黑名单及应用选择",
                            icon = Icons.Filled.Apps,
                            onClick = { navController.navigate("settings_app_proxy") },
                        )
                    }
                    item {
                        SbItem(
                            title = "订阅身份",
                            subtitle = "User-Agent / HWID / 设备信息",
                            icon = Icons.Filled.Security,
                            onClick = { navController.navigate("settings_subscription") },
                        )
                    }
                    item {
                        SbItem(
                            title = "外观",
                            subtitle = "主题模式 / 动态取色",
                            icon = Icons.Filled.Palette,
                            onClick = { navController.navigate("settings_appearance") },
                        )
                    }
                    item {
                        SbItem(
                            title = "备份与恢复",
                            subtitle = "导入 / 导出 / 配置快照",
                            icon = Icons.Filled.Save,
                            onClick = { navController.navigate("settings_backup") },
                        )
                    }
                    item {
                        SbItem(
                            title = "关于",
                            subtitle = "版本 / 许可 / 支持",
                            icon = Icons.Filled.Info,
                            onClick = { navController.navigate("settings_about") },
                        )
                    }
                }
            }
            item {
                SbGroup(title = "快速设置") {
                    item {
                        SbSwitchItem(
                            title = "开机自启",
                            subtitle = "需已授予 VPN 权限；启动 VPN 服务",
                            icon = Icons.Filled.PowerSettingsNew,
                            checked = settings.autoStartOnBoot,
                        ) { store.updateSettings(settings.copy(autoStartOnBoot = it)) }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // ---- 一级页对话框：负载均衡模式 ----
    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("负载均衡模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.sbai.data.LoadBalanceMode.entries.forEach { mode ->
                        Surface(
                            onClick = {
                                store.updateLoadBalance(loadBalance.copy(mode = mode))
                                showModeDialog = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (loadBalance.mode == mode) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                                Text(mode.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when (mode) {
                                        com.sbai.data.LoadBalanceMode.LATENCY -> "urltest：始终选延迟最低的节点"
                                        com.sbai.data.LoadBalanceMode.BALANCED -> "urltest+tolerance：在可接受延迟内分摊节点"
                                        com.sbai.data.LoadBalanceMode.MANUAL -> "selector：手动切换出口"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModeDialog = false }) { Text("关闭") } },
        )
    }

    // ---- 一级页对话框：配置预览 ----
    if (showConfigPreview) {
        var previewConfig by remember { mutableStateOf("正在生成配置…") }
        LaunchedEffect(Unit) {
            val snapshot = store.state.value
            previewConfig = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.sbai.service.SingBoxConfigGenerator.generate(snapshot)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "生成失败: ${e.message}"
            }
        }
        AlertDialog(
            onDismissRequest = { showConfigPreview = false },
            title = { Text("sing-box 配置预览") },
            text = {
                OutlinedTextField(
                    value = previewConfig, onValueChange = {}, readOnly = true,
                    modifier = Modifier.fillMaxWidth(), minLines = 12,
                )
            },
            confirmButton = { TextButton(onClick = { showConfigPreview = false }) { Text("关闭") } },
        )
    }
}

/**
 * 二级子页面通用顶栏（返回 + 标题）。子页面 onBack/返回键时恢复底栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubTopBar(
    navController: NavHostController,
    title: String,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = {
                BottomBarController.show()
                navController.navigateUp()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}
