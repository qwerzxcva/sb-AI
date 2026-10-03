package com.sbai.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sbai.data.AppState
import com.sbai.data.LogLevel
import com.sbai.data.OverridePriority
import com.sbai.data.PerAppProxyMode
import com.sbai.data.RuleStore
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.data.ThemeMode
import com.sbai.ui.components.AppPickerDialog
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val settings = state.settings
    val tokens = LocalSbStyleTokens.current

    var showAppPicker by remember { mutableStateOf(false) }
    var showCustomConfigEditor by remember { mutableStateOf(false) }
    var showOverridePreview by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }

    val backupJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(backupJson.encodeToString(AppState.serializer(), state).toByteArray())
            }
            backupMessage = "已导出配置"
        }.onFailure { backupMessage = "导出失败: ${it.message}" }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                // 上限 8MB，防止超大/畸形文件撑爆内存
                val buf = ByteArray(8192)
                val sb = StringBuilder()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BACKUP_BYTES) error("备份文件超过 8MB，已中止")
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
                sb.toString()
            } ?: error("空文件")
            val imported = backupJson.decodeFromString(AppState.serializer(), text)
            store.replaceAll(imported)
            backupMessage = "已导入配置"
        }.onFailure { backupMessage = "导入失败: ${it.message}" }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = tokens.screenHorizontalPadding),
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            // ---- 外观 ----
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
                SbSpacer()
            }

            // ---- 内核 ----
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
                            OutlinedTextField(
                                value = settings.mtu.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { n -> store.updateSettings(settings.copy(mtu = n)) } },
                                label = { Text("MTU") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // TUN 网络栈（system / gvisor / mixed）
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("TUN 网络栈", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf("system", "gvisor", "mixed").forEachIndexed { i, stack ->
                                    SegmentedButton(
                                        selected = settings.tunStack == stack,
                                        onClick = { store.updateSettings(settings.copy(tunStack = stack)) },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                                    ) { Text(stack) }
                                }
                            }
                        }
                    }
                    // TUN 地址段自定义（LxBox tun_address / tun_address6）
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
                SbSpacer()
            }

            // ---- 日志 ----
            item {
                SbGroup(title = "日志") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("日志级别", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            // 5 个级别用 segmented 按钮会挤爆（debug 文字被截断），改用 FlowRow chips
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
                SbSpacer()
            }

            // ---- 分应用代理 ----
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
                SbSpacer()
            }

            // ---- 配置覆盖 ----
            item {
                SbGroup(title = "配置覆盖") {
                    item {
                        SbSwitchItem(
                            title = "启用配置覆盖",
                            subtitle = "导入完整 sing-box JSON，与 UI 生成的配置合并",
                            icon = Icons.Filled.Code,
                            checked = settings.configOverride.enabled,
                        ) { enabled ->
                            store.updateSettings(
                                settings.copy(
                                    configOverride = settings.configOverride.copy(
                                        enabled = enabled,
                                        json = if (enabled && settings.configOverride.json.isBlank()) {
                                            OVERRIDE_SAMPLE
                                        } else {
                                            settings.configOverride.json
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
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = i, count = OverridePriority.entries.size,
                                            ),
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
                SbSpacer()
            }

            // ---- 订阅身份（LxBox SubscriptionIdentity 基准） ----
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
                            // 开启时若 hwid 为空则懒生成 UUIDv4
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
                SbSpacer()
            }

            // ---- 备份与恢复 ----
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
                            subtitle = "从 JSON 文件恢复（覆盖当前全部配置）",
                            icon = Icons.Filled.Restore,
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        )
                    }
                    backupMessage?.let { msg ->
                        item { SbItem(title = "结果", subtitle = msg) }
                    }
                }
                SbSpacer()
            }

            // ---- 启动 ----
            item {
                SbGroup(title = "启动") {
                    item {
                        SbSwitchItem(
                            title = "开机自启",
                            subtitle = "需已授予 VPN 权限；启动 VPN 服务",
                            icon = Icons.Filled.PowerSettingsNew,
                            checked = settings.autoStartOnBoot,
                        ) { store.updateSettings(settings.copy(autoStartOnBoot = it)) }
                    }
                }
                SbSpacer()
            }

            // ---- 关于 ----
            item {
                SbGroup(title = "关于") {
                    item {
                        SbItem(
                            title = "sb-AI",
                            subtitle = "基于 sing-box（LxBox 同款 sing-box-lx 内核，含 AWG2/XHTTP/balancer 扩展）\nUI 风格参考 Kototoro；功能参考 LxBox / AsteriskBOX / ThroneForAndroid\n不集成 Root / Magisk 功能",
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
                Spacer(Modifier.height(120.dp))
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

    if (showCustomConfigEditor) {
        CustomConfigEditorDialog(
            initial = settings.configOverride.json.ifBlank { OVERRIDE_SAMPLE },
            onDismiss = { showCustomConfigEditor = false },
            onSave = { text ->
                store.updateSettings(
                    settings.copy(configOverride = settings.configOverride.copy(json = text)),
                )
                showCustomConfigEditor = false
            },
        )
    }

    if (showOverridePreview) {
        val merged = runCatching { SingBoxConfigGenerator.generate(state) }
            .getOrElse { "合并失败: ${it.message}" }
        AlertDialog(
            onDismissRequest = { showOverridePreview = false },
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
            confirmButton = { TextButton(onClick = { showOverridePreview = false }) { Text("关闭") } },
        )
    }

    editingText?.let { (title, initialValue, onDone) ->
        SettingsTextEditDialog(
            title = title,
            initial = initialValue,
            onDismiss = { editingText = null },
            onDone = { onDone(it); editingText = null },
        )
    }
}

/** 覆盖示例：常用「UI 没有生成的补充项」写法 */
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

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

@Composable
private fun SettingsTextEditDialog(
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

// ---------------------------------------------------------------------------
// 分应用代理：应用选择器
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 自定义配置编辑器
// ---------------------------------------------------------------------------
// 自定义配置编辑器
// ---------------------------------------------------------------------------

@Composable
private fun CustomConfigEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 sing-box 配置") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
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
            TextButton(onClick = {
                runCatching { Json.parseToJsonElement(text) }
                    .onSuccess { onSave(text) }
                    .onFailure { error = "JSON 无效: ${it.message}" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
