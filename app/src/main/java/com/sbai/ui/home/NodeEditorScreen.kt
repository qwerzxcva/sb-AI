package com.sbai.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbai.ui.components.BottomBarController
import com.sbai.data.ProxyNode
import com.sbai.ui.components.RestoreBottomBarOnDispose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 支持的 outbound 类型（含 HTTP / SOCKS 隧道代理） */
private val OUTBOUND_TYPES = listOf(
    "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria",
    "http", "socks", "wireguard", "tor", "ssh", "direct", "block",
)

/** uTLS 指纹（#2） */
private val UTLS_FINGERPRINTS = listOf(
    "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
    "chrome_120", "chrome_116", "chrome_115", "chrome_106_shuffle",
    "firefox", "firefox_120", "firefox_115", "firefox_105",
    "safari", "safari_20", "safari_17_0", "safari_16_0", "safari_15_6", "safari_ios",
    "edge", "edge_112", "edge_101",
    "360", "360_7_5", "360_11_0",
    "qq", "qq_11_5",
    "ios", "random", "randomized",
)

/** 多路复用协议（#3） */
private val MUX_PROTOCOLS = listOf("h2mux", "smux", "yamux")

/**
 * 节点编辑器（整页，非弹窗）。
 * 表单模式 + JSON 模式双轨：表单生成 outbound JSON，JSON 模式可直接编辑/粘贴。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NodeEditorScreen(
    initial: ProxyNode,
    onBack: () -> Unit,
    onSave: (ProxyNode) -> Unit,
) {
    RestoreBottomBarOnDispose()

    // 解析已有 JSON 到表单字段
    val parsed = remember(initial.outboundJson) { parseOutbound(initial.outboundJson) }

    var jsonMode by remember { mutableStateOf(initial.outboundJson.isNotBlank() && parsed == null) }
    var rawJson by remember { mutableStateOf(initial.outboundJson) }

    var type by remember { mutableStateOf(parsed?.type ?: "vless") }
    var tag by remember { mutableStateOf(parsed?.tag ?: initial.name) }
    var server by remember { mutableStateOf(parsed?.server ?: "") }
    var port by remember { mutableStateOf(parsed?.port?.toString() ?: "") }
    var uuid by remember { mutableStateOf(parsed?.uuid ?: "") }
    var password by remember { mutableStateOf(parsed?.password ?: "") }
    var method by remember { mutableStateOf(parsed?.method ?: "") }
    var flow by remember { mutableStateOf(parsed?.flow ?: "") }
    var username by remember { mutableStateOf(parsed?.username ?: "") }

    // TLS
    var tlsEnabled by remember { mutableStateOf(parsed?.tlsEnabled ?: false) }
    var sni by remember { mutableStateOf(parsed?.sni ?: "") }
    var tlsInsecure by remember { mutableStateOf(parsed?.tlsInsecure ?: false) }
    var utlsEnabled by remember { mutableStateOf(parsed?.utlsEnabled ?: false) }
    var utlsFingerprint by remember { mutableStateOf(parsed?.utlsFingerprint ?: "chrome") }
    var realityEnabled by remember { mutableStateOf(parsed?.realityEnabled ?: false) }
    var realityPublicKey by remember { mutableStateOf(parsed?.realityPublicKey ?: "") }
    var realityShortId by remember { mutableStateOf(parsed?.realityShortId ?: "") }

    // 多路复用（#3）
    var muxEnabled by remember { mutableStateOf(parsed?.muxEnabled ?: false) }
    var muxProtocol by remember { mutableStateOf(parsed?.muxProtocol ?: "h2mux") }
    var muxMaxConnections by remember { mutableStateOf(parsed?.muxMaxConnections ?: "") }
    var muxPadding by remember { mutableStateOf(parsed?.muxPadding ?: false) }
    var muxBrutalEnabled by remember { mutableStateOf(parsed?.muxBrutalUpMbps ?: 0 > 0) }
    var muxBrutalUp by remember { mutableStateOf(parsed?.muxBrutalUpMbps?.toString() ?: "") }
    var muxBrutalDown by remember { mutableStateOf(parsed?.muxBrutalDownMbps?.toString() ?: "") }

    // 传输层
    var transportType by remember { mutableStateOf(parsed?.transportType ?: "") }
    var wsPath by remember { mutableStateOf(parsed?.wsPath ?: "") }
    var wsHost by remember { mutableStateOf(parsed?.wsHost ?: "") }
    var grpcServiceName by remember { mutableStateOf(parsed?.grpcServiceName ?: "") }
    var httpPath by remember { mutableStateOf(parsed?.httpPath ?: "") }

    var error by remember { mutableStateOf<String?>(null) }
    var showJsonPaste by remember { mutableStateOf(false) }

    // 拦截系统返回/侧滑，回到首页而不是退出应用
    BackHandler(enabled = true) { BottomBarController.show(); onBack() }

    fun buildOutbound(): String {
        val effectiveTag = tag.trim().ifBlank { server.trim().ifBlank { "node" } }
        return buildJsonObject {
            put("type", type)
            put("tag", effectiveTag)
            if (type != "direct" && type != "block") {
                put("server", server.trim())
                port.trim().toIntOrNull()?.let { put("server_port", it) }
            }
            when (type) {
                "vless" -> {
                    put("uuid", uuid.trim())
                    flow.trim().takeIf { it.isNotBlank() }?.let { put("flow", it) }
                }
                "vmess" -> {
                    put("uuid", uuid.trim())
                    put("security", method.trim().ifBlank { "auto" })
                }
                "trojan" -> put("password", password)
                "shadowsocks" -> {
                    put("method", method.trim().ifBlank { "2022-blake3-aes-128-gcm" })
                    put("password", password)
                }
                "hysteria2", "hysteria" -> put("password", password)
                "http", "socks" -> {
                    username.trim().takeIf { it.isNotBlank() }?.let { put("username", it) }
                    password.takeIf { it.isNotBlank() }?.let { put("password", it) }
                }
                "wireguard" -> {
                    put("private_key", password)
                }
                "ssh" -> {
                    username.trim().takeIf { it.isNotBlank() }?.let { put("user", it) }
                    password.takeIf { it.isNotBlank() }?.let { put("password", it) }
                }
            }

            // TLS
            if (tlsEnabled) {
                putJsonObject("tls") {
                    put("enabled", true)
                    sni.trim().takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                    if (tlsInsecure) put("insecure", true)
                    // uTLS 指纹（#2）
                    if (utlsEnabled) {
                        putJsonObject("utls") {
                            put("enabled", true)
                            put("fingerprint", utlsFingerprint)
                        }
                    }
                    // REALITY
                    if (realityEnabled) {
                        putJsonObject("reality") {
                            put("enabled", true)
                            realityPublicKey.trim().takeIf { it.isNotBlank() }?.let { put("public_key", it) }
                            realityShortId.trim().takeIf { it.isNotBlank() }?.let { put("short_id", it) }
                        }
                    }
                }
            }

            // 多路复用（#3）
            if (muxEnabled) {
                putJsonObject("multiplex") {
                    put("enabled", true)
                    put("protocol", muxProtocol)
                    muxMaxConnections.trim().toIntOrNull()?.let { put("max_connections", it) }
                    put("padding", muxPadding)
                    if (muxBrutalEnabled) {
                        putJsonObject("brutal") {
                            put("enabled", true)
                            muxBrutalUp.trim().toIntOrNull()?.let { put("up_mbps", it) }
                            muxBrutalDown.trim().toIntOrNull()?.let { put("down_mbps", it) }
                        }
                    }
                }
            }

            // 传输层
            when (transportType) {
                "ws" -> putJsonObject("transport") {
                    put("type", "ws")
                    wsPath.trim().takeIf { it.isNotBlank() }?.let { put("path", it) }
                    wsHost.trim().takeIf { it.isNotBlank() }?.let {
                        putJsonObject("headers") { put("Host", it) }
                    }
                }
                "grpc" -> putJsonObject("transport") {
                    put("type", "grpc")
                    grpcServiceName.trim().takeIf { it.isNotBlank() }?.let { put("service_name", it) }
                }
                "http" -> putJsonObject("transport") {
                    put("type", "http")
                    httpPath.trim().takeIf { it.isNotBlank() }?.let { put("path", it) }
                    wsHost.trim().takeIf { it.isNotBlank() }?.let {
                        putJsonArray("host") { add(it) }
                    }
                }
            }
        }.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.outboundJson.isBlank()) "添加节点" else "编辑节点") },
                navigationIcon = {
                    IconButton(onClick = { BottomBarController.show(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { jsonMode = !jsonMode }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = if (jsonMode) "表单模式" else "JSON 模式")
                    }
                    TextButton(onClick = {
                        val json = if (jsonMode) rawJson else buildOutbound()
                        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrElse {
                            error = "JSON 无效: ${it.message}"; return@TextButton
                        }
                        if (obj["type"] == null || obj["tag"] == null) {
                            error = "outbound 必须包含 type 与 tag"; return@TextButton
                        }
                        val nodeTag = obj["tag"]!!.jsonPrimitive.content
                        onSave(initial.copy(name = tag.trim().ifBlank { nodeTag }, outboundJson = json))
                    }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (jsonMode) {
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = { rawJson = it },
                    label = { Text("sing-box outbound JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                )
                TextButton(onClick = { showJsonPaste = true }) { Text("从剪贴板粘贴") }
            } else {
                // ---- 基础 ----
                SectionTitle("基础")
                DropdownField("协议类型", type, OUTBOUND_TYPES) { type = it }
                OutlinedTextField(
                    value = tag, onValueChange = { tag = it },
                    label = { Text("标签 tag（留空自动生成）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type != "direct" && type != "block") {
                    OutlinedTextField(
                        value = server, onValueChange = { server = it },
                        label = { Text("服务器地址") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("端口") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ---- 认证（按类型显示不同字段） ----
                when (type) {
                    "vless" -> {
                        SectionTitle("认证")
                        OutlinedTextField(value = uuid, onValueChange = { uuid = it }, label = { Text("UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = flow, onValueChange = { flow = it }, label = { Text("flow（如 xtls-rprx-vision）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "vmess" -> {
                        SectionTitle("认证")
                        OutlinedTextField(value = uuid, onValueChange = { uuid = it }, label = { Text("UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        DropdownField("加密方式 security", method.ifBlank { "auto" }, listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) { method = it }
                    }
                    "trojan", "hysteria2", "hysteria" -> {
                        SectionTitle("认证")
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "shadowsocks" -> {
                        SectionTitle("认证")
                        DropdownField("加密方式 method", method.ifBlank { "2022-blake3-aes-128-gcm" }, listOf(
                            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
                            "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "none",
                        )) { method = it }
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "http", "socks" -> {
                        SectionTitle("认证（可选）")
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(
                            if (type == "socks") "SOCKS 隧道代理（支持 SOCKS5）" else "HTTP 隧道代理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "wireguard" -> {
                        SectionTitle("WireGuard")
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("private_key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "ssh" -> {
                        SectionTitle("SSH")
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码 / 私钥") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }

                // ---- TLS ----
                if (type in listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "hysteria", "http", "socks")) {
                    SectionTitle("TLS")
                    SwitchRow("启用 TLS", tlsEnabled) { tlsEnabled = it }
                    if (tlsEnabled) {
                        OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI / server_name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        SwitchRow("跳过证书校验（insecure）", tlsInsecure) { tlsInsecure = it }

                        // uTLS 指纹（#2）
                        SwitchRow("uTLS 指纹伪装", utlsEnabled) { utlsEnabled = it }
                        if (utlsEnabled) {
                            DropdownField("指纹 fingerprint", utlsFingerprint, UTLS_FINGERPRINTS) { utlsFingerprint = it }
                        }

                        // REALITY
                        SwitchRow("REALITY", realityEnabled) { realityEnabled = it }
                        if (realityEnabled) {
                            OutlinedTextField(value = realityPublicKey, onValueChange = { realityPublicKey = it }, label = { Text("public_key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = realityShortId, onValueChange = { realityShortId = it }, label = { Text("short_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // ---- 多路复用（#3） ----
                if (type in listOf("vless", "vmess", "trojan", "shadowsocks")) {
                    SectionTitle("多路复用（Multiplex）")
                    SwitchRow("启用多路复用", muxEnabled) { muxEnabled = it }
                    if (muxEnabled) {
                        DropdownField("协议 protocol", muxProtocol, MUX_PROTOCOLS) { muxProtocol = it }
                        OutlinedTextField(
                            value = muxMaxConnections, onValueChange = { muxMaxConnections = it },
                            label = { Text("最大连接数 max_connections（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchRow("padding", muxPadding) { muxPadding = it }
                        SwitchRow("Brutal 拥塞控制", muxBrutalEnabled) { muxBrutalEnabled = it }
                        if (muxBrutalEnabled) {
                            OutlinedTextField(value = muxBrutalUp, onValueChange = { muxBrutalUp = it }, label = { Text("上行 up_mbps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = muxBrutalDown, onValueChange = { muxBrutalDown = it }, label = { Text("下行 down_mbps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // ---- 传输层 ----
                if (type in listOf("vless", "vmess", "trojan")) {
                    SectionTitle("传输层（Transport）")
                    DropdownField("类型", transportType.ifBlank { "（默认 tcp）" }, listOf("", "ws", "grpc", "http")) {
                        transportType = if (it.startsWith("（")) "" else it
                    }
                    when (transportType) {
                        "ws" -> {
                            OutlinedTextField(value = wsPath, onValueChange = { wsPath = it }, label = { Text("path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = wsHost, onValueChange = { wsHost = it }, label = { Text("Host 头") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        "grpc" -> {
                            OutlinedTextField(value = grpcServiceName, onValueChange = { grpcServiceName = it }, label = { Text("service_name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        "http" -> {
                            OutlinedTextField(value = httpPath, onValueChange = { httpPath = it }, label = { Text("path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = wsHost, onValueChange = { wsHost = it }, label = { Text("host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showJsonPaste) {
        NodeJsonPasteDialog(
            onDismiss = { showJsonPaste = false },
            onApply = { text ->
                rawJson = text
                jsonMode = true
                showJsonPaste = false
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.ifBlank { "（无）" }) },
                    onClick = { onChange(opt); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun NodeJsonPasteDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 outbound JSON") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onApply(text) }, enabled = text.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// outbound JSON → 表单字段
// ---------------------------------------------------------------------------

private data class OutboundForm(
    val type: String, val tag: String, val server: String, val port: Int?,
    val uuid: String, val password: String, val method: String, val flow: String, val username: String,
    val tlsEnabled: Boolean, val sni: String, val tlsInsecure: Boolean,
    val utlsEnabled: Boolean, val utlsFingerprint: String,
    val realityEnabled: Boolean, val realityPublicKey: String, val realityShortId: String,
    val muxEnabled: Boolean, val muxProtocol: String, val muxMaxConnections: String,
    val muxPadding: Boolean, val muxBrutalUpMbps: Int?, val muxBrutalDownMbps: Int?,
    val transportType: String, val wsPath: String, val wsHost: String,
    val grpcServiceName: String, val httpPath: String,
)

private val json = Json { ignoreUnknownKeys = true }

private fun parseOutbound(raw: String): OutboundForm? {
    if (raw.isBlank()) return null
    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    fun s(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
    fun i(key: String) = obj[key]?.jsonPrimitive?.content?.toIntOrNull()

    val tls = obj["tls"]?.jsonObject
    val utls = tls?.get("utls")?.jsonObject
    val reality = tls?.get("reality")?.jsonObject
    val mux = obj["multiplex"]?.jsonObject
    val brutal = mux?.get("brutal")?.jsonObject
    val transport = obj["transport"]?.jsonObject
    val headers = transport?.get("headers")?.jsonObject

    return OutboundForm(
        type = s("type"),
        tag = s("tag"),
        server = s("server"),
        port = i("server_port"),
        uuid = s("uuid"),
        password = s("password"),
        method = s("method").ifBlank { s("security") },
        flow = s("flow"),
        username = s("username").ifBlank { s("user") },
        tlsEnabled = tls?.get("enabled")?.jsonPrimitive?.content == "true",
        sni = tls?.get("server_name")?.jsonPrimitive?.content.orEmpty(),
        tlsInsecure = tls?.get("insecure")?.jsonPrimitive?.content == "true",
        utlsEnabled = utls?.get("enabled")?.jsonPrimitive?.content == "true",
        utlsFingerprint = utls?.get("fingerprint")?.jsonPrimitive?.content ?: "chrome",
        realityEnabled = reality?.get("enabled")?.jsonPrimitive?.content == "true",
        realityPublicKey = reality?.get("public_key")?.jsonPrimitive?.content.orEmpty(),
        realityShortId = reality?.get("short_id")?.jsonPrimitive?.content.orEmpty(),
        muxEnabled = mux?.get("enabled")?.jsonPrimitive?.content == "true",
        muxProtocol = mux?.get("protocol")?.jsonPrimitive?.content ?: "h2mux",
        muxMaxConnections = mux?.get("max_connections")?.jsonPrimitive?.content.orEmpty(),
        muxPadding = mux?.get("padding")?.jsonPrimitive?.content == "true",
        muxBrutalUpMbps = brutal?.get("up_mbps")?.jsonPrimitive?.content?.toIntOrNull(),
        muxBrutalDownMbps = brutal?.get("down_mbps")?.jsonPrimitive?.content?.toIntOrNull(),
        transportType = transport?.get("type")?.jsonPrimitive?.content.orEmpty(),
        wsPath = transport?.get("path")?.jsonPrimitive?.content.orEmpty(),
        wsHost = headers?.get("Host")?.jsonPrimitive?.content
            ?: (transport?.get("host")?.let { h ->
                runCatching { h.jsonObject.keys.firstOrNull() }.getOrNull()
            }.orEmpty()),
        grpcServiceName = transport?.get("service_name")?.jsonPrimitive?.content.orEmpty(),
        httpPath = transport?.get("path")?.jsonPrimitive?.content.orEmpty(),
    )
}
