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
 * 路由规则 JSON 片段编解码：可直接粘贴 route.rules 里的单个规则对象。
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

    /** 完整解析结果：规则 + 附带的规则集定义 */
    data class FullResult(
        val rule: RouteRule,
        val ruleSets: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    )

    fun fromJson(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParseResult.Failure("内容为空")

        var obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }
            .getOrElse { return ParseResult.Failure("不是合法的 JSON 对象：${it.message}") }

        // 完整配置格式：{"route":{"rule_set":[...],"rules":[...]}} 剥掉 route 包裹
        if (obj.containsKey("route")) {
            obj = runCatching { obj["route"]!!.jsonObject }.getOrElse { obj }
        }

        // 先判 logical（它自身也含 rules 数组，不能误当包裹）；再允许 {"rules":[{...}]} 包裹取首个
        val isLogical = obj["type"]?.jsonPrimitive?.content == "logical"
        val rule = when {
            isLogical -> obj
            obj.containsKey("rules") && obj["rules"] is JsonArray -> runCatching {
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
            val action = sources.mapNotNull { it["action"]?.jsonPrimitive?.content }.firstOrNull()
            val invert = sources.any { it["invert"]?.jsonPrimitive?.booleanOrNull == true } ||
                    rule["invert"]?.jsonPrimitive?.booleanOrNull == true
            // 只有明确指定了非内置 outbound 时才保留 tag（direct/block 是内置语义）
            val explicitOutbound = outbound?.takeIf {
                it.isNotBlank() && it != "direct" && it != "block"
            }.orEmpty()
            // IP 版本勾选：内核没有单独的 ipv4/ipv6 开关字段，只有 ip_version。
            // ip_version=4 → 仅 IPv4，=6 → 仅 IPv6，缺省 → 两个都勾。
            val parsedIpVersion = sources.mapNotNull { it["ip_version"]?.jsonPrimitive?.intOrNull }
                .firstOrNull() ?: 0
            val ipv4 = parsedIpVersion != 6
            val ipv6 = parsedIpVersion != 4

            // action 映射必须覆盖内核全部枚举，否则粘贴 JSON 时会默默把
            // route-options / sniff / resolve / hijack-dns 改成 ROUTE_PROXY（语义被篡改）。
            val resolvedAction = when (action) {
                "route" -> if (outbound == "direct") RuleAction.ROUTE_DIRECT else RuleAction.ROUTE_PROXY
                "route-options" -> RuleAction.ROUTE_OPTIONS
                "reject" -> RuleAction.REJECT
                "hijack-dns" -> RuleAction.HIJACK_DNS
                "sniff" -> RuleAction.SNIFF
                "resolve" -> RuleAction.RESOLVE
                else -> when (outbound) {
                    "block" -> RuleAction.REJECT
                    "direct" -> RuleAction.ROUTE_DIRECT
                    else -> RuleAction.ROUTE_PROXY
                }
            }

            ParseResult.Success(
                RouteRule(
                    name = "",
                    action = resolvedAction,
                    domains = strings("domain"),
                    domainSuffixes = strings("domain_suffix"),
                    domainKeywords = strings("domain_keyword"),
                    domainRegexes = strings("domain_regex"),
                    geosites = strings("geosite"),
                    ipCidrs = strings("ip_cidr"),
                    geoips = strings("geoip"),
                    ruleSetTags = strings("rule_set"),
                    networks = strings("network"),
                    protocols = strings("protocol"),
                    ports = ints("port"),
                    portRanges = strings("port_range"),
                    sourceIpCidrs = strings("source_ip_cidr"),
                    sourcePorts = ints("source_port"),
                    sourcePortRanges = strings("source_port_range"),
                    packageNames = strings("package_name"),
                    // 包名正则匹配（兼容旧格式）
                    packageNameRegexes = strings("package_name_regex"),
                    processNames = strings("process_name"),
                    processPaths = strings("process_path"),
                    processPathRegexes = strings("process_path_regex"),
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
                    // route-options / reject 动作字段（粘贴导入时不能丢）
                    outboundTag = explicitOutbound,
                    ipVersion = sources.mapNotNull { it["ip_version"]?.jsonPrimitive?.intOrNull }
                        .firstOrNull() ?: 0,
                    rejectMethod = sources.mapNotNull { it["reject_method"]?.jsonPrimitive?.content }
                        .firstOrNull() ?: "default",
                    overrideAddress = sources.mapNotNull { it["override_address"]?.jsonPrimitive?.content }
                        ?.firstOrNull().orEmpty(),
                    overridePort = sources.mapNotNull { it["override_port"]?.jsonPrimitive?.intOrNull }
                        ?.firstOrNull() ?: 0,
                    tlsFragment = sources.any { it["tls_fragment"]?.jsonPrimitive?.booleanOrNull == true },
                    tlsRecordFragment = sources.any { it["tls_record_fragment"]?.jsonPrimitive?.booleanOrNull == true },
                    tlsFragmentFallbackDelay = sources
                        .mapNotNull { it["tls_fragment_fallback_delay"]?.jsonPrimitive?.content }
                        ?.firstOrNull().orEmpty(),
                    udpConnect = sources.any { it["udp_connect"]?.jsonPrimitive?.booleanOrNull == true },
                    udpTimeout = sources.mapNotNull { it["udp_timeout"]?.jsonPrimitive?.content }
                        ?.firstOrNull().orEmpty(),
                    udpDisableDomainUnmapping = sources.any {
                        it["udp_disable_domain_unmapping"]?.jsonPrimitive?.booleanOrNull == true
                    },
                    dnsTag = sources.mapNotNull { it["dns_tag"]?.jsonPrimitive?.content }
                        ?.firstOrNull(),
                    ipv4 = ipv4,
                    ipv6 = ipv6,
                    logic = if (logical && mode == "or") RuleLogic.OR else RuleLogic.AND,
                    invert = invert,
                ),
            )
        }.getOrElse { ParseResult.Failure("解析失败：${it.message}") }
    }

    /** 反向：把表单规则导出为 sing-box route rule JSON 片段（供用户复制/校验） */
    fun toJson(rule: RouteRule, entryTag: String = "proxy"): String {
        val outbound = when (rule.action) {
            RuleAction.REJECT -> "block"
            RuleAction.ROUTE_DIRECT -> "direct"
            RuleAction.ROUTE_PROXY -> entryTag
            // 非路由动作导出为 route 到代理（片段仅供 UI 校验/复制）
            else -> entryTag
        }

        fun putAll(b: kotlinx.serialization.json.JsonObjectBuilder, domain: Boolean, ip: Boolean, transport: Boolean) {
            if (domain) {
                if (rule.domains.isNotEmpty()) b.putJsonArray("domain") { rule.domains.forEach { add(it) } }
                if (rule.domainSuffixes.isNotEmpty()) b.putJsonArray("domain_suffix") { rule.domainSuffixes.forEach { add(it) } }
                if (rule.domainKeywords.isNotEmpty()) b.putJsonArray("domain_keyword") { rule.domainKeywords.forEach { add(it) } }
                if (rule.domainRegexes.isNotEmpty()) b.putJsonArray("domain_regex") { rule.domainRegexes.forEach { add(it) } }
                if (rule.geosites.isNotEmpty()) b.putJsonArray("geosite") { rule.geosites.forEach { add(it) } }
            }
            if (ip) {
                if (rule.ipCidrs.isNotEmpty()) b.putJsonArray("ip_cidr") { rule.ipCidrs.forEach { add(it) } }
                if (rule.geoips.isNotEmpty()) b.putJsonArray("geoip") { rule.geoips.forEach { add(it) } }
                if (rule.ruleSetTags.isNotEmpty()) b.putJsonArray("rule_set") { rule.ruleSetTags.forEach { add(it) } }
            }
            if (transport) {
                if (rule.networks.isNotEmpty()) b.putJsonArray("network") { rule.networks.forEach { add(it) } }
                if (rule.protocols.isNotEmpty()) b.putJsonArray("protocol") { rule.protocols.forEach { add(it) } }
                if (rule.ports.isNotEmpty()) b.putJsonArray("port") { rule.ports.forEach { add(it) } }
                if (rule.portRanges.isNotEmpty()) b.putJsonArray("port_range") { rule.portRanges.forEach { add(it) } }
                if (rule.ipVersion == 4 || rule.ipVersion == 6) b.put("ip_version", rule.ipVersion)
            }
            // 动作字段（reject / route-options 专属，粘贴片段时一并导出）
            if (rule.action == RuleAction.REJECT && rule.rejectMethod != "default") {
                b.put("reject_method", rule.rejectMethod)
            }
            if (rule.action == RuleAction.ROUTE_OPTIONS) {
                if (rule.overrideAddress.isNotBlank()) b.put("override_address", rule.overrideAddress)
                if (rule.overridePort > 0) b.put("override_port", rule.overridePort)
                if (rule.tlsFragment) b.put("tls_fragment", true)
                if (rule.tlsRecordFragment) b.put("tls_record_fragment", true)
                if (rule.tlsFragmentFallbackDelay.isNotBlank()) {
                    b.put("tls_fragment_fallback_delay", rule.tlsFragmentFallbackDelay)
                }
                if (rule.udpConnect) b.put("udp_connect", true)
                if (rule.udpTimeout.isNotBlank()) b.put("udp_timeout", rule.udpTimeout)
                if (rule.udpDisableDomainUnmapping) b.put("udp_disable_domain_unmapping", true)
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
