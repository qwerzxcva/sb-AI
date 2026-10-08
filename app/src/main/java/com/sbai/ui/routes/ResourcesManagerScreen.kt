package com.sbai.ui.routes

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sbai.data.Resource
import com.sbai.data.ResourceType
import com.sbai.data.RuleStore
import com.sbai.ui.components.BottomBarClearance
import com.sbai.ui.components.BottomBarController
import com.sbai.ui.components.FabBottomBarClearance
import com.sbai.ui.components.RestoreBottomBarOnDispose
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 资源管理（全屏二级页）：IP 列表 / GeoIP / hosts / 规则集 / 自定义。
 * 入口在「路由」页第 1 页规则集组；资源被 SingBoxConfigGenerator 消费：
 * CHINA_IP 类型启用时自动注入 direct 路由，ResourceUpdateWorker 定期从 URL 更新。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ResourcesManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current

    val resources by remember(store) {
        store.state.map { it.settings.resources }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings.resources })
    val settings by remember(store) {
        store.state.map { it.settings }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings })

    var editingResource by remember { mutableStateOf<Resource?>(null) }

    editingResource?.let { res ->
        ResourceEditorDialog(
            initial = res,
            onDismiss = { editingResource = null },
            onSave = { updated ->
                store.updateSettings(settings.copy(resources = settings.resources.map { r ->
                    if (r.id == updated.id) updated else r
                }))
                editingResource = null
            },
        )
        return
    }

    BackHandler(enabled = true) { BottomBarController.show(); onBack() }
    RestoreBottomBarOnDispose()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资源管理（${resources.size}）") },
                navigationIcon = {
                    IconButton(onClick = { BottomBarController.show(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val next = settings.resources + Resource()
                    store.updateSettings(settings.copy(resources = next))
                    editingResource = next.lastOrNull()
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加资源") },
                modifier = Modifier.padding(bottom = FabBottomBarClearance),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = tokens.screenHorizontalPadding),
            contentPadding = PaddingValues(bottom = BottomBarClearance),
        ) {
            item {
                Text(
                    "资源被路由规则引用：CHINA_IP 启用时自动注入 direct 路由；支持 URL 自动更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            if (resources.isEmpty()) {
                item {
                    Text(
                        "暂无资源。点击右下角「添加资源」新增 IP 列表等。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                itemsIndexed(resources) { _, res ->
                    val gap = System.currentTimeMillis() - res.lastUpdatedAt
                    val ageStr = if (res.lastUpdatedAt == 0L) "未更新" else "${gap / 3600_000}h 前"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .background(
                                if (res.enabled) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                res.name.ifBlank { "（未命名）" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (res.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                res.resType.displayName + " · " + ageStr +
                                    (res.lastError?.let { " · 错误: $it" }.orEmpty()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { editingResource = res },
                                contentPadding = PaddingValues(8.dp),
                            ) { Text("编辑", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(
                                onClick = {
                                    store.updateSettings(settings.copy(resources = settings.resources.map { r ->
                                        if (r.id == res.id) r.copy(enabled = !r.enabled) else r
                                    }))
                                },
                                contentPadding = PaddingValues(8.dp),
                            ) { Text(if (res.enabled) "禁用" else "启用", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(
                                onClick = {
                                    store.updateSettings(settings.copy(resources = settings.resources.filter { it.id != res.id }))
                                },
                                contentPadding = PaddingValues(8.dp),
                            ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ResourceEditorDialog(
    initial: Resource,
    onDismiss: () -> Unit,
    onSave: (Resource) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var resType by remember { mutableStateOf(initial.resType) }
    var url by remember { mutableStateOf(initial.url) }
    var content by remember { mutableStateOf(initial.content) }
    var updateIntervalHours by remember { mutableStateOf(initial.updateIntervalHours.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资源") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("资源名称") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("资源类型", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResourceType.entries.forEach { type ->
                        FilterChip(
                            selected = resType == type,
                            onClick = { resType = type },
                            label = { Text(type.displayName) },
                        )
                    }
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("更新 URL（可选，留空则仅手动维护）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("资源内容（可手动编辑；china_ip 一行一条 CIDR）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    minLines = 6,
                )

                OutlinedTextField(
                    value = updateIntervalHours,
                    onValueChange = { updateIntervalHours = it },
                    label = { Text("更新间隔（小时）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val interval = updateIntervalHours.toIntOrNull() ?: 24
                onSave(initial.copy(
                    name = name.trim(),
                    resType = resType,
                    url = url.trim(),
                    content = content,
                    updateIntervalHours = interval,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
