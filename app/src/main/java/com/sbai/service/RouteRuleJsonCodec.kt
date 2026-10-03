package com.sbai.service

import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 路由规则 JSON 片段编解码（LxBox 风格：可直接粘贴 route.rules 里的单个规则对象）。
 *
 * 支持解析：domain / domain_suffix / domain_keyword / domain_regex / ip_cidr / rule_set /
 * network / protocol / port / port_range / invert / outbound / type=logical(mode,rules)。
 * 无法映射到表单的字段会被忽略（不报错），避免用户粘贴复杂规则时卡住。
 */
object RouteRuleJsonCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true   // toJson 输出面向用户复制/阅读，使用格式化排版
    }

    sealed interface ParseResult {
        data class Success(val rule: RouteRule) : ParseResult
        data class Failure(val message: String) : ParseResult
    }

    fun fromJson(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParseResult.Failure("内容为空")

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }
            .getOrElse { return ParseResult.Failure("不是合法的 JSON 对象：${it.message}") }

        // 先判 logical（它自身也含 rules 数组，不能误当包裹）；再允许 {"rules":[{...}]} 包裹取首个
        val isLogical = obj["type"]?.jsonPrimitive?.content == "logical"
        val rule = when {
            isLogical -> obj
            obj.containsKey("rules") -> runCatching {
                obj["rules"]!!.jsonArray.firstOrNull()?.jsonObject
            }.getOrNull() ?: return ParseResult.Failure("rules 数组为空")
            else -> obj
        }

        return runCatching {
            val logical = rule["type"]?.jsonPrimitive?.content == "logical"
            val mode = rule["mode"]?.jsonPrimitive?.content
            val children = if (logical) rule["rules"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            else emptyList()

            // logical 的子规则里聚合各类字段；非 logical 直接读自身
            val sources: List<JsonObject> = if (logical) children else listOf(rule)

            fun strings(key: String): List<String> = sources.flatMap { o ->
                when (val v = o[key]) {
                    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
                    is JsonPrimitive -> listOfNotNull(v.contentOrNullSafe())
                    else -> emptyList()
                }
            }.distinct()

            fun ints(key: String): List<Int> = sources.flatMap { o ->
                when (val v = o[key]) {
                    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    is JsonPrimitive -> listOfNotNull(v.intOrNull)
                    else -> emptyList()
                }
            }.distinct()

            val outbound = sources.mapNotNull { it["outbound"]?.jsonPrimitive?.content }.firstOrNull()
            val invert = sources.any { it["invert"]?.jsonPrimitive?.booleanOrNull == true } ||
                    rule["invert"]?.jsonPrimitive?.booleanOrNull == true

            ParseResult.Success(
                RouteRule(
                    name = "",
                    action = when (outbound) {
                        "block" -> RuleAction.BLOCK
                        "direct" -> RuleAction.DIRECT
                        else -> RuleAction.PROXY
                    },
                    domains = strings("domain"),
                    domainSuffixes = strings("domain_suffix"),
                    domainKeywords = strings("domain_keyword"),
                    domainRegexes = strings("domain_regex"),
                    ipCidrs = strings("ip_cidr"),
                    ruleSetTags = strings("rule_set"),
                    networks = strings("network"),
                    protocols = strings("protocol"),
                    ports = ints("port"),
                    portRanges = strings("port_range"),
                    sourceIpCidrs = strings("source_ip_cidr"),
                    sourcePorts = ints("source_port"),
                    sourcePortRanges = strings("source_port_range"),
                    packageNames = strings("package_name"),
                    processNames = strings("process_name"),
                    processPaths = strings("process_path"),
                    users = strings("user"),
                    userIds = ints("user_id"),
                    networkTypes = strings("network_type"),
                    wifiSsids = strings("wifi_ssid"),
                    wifiBssids = strings("wifi_bssid"),
                    inbounds = strings("inbound"),
                    clashMode = sources.mapNotNull { it["clash_mode"]?.jsonPrimitive?.content }.firstOrNull() ?: "",
                    sourceIpIsPrivate = sources.any { it["source_ip_is_private"]?.jsonPrimitive?.booleanOrNull == true },
                    ipIsPrivate = sources.any { it["ip_is_private"]?.jsonPrimitive?.booleanOrNull == true },
                    networkIsExpensive = sources.any { it["network_is_expensive"]?.jsonPrimitive?.booleanOrNull == true },
                    logic = if (logical && mode == "or") RuleLogic.OR else RuleLogic.AND,
                    invert = invert,
                ),
            )
        }.getOrElse { ParseResult.Failure("解析失败：${it.message}") }
    }

    /** 反向：把表单规则导出为 sing-box route rule JSON 片段（供用户复制/校验） */
    fun toJson(rule: RouteRule, entryTag: String = "proxy"): String {
        val outbound = when (rule.action) {
            RuleAction.BLOCK -> "block"
            RuleAction.DIRECT -> "direct"
            RuleAction.PROXY -> entryTag
        }

        fun putAll(b: kotlinx.serialization.json.JsonObjectBuilder, domain: Boolean, ip: Boolean, transport: Boolean) {
            if (domain) {
                if (rule.domains.isNotEmpty()) b.putJsonArray("domain") { rule.domains.forEach { add(it) } }
                if (rule.domainSuffixes.isNotEmpty()) b.putJsonArray("domain_suffix") { rule.domainSuffixes.forEach { add(it) } }
                if (rule.domainKeywords.isNotEmpty()) b.putJsonArray("domain_keyword") { rule.domainKeywords.forEach { add(it) } }
                if (rule.domainRegexes.isNotEmpty()) b.putJsonArray("domain_regex") { rule.domainRegexes.forEach { add(it) } }
            }
            if (ip) {
                if (rule.ipCidrs.isNotEmpty()) b.putJsonArray("ip_cidr") { rule.ipCidrs.forEach { add(it) } }
                if (rule.ruleSetTags.isNotEmpty()) b.putJsonArray("rule_set") { rule.ruleSetTags.forEach { add(it) } }
            }
            if (transport) {
                if (rule.networks.isNotEmpty()) b.putJsonArray("network") { rule.networks.forEach { add(it) } }
                if (rule.protocols.isNotEmpty()) b.putJsonArray("protocol") { rule.protocols.forEach { add(it) } }
                if (rule.ports.isNotEmpty()) b.putJsonArray("port") { rule.ports.forEach { add(it) } }
                if (rule.portRanges.isNotEmpty()) b.putJsonArray("port_range") { rule.portRanges.forEach { add(it) } }
            }
        }

        val element = if (rule.logic == RuleLogic.OR) {
            val children = listOf(
                buildJsonObject { putAll(this, domain = true, ip = false, transport = false) },
                buildJsonObject { putAll(this, domain = false, ip = true, transport = false) },
                buildJsonObject { putAll(this, domain = false, ip = false, transport = true) },
            ).filter { it.isNotEmpty() }

            if (children.size <= 1) {
                buildJsonObject {
                    children.firstOrNull()?.forEach { (k, v) -> put(k, v) }
                    if (rule.invert) put("invert", true)
                    put("outbound", outbound)
                }
            } else {
                buildJsonObject {
                    put("type", "logical")
                    put("mode", "or")
                    putJsonArray("rules") { children.forEach { add(it) } }
                    if (rule.invert) put("invert", true)
                    put("outbound", outbound)
                }
            }
        } else {
            buildJsonObject {
                putAll(this, domain = true, ip = true, transport = true)
                if (rule.invert) put("invert", true)
                put("outbound", outbound)
            }
        }

        return json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { if (isString) content else content }.getOrNull()?.takeIf { it.isNotBlank() }
}
