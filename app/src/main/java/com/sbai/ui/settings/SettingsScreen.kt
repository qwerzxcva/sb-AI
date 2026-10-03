package com.sbai.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.sbai.data.PerAppProxyMode
import com.sbai.data.RuleStore
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val settings = state.settings
    val tokens = LocalSbStyleTokens.current

    var showAppPicker by remember { mutableStateOf(false) }
    var showCustomConfigEditor by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }

    val backupJson = Json { prettyPrint = true; encodeDefaults = true }

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
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
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
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                LogLevel.entries.take(5).forEachIndexed { i, level ->
                                    SegmentedButton(
                                        selected = settings.logLevel == level,
                                        onClick = { store.updateSettings(settings.copy(logLevel = level)) },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 5),
                                    ) { Text(level.wireName) }
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

            // ---- 自定义配置 ----
            item {
                SbGroup(title = "自定义配置") {
                    item {
                        SbSwitchItem(
                            title = "使用自定义配置",
                            subtitle = "启用后忽略生成的配置，直接使用下方 JSON",
                            icon = Icons.Filled.Code,
                            checked = !settings.customConfig.isNullOrBlank(),
                        ) { enabled ->
                            store.updateSettings(
                                settings.copy(customConfig = if (enabled) (settings.customConfig ?: DEFAULT_CUSTOM_CONFIG) else null),
                            )
                        }
                    }
                    item {
                        SbItem(
                            title = "编辑自定义配置",
                            subtitle = if (settings.customConfig.isNullOrBlank()) "未启用" else "已启用（${settings.customConfig.length} 字符）",
                            icon = Icons.Filled.EditNote,
                            onClick = { showCustomConfigEditor = true },
                        )
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
                            subtitle = "基于 sing-box（AndroidLibBoxLite 内核）\nUI 风格参考 Kototoro；功能参考 LxBox / AsteriskBOX / ThroneForAndroid\n不集成 Root / Magisk 功能",
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
        PerAppPickerDialog(
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
            initial = settings.customConfig ?: DEFAULT_CUSTOM_CONFIG,
            onDismiss = { showCustomConfigEditor = false },
            onSave = { store.updateSettings(settings.copy(customConfig = it.ifBlank { null })) },
        )
    }
}

private const val DEFAULT_CUSTOM_CONFIG = """{
  "log": { "level": "warn" },
  "dns": { "servers": [ { "type": "udp", "tag": "dns", "server": "223.5.5.5" } ] },
  "inbounds": [ { "type": "tun", "tag": "tun-in", "address": ["172.18.0.1/30"], "auto_route": true } ],
  "outbounds": [ { "type": "direct", "tag": "direct" } ],
  "route": { "final": "direct", "auto_detect_interface": true }
}"""

// ---------------------------------------------------------------------------
// 分应用代理：应用选择器
// ---------------------------------------------------------------------------

private data class AppInfo(val packageName: String, val label: String)

@Composable
private fun PerAppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(selected) }

    val apps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.packageName == context.packageName }
            .map { AppInfo(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用（${current.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.packageName in current,
                                onCheckedChange = { checked ->
                                    current = if (checked) current + app.packageName else current - app.packageName
                                },
                            )
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(current) }) { Text("保存（${current.size}）") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

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
