package com.sbai.service

import com.sbai.data.DnsRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * DNS 规则 JSON 片段编解码（粘贴 dns.rules 里的单个规则对象）。
 * 支持解析：domain/suffix/keyword/regex/ip_cidr/rule_set/network/port/query_type/
 * server/ip_strategy/disable_cache/rewrite_ttl/client_subnet/action/rcode/answer/ns/extra/timeout。
 */
object DnsRuleJsonCodec {

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface ParseResult {
        data class Success(val rule: DnsRule) : ParseResult
        data class Failure(val message: String) : ParseResult
    }

    fun fromJson(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParseResult.Failure("内容为空")
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }
            .getOrElse { return ParseResult.Failure("不是合法的 JSON 对象：${it.message}") }

        return runCatching {
            fun strings(key: String): List<String> = when (val v = obj[key]) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrBlank() }
                is JsonPrimitive -> v.contentOrBlank()?.let { listOf(it) }.orEmpty()
                else -> emptyList()
            }.distinct()

            fun ints(key: String): List<Int> = when (val v = obj[key]) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                is JsonPrimitive -> listOfNotNull(v.intOrNull)
                else -> emptyList()
            }.distinct()

            fun str(key: String): String =
                obj[key]?.jsonPrimitive?.content.orEmpty()

            fun bool(key: String): Boolean =
                obj[key]?.jsonPrimitive?.booleanOrNull == true

            ParseResult.Success(
                DnsRule(
                    name = str("tag").ifBlank { str("name") },
                    domains = strings("domain"),
                    domainSuffixes = strings("domain_suffix"),
                    domainKeywords = strings("domain_keyword"),
                    domainRegexes = strings("domain_regex"),
                    ruleSetTags = strings("rule_set"),
                    ipCidrs = strings("ip_cidr"),
                    networks = strings("network"),
                    ports = ints("port"),
                    queryTypes = strings("query_type"),
                    server = str("server"),
                    ipStrategy = str("ip_strategy"),
                    disableCache = bool("disable_cache"),
                    rewriteTtl = obj["rewrite_ttl"]?.jsonPrimitive?.intOrNull,
                    clientSubnet = str("client_subnet").ifBlank { null },
                    action = str("action").ifBlank { "route" },
                    rcode = str("rcode"),
                    answers = strings("answer"),
                    ns = strings("ns"),
                    extra = strings("extra"),
                    timeout = str("timeout"),
                ),
            )
        }.getOrElse { ParseResult.Failure("解析失败：${it.message}") }
    }

    private fun JsonPrimitive.contentOrBlank(): String? =
        runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() }
}
