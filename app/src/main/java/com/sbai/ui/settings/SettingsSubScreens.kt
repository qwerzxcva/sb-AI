package com.sbai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.sbai.data.AppState
import com.sbai.data.ConfigProfile
import com.sbai.data.LogLevel
import com.sbai.data.OverridePriority
import com.sbai.data.PerAppProxyMode
import com.sbai.data.RuleStore
import com.sbai.data.ThemeMode
import com.sbai.service.BackupManager
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.ui.components.AppPickerDialog
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// 内核设置（二级页）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsKernelScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "内核设置") },
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
                SbGroup(title = "内核") {
                    item {
                        SbSwitchItem(
                            title = "IPv6 路由",
                            subtitle = "TUN 同时承载 IPv6 流量",
                            icon = Icons.Filled.NetworkCheck,
                            checked = settings.ipv6Route,
                        ) { store.updateSettings(settings.copy(ipv6Route = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "自动检测网卡",
                            subtitle = "auto_detect_interface",
                            icon = Icons.Filled.Route,
                            checked = settings.autoDetectInterface,
                        ) { store.updateSettings(settings.copy(autoDetectInterface = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "严格路由",
                            subtitle = "strict_route，防止流量泄漏",
                            icon = Icons.Filled.Security,
                            checked = settings.strictRoute,
                        ) { store.updateSettings(settings.copy(strictRoute = it)) }
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("MTU", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = settings.mtu.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { n -> store.updateSettings(settings.copy(mtu = n)) } },
                                label = { Text("MTU（默认 1500）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("TUN 网络栈", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            val stacks = listOf(
                                Triple("system", "system", "系统协议栈"),
                                Triple("gvisor", "gVisor", "用户态协议栈，兼容性最好"),
                                Triple("mixed", "mixed", "系统 TCP + gVisor UDP"),
                            )
                            stacks.forEach { (stack, title, subtitle) ->
                                com.sbai.ui.components.SbChoiceCard(
                                    title = title,
                                    subtitle = subtitle,
                                    selected = settings.tunStack == stack,
                                    onClick = { store.updateSettings(settings.copy(tunStack = stack)) },
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("TUN 地址段", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = settings.tunAddress,
                                onValueChange = { store.updateSettings(settings.copy(tunAddress = it.trim())) },
                                label = { Text("IPv4 段（如 172.18.0.1/30）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = settings.tunAddress6,
                                onValueChange = { store.updateSettings(settings.copy(tunAddress6 = it.trim())) },
                                label = { Text("IPv6 段（如 fdfe:dcba:9876::1/126）") },
                                singleLine = true,
                                enabled = settings.ipv6Route,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                SbGroup(title = "DPI 硬化（TLS 分片）") {
                    item {
                        SbSwitchItem(
                            title = "TLS 记录分片",
                            subtitle = "把握手拆成多个 TLS record（推荐先试这个）",
                            icon = Icons.Filled.Security,
                            checked = settings.tlsRecordFragment,
                        ) { store.updateSettings(settings.copy(tlsRecordFragment = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "TLS 分片",
                            subtitle = "把 ClientHello 拆成小 TCP 段，绕过 DPI",
                            icon = Icons.Filled.ContentCut,
                            checked = settings.tlsFragment,
                        ) { store.updateSettings(settings.copy(tlsFragment = it)) }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OutlinedTextField(
                                value = settings.tlsFragmentFallbackDelay,
                                onValueChange = { store.updateSettings(settings.copy(tlsFragmentFallbackDelay = it.trim())) },
                                label = { Text("回退延迟（如 500ms）") },
                                singleLine = true,
                                enabled = settings.tlsFragment,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                SbGroup(title = "日志") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("日志级别", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LogLevel.entries.forEach { level ->
                                    FilterChip(
                                        selected = settings.logLevel == level,
                                        onClick = { store.updateSettings(settings.copy(logLevel = level)) },
                                        label = { Text(level.wireName) },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分应用代理（二级页）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppProxyScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })
    var showAppPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "分应用代理") },
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
                SbGroup(title = "分应用代理") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("模式", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                PerAppProxyMode.entries.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = settings.perAppProxy.mode == mode,
                                        onClick = {
                                            store.updateSettings(
                                                settings.copy(perAppProxy = settings.perAppProxy.copy(mode = mode)),
                                            )
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = PerAppProxyMode.entries.size),
                                    ) {
                                        Text(
                                            when (mode) {
                                                PerAppProxyMode.OFF -> "关闭"
                                                PerAppProxyMode.INCLUDE -> "白名单"
                                                PerAppProxyMode.EXCLUDE -> "黑名单"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SbItem(
                            title = "应用名单（${settings.perAppProxy.packages.size}）",
                            subtitle = if (settings.perAppProxy.mode == PerAppProxyMode.OFF) "先选择模式"
                            else "选择走代理 / 绕过的应用",
                            icon = Icons.Filled.Apps,
                            onClick = { showAppPicker = true },
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "选择应用",
            selected = settings.perAppProxy.packages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave = { pkgs ->
                store.updateSettings(settings.copy(perAppProxy = settings.perAppProxy.copy(packages = pkgs.sorted())))
                showAppPicker = false
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 订阅身份（二级页）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubscriptionScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })
    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "订阅身份") },
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
                SbGroup(title = "订阅身份") {
                    item {
                        SbItem(
                            title = "全局 User-Agent",
                            subtitle = settings.subscriptionUserAgent.ifBlank { "默认 sb-AI/<版本>" },
                            icon = Icons.Filled.Badge,
                            onClick = {
                                editingText = Triple("全局 User-Agent", settings.subscriptionUserAgent) { v ->
                                    store.updateSettings(settings.copy(subscriptionUserAgent = v.trim()))
                                }
                            },
                        )
                    }
                    item {
                        SbSwitchItem(
                            title = "发送 HWID + 设备信息",
                            subtitle = "x-hwid / x-device-os / x-ver-os / x-device-model（Remnawave 设备限制面板用）",
                            icon = Icons.Filled.Fingerprint,
                            checked = settings.subscriptionSendHwid,
                        ) { enabled ->
                            val hwid = if (enabled && settings.subscriptionHwid.isBlank()) {
                                java.util.UUID.randomUUID().toString()
                            } else settings.subscriptionHwid
                            store.updateSettings(
                                settings.copy(subscriptionSendHwid = enabled, subscriptionHwid = hwid),
                            )
                        }
                    }
                    if (settings.subscriptionSendHwid) {
                        item {
                            SbItem(
                                title = "HWID（x-hwid）",
                                subtitle = settings.subscriptionHwid.ifBlank { "（开启时自动生成）" },
                                icon = Icons.Filled.VpnKey,
                                onClick = {
                                    editingText = Triple("HWID（留空则重新生成）", settings.subscriptionHwid) { v ->
                                        val newHwid = v.trim().ifBlank { java.util.UUID.randomUUID().toString() }
                                        store.updateSettings(settings.copy(subscriptionHwid = newHwid))
                                    }
                                },
                            )
                        }
                        item {
                            SbItem(
                                title = "设备型号（x-device-model）",
                                subtitle = settings.subscriptionDeviceModel.ifBlank { android.os.Build.MODEL },
                                icon = Icons.Filled.PhoneAndroid,
                                onClick = {
                                    editingText = Triple("x-device-model", settings.subscriptionDeviceModel) { v ->
                                        store.updateSettings(settings.copy(subscriptionDeviceModel = v.trim()))
                                    }
                                },
                            )
                        }
                        item {
                            SbItem(
                                title = "系统版本（x-ver-os）",
                                subtitle = settings.subscriptionVerOs.ifBlank { android.os.Build.VERSION.RELEASE },
                                icon = Icons.Filled.Badge,
                                onClick = {
                                    editingText = Triple("x-ver-os", settings.subscriptionVerOs) { v ->
                                        store.updateSettings(settings.copy(subscriptionVerOs = v.trim()))
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }

    editingText?.let { (t, v, onDone) ->
        SettingsTextEditDialog(
            title = t,
            initial = v,
            onDismiss = { editingText = null },
            onDone = { onDone(it); editingText = null },
        )
    }
}

// ---------------------------------------------------------------------------
// 外观（二级页）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "外观") },
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
                SbGroup(title = "外观") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("主题", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                ThemeMode.entries.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = settings.themeMode == mode,
                                        onClick = { store.updateSettings(settings.copy(themeMode = mode)) },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = ThemeMode.entries.size),
                                    ) { Text(mode.displayName) }
                                }
                            }
                        }
                    }
                    item {
                        SbSwitchItem(
                            title = "Material You 动态取色",
                            subtitle = "跟随系统壁纸配色（Android 12+）",
                            icon = Icons.Filled.Palette,
                            checked = settings.dynamicColor,
                        ) { store.updateSettings(settings.copy(dynamicColor = it)) }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 备份与恢复（二级页）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })
    val profiles by remember(store) {
        store.state.map { it.profiles }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.profiles })
    val activeProfileId by remember(store) {
        store.state.map { it.activeProfileId }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.activeProfileId })
    val scope = rememberCoroutineScope()

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var profileNameInput by remember { mutableStateOf("") }
    var editingProfile by remember { mutableStateOf<ConfigProfile?>(null) }
    var pendingImport by remember { mutableStateOf<AppState?>(null) }
    var showCustomConfigEditor by remember { mutableStateOf(false) }
    var showOverridePreview by remember { mutableStateOf(false) }

    val backupJson = remember {
        Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    }
    val snapshotJson = remember { Json { encodeDefaults = true; ignoreUnknownKeys = true } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val snapshot = store.state.value
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    backupJson.encodeToString(AppState.serializer(), snapshot).toByteArray(Charsets.UTF_8)
                }
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法打开输出文件")
                    output.use { it.write(bytes) }
                }
                backupMessage = "已导出配置"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                backupMessage = "导出失败: ${error.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("空文件")
                    input.use {
                        java.io.ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_BACKUP_BYTES) error("备份文件超过 8MB，已中止")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                }
                pendingImport = withContext(Dispatchers.Default) {
                    backupJson.decodeFromString(AppState.serializer(), bytes.toString(Charsets.UTF_8))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                backupMessage = "导入失败: ${error.message}"
            }
        }
    }

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "备份与恢复") },
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
                SbGroup(title = "备份与恢复") {
                    item {
                        SbItem(
                            title = "导出配置",
                            subtitle = "全部规则 / DNS / 节点 / 设置 → JSON 文件",
                            icon = Icons.Filled.Save,
                            onClick = { exportLauncher.launch("sb-ai-backup.json") },
                        )
                    }
                    item {
                        SbItem(
                            title = "导入配置",
                            subtitle = "从 JSON 文件恢复（可选合并或覆盖）",
                            icon = Icons.Filled.Restore,
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        )
                    }
                    backupMessage?.let { msg ->
                        item { SbItem(title = "结果", subtitle = msg) }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                val settingsCopy = settings
                SbGroup(title = "配置覆盖") {
                    item {
                        SbSwitchItem(
                            title = "启用配置覆盖",
                            subtitle = "导入完整 sing-box JSON，与 UI 生成的配置合并",
                            icon = Icons.Filled.Code,
                            checked = settingsCopy.configOverride.enabled,
                        ) { enabled ->
                            store.updateSettings(
                                settingsCopy.copy(
                                    configOverride = settingsCopy.configOverride.copy(
                                        enabled = enabled,
                                        json = if (enabled && settingsCopy.configOverride.json.isBlank()) {
                                            OVERRIDE_SAMPLE
                                        } else {
                                            settingsCopy.configOverride.json
                                        },
                                    ),
                                ),
                            )
                        }
                    }
                    if (settings.configOverride.enabled) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("优先级", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(4.dp))
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    OverridePriority.entries.forEachIndexed { i, p ->
                                        SegmentedButton(
                                            selected = settings.configOverride.priority == p,
                                            onClick = {
                                                store.updateSettings(
                                                    settings.copy(
                                                        configOverride = settings.configOverride.copy(priority = p),
                                                    ),
                                                )
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index = i, count = OverridePriority.entries.size),
                                        ) { Text(p.displayName) }
                                    }
                                }
                                Text(
                                    if (settings.configOverride.priority == OverridePriority.UI_HIGHEST) {
                                        "UI 层最高：导入的 JSON 只能补充 UI 没有生成的字段和没有添加的数组项"
                                    } else {
                                        "导入 JSON 最高：覆盖 UI 生成的同名字段（按 tag/name 合并数组）"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            SbItem(
                                title = "编辑导入的 JSON",
                                subtitle = if (settings.configOverride.json.isBlank()) "未填写"
                                else "已填写（${settings.configOverride.json.length} 字符）",
                                icon = Icons.Filled.EditNote,
                                onClick = { showCustomConfigEditor = true },
                            )
                        }
                        item {
                            SbItem(
                                title = "查看最终合并结果",
                                subtitle = "预览 UI 配置 + 导入 JSON 合并后的 sing-box 配置",
                                icon = Icons.Filled.Preview,
                                onClick = { showOverridePreview = true },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                SbGroup(title = "配置快照（${profiles.size}）") {
                    item {
                        SbItem(
                            title = "保存当前配置为快照",
                            subtitle = if (activeProfileId.isEmpty()) "当前未处于快照模式"
                            else "当前在快照: ${profiles.find { it.id == activeProfileId }?.name}",
                            icon = Icons.Filled.Save,
                            onClick = {
                                editingProfile = null
                                profileNameInput = ""
                                showProfileDialog = true
                            },
                        )
                    }
                    if (profiles.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(horizontal = 8.dp)) {
                                profiles.forEach { profile ->
                                    val isActive = activeProfileId == profile.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .background(
                                                if (isActive) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceContainer,
                                                RoundedCornerShape(16.dp),
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                profile.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                                    .format(java.util.Date(profile.createdAt)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            FilledTonalButton(
                                                onClick = {
                                                    editingProfile = profile
                                                    profileNameInput = profile.name
                                                    showProfileDialog = true
                                                },
                                                contentPadding = PaddingValues(8.dp),
                                            ) { Text("重命名", style = MaterialTheme.typography.labelSmall) }
                                            if (!isActive) {
                                                OutlinedButton(
                                                    onClick = {
                                                        val selected = store.state.value.profiles.find { it.id == profile.id }
                                                        if (selected != null) scope.launch {
                                                            try {
                                                                val restored = withContext(Dispatchers.Default) {
                                                                    snapshotJson.decodeFromString(AppState.serializer(), selected.snapshot)
                                                                }
                                                                store.update { current ->
                                                                    BackupManager.restoreProfile(current, restored, selected.id)
                                                                }
                                                            } catch (cancelled: CancellationException) {
                                                                throw cancelled
                                                            } catch (error: Exception) {
                                                                backupMessage = "加载快照失败: ${error.message}"
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(8.dp),
                                                ) { Text("加载", style = MaterialTheme.typography.labelSmall) }
                                            }
                                            OutlinedButton(
                                                onClick = { store.deleteProfile(profile.id) },
                                                contentPadding = PaddingValues(8.dp),
                                            ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }

    if (showProfileDialog) {
        var name by remember { mutableStateOf(profileNameInput) }
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text(if (editingProfile != null) "重命名快照" else "保存快照") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("快照名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.trim().isEmpty()) return@TextButton
                    if (editingProfile != null) {
                        store.update { s ->
                            s.copy(profiles = s.profiles.map { p -> if (p.id == editingProfile!!.id) p.copy(name = name.trim()) else p })
                        }
                    } else {
                        val snapshot = store.state.value
                        val profileName = name.trim()
                        scope.launch {
                            try {
                                val profile = withContext(Dispatchers.Default) {
                                    ConfigProfile(
                                        name = profileName,
                                        snapshot = snapshotJson.encodeToString(AppState.serializer(), snapshot),
                                    )
                                }
                                store.update { current ->
                                    current.copy(profiles = current.profiles + profile, activeProfileId = profile.id)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                backupMessage = "保存快照失败: ${error.message}"
                            }
                        }
                    }
                    showProfileDialog = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showProfileDialog = false }) { Text("取消") } },
        )
    }

    if (pendingImport != null) {
        val imported = pendingImport!!
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入配置") },
            text = {
                Column {
                    Text("选择导入方式：")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 合并：把文件中的规则/节点/订阅追加到现有配置（不删除现有项，同名 id 保留现有）\n" +
                            "• 覆盖：用文件内容整体替换当前全部配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.replaceAll(imported)
                    backupMessage = "已覆盖导入配置"
                    pendingImport = null
                }) { Text("覆盖导入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val initialSnapshot = store.state.value
                    pendingImport = null
                    scope.launch {
                        try {
                            var snapshot = initialSnapshot
                            while (true) {
                                val merged = withContext(Dispatchers.Default) {
                                    BackupManager.merge(snapshot, imported)
                                }
                                var applied = false
                                store.update { current ->
                                    if (current === snapshot) {
                                        applied = true
                                        merged
                                    } else current
                                }
                                if (applied) break
                                snapshot = store.state.value
                            }
                            backupMessage = "已合并导入配置"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            backupMessage = "合并导入失败: ${error.message}"
                        }
                    }
                }) { Text("合并导入") }
            },
        )
    }
    if (showCustomConfigEditor) {
        CustomConfigEditorDialog(
            initial = settings.configOverride.json.ifBlank { OVERRIDE_SAMPLE },
            onDismiss = { showCustomConfigEditor = false },
            onSave = { text ->
                store.update { current ->
                    current.copy(settings = current.settings.copy(
                        configOverride = current.settings.configOverride.copy(json = text),
                    ))
                }
                showCustomConfigEditor = false
            },
        )
    }

    if (showOverridePreview) {
        var merged by remember { mutableStateOf("正在生成…") }
        LaunchedEffect(store) {
            val snapshot = store.state.value
            merged = try {
                withContext(Dispatchers.Default) { SingBoxConfigGenerator.generate(snapshot) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "合并失败: ${error.message}"
            }
        }
        OverridePreviewDialog(merged, onDismiss = { showOverridePreview = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "关于") },
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
                SbGroup(title = "关于") {
                    item {
                        SbItem(
                            title = "sb-AI",
                            subtitle = "基于 sing-box 内核，含 AWG2/XHTTP/balancer 扩展\nUI 风格参考 Kototoro；功能参考多个开源项目\n不集成 Root / Magisk 功能",
                            icon = Icons.Filled.Info,
                        )
                    }
                    item {
                        SbItem(
                            title = "开源许可",
                            subtitle = "GPL-3.0（与上游保持一致）",
                            icon = Icons.Filled.Article,
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 共享对话框
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsTextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

private const val OVERRIDE_SAMPLE = """{
  "log": { "level": "info" },
  "ntp": { "enabled": true, "server": "ntp.aliyun.com" },
  "experimental": {
    "clash_api": { "external_controller": "127.0.0.1:9090" }
  },
  "outbounds": [
    { "type": "direct", "tag": "warp-direct" }
  ]
}"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomConfigEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 sing-box 配置") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp),
                    minLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall,
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val submitted = text
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.Default) { Json.parseToJsonElement(submitted) }
                        onSave(submitted)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = "JSON 无效: ${failure.message}"
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "校验中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverridePreviewDialog(
    merged: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("合并后的 sing-box 配置") },
        text = {
            OutlinedTextField(
                value = merged, onValueChange = {}, readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text("关闭") } },
    )
}
