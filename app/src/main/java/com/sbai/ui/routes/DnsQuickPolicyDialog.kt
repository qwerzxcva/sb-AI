package com.sbai.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbai.data.DnsGroup
import com.sbai.data.DnsRule
import com.sbai.data.DnsServer
import java.util.UUID

/**
 * DNS 快速策略对话框：预设常用模板，用户勾选后一键生成多条 DNS 规则。
 * 参考 mihomo / FlClash 的 nameserver-policy 快速配置理念。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsQuickPolicyDialog(
    dnsServers: List<DnsServer>,
    dnsGroups: List<DnsGroup>,
    onDismiss: () -> Unit,
    onApply: (List<DnsRule>) -> Unit,
) {
    // 可用的 DNS server/group tag
    val availableTags = remember(dnsServers, dnsGroups) {
        dnsServers.filter { it.enabled }.map { it.tag } + dnsGroups.map { it.name }
    }

    // 预设模板开关
    var enableChinaDns by remember { mutableStateOf(true) }
    var enableAdBlock by remember { mutableStateOf(true) }
    var enableProxyDns by remember { mutableStateOf(false) }

    // 模板对应的 DNS server/group 选择
    var chinaDnsTag by remember { mutableStateOf(availableTags.firstOrNull() ?: "") }
    var proxyDnsTag by remember { mutableStateOf(availableTags.firstOrNull() ?: "") }

    var chinaDnsExpanded by remember { mutableStateOf(false) }
    var proxyDnsExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val templates = mutableListOf<DnsRule>()

                // 模板 1：国内域名走指定 DNS（通常是阿里 / 腾讯等国内 DNS）
                if (enableChinaDns && chinaDnsTag.isNotBlank()) {
                    templates.add(
                        DnsRule(
                            id = UUID.randomUUID().toString(),
                            name = "国内域名 DNS",
                            enabled = true,
                            action = "route",
                            server = chinaDnsTag,
                            domainSuffixes = listOf("cn"),
                            ruleSetTags = listOf("geosite-cn", "geosite-geolocation-cn"),
                        ),
                    )
                }

                // 模板 2：广告域名拒绝
                if (enableAdBlock) {
                    templates.add(
                        DnsRule(
                            id = UUID.randomUUID().toString(),
                            name = "广告域名拒绝",
                            enabled = true,
                            action = "reject",
                            rcode = "refused",
                            ruleSetTags = listOf("geosite-category-ads-all"),
                        ),
                    )
                }

                // 模板 3：代理节点域名走本地 DNS（避免循环依赖）
                if (enableProxyDns && proxyDnsTag.isNotBlank()) {
                    templates.add(
                        DnsRule(
                            id = UUID.randomUUID().toString(),
                            name = "代理节点域名 DNS",
                            enabled = true,
                            action = "route",
                            server = proxyDnsTag,
                            ruleSetTags = listOf("geosite-category-proxy"),
                        ),
                    )
                }

                onApply(templates)
            }) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("DNS 快速策略") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "勾选需要的策略，一键生成对应 DNS 规则。已有同名规则会被覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )

                HorizontalDivider()

                // 模板 1：国内域名
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableChinaDns,
                        onCheckedChange = { enableChinaDns = it },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("国内域名走指定 DNS", style = MaterialTheme.typography.titleSmall)
                }
                if (enableChinaDns) {
                    Text(
                        "geosite:cn / geosite:geolocation-cn 域名走国内 DNS（通常是阿里 / 腾讯等）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ExposedDropdownMenuBox(
                        expanded = chinaDnsExpanded,
                        onExpandedChange = { chinaDnsExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = chinaDnsTag.ifBlank { "（请选择）" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("DNS server / group") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(chinaDnsExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = chinaDnsExpanded,
                            onDismissRequest = { chinaDnsExpanded = false },
                        ) {
                            availableTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag) },
                                    onClick = { chinaDnsTag = tag; chinaDnsExpanded = false },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 模板 2：广告域名拒绝
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableAdBlock,
                        onCheckedChange = { enableAdBlock = it },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("广告域名拒绝", style = MaterialTheme.typography.titleSmall)
                }
                if (enableAdBlock) {
                    Text(
                        "geosite:category-ads-all 域名返回 refused（需已启用该规则集）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                HorizontalDivider()

                // 模板 3：代理节点域名走本地 DNS
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableProxyDns,
                        onCheckedChange = { enableProxyDns = it },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("代理节点域名走本地 DNS", style = MaterialTheme.typography.titleSmall)
                }
                if (enableProxyDns) {
                    Text(
                        "geosite:category-proxy 域名走本地 DNS（避免循环依赖）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ExposedDropdownMenuBox(
                        expanded = proxyDnsExpanded,
                        onExpandedChange = { proxyDnsExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = proxyDnsTag.ifBlank { "（请选择）" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("DNS server / group") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(proxyDnsExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = proxyDnsExpanded,
                            onDismissRequest = { proxyDnsExpanded = false },
                        ) {
                            availableTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag) },
                                    onClick = { proxyDnsTag = tag; proxyDnsExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
