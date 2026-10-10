package com.sbai.service

import com.sbai.data.OverridePriority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 配置覆盖合并器。
 *
 * 语义（对应用户需求）：
 *  - UI_HIGHEST：UI 生成的配置优先级最高。导入的 JSON 只能补充
 *    「UI 没有生成的字段 / 没有添加的数组项」。
 *  - IMPORT_HIGHEST：导入的 JSON 优先级最高，覆盖 UI 生成的同名字段。
 *
 * 合并规则：
 *  - 对象：逐 key 递归合并；标量冲突时高优先级方胜出。
 *  - 数组：
 *      · 元素是含 `tag`（或 `name`）的对象 → 按该 key 去重合并，高优先级方胜出，
 *        低优先级方独有的元素追加（用于 outbounds / dns.servers / rule_set）。
 *      · 其它数组（如 route.rules / dns.rules）→ 高优先级方在前、低优先级方在后拼接，
 *        并去掉完全相同的重复项。规则类数组顺序即匹配优先级，故高优先级必须在前。
 */
object ConfigMerger {

    private val json = Json { ignoreUnknownKeys = true }

    /** 数组元素用于去重的 key 字段（按优先级） */
    private val IDENTITY_KEYS = listOf("tag", "name")

    /**
     * lx 内核（sing-box-lx，无 with_clash_api tag）不支持的 experimental 字段。
     * 用户导入的完整 sing-box 配置（含 clash_api / v2ray_api）合并后会让内核
     * `decode config: clash api is not included in this build` 拒绝启动——
     * 真机复现「VPN 启用不生效」的根因。合并时剥离这些字段。
     */
    private val UNSUPPORTED_EXPERIMENTAL_KEYS = setOf("clash_api", "v2ray_api", "external_ui")

    fun merge(uiConfig: String, importedJson: String, priority: OverridePriority): String {
        val ui = json.parseToJsonElement(uiConfig).jsonObject
        val imported = json.parseToJsonElement(importedJson).jsonObject
        val (base, overlay) = when (priority) {
            // UI 最高：以导入为底，UI 覆盖其上
            OverridePriority.UI_HIGHEST -> imported to ui
            // 导入最高：以 UI 为底，导入覆盖其上
            OverridePriority.IMPORT_HIGHEST -> ui to imported
        }
        val merged = mergeObject(base, overlay)
        return json.encodeToString(JsonElement.serializer(), stripUnsupported(merged))
    }

    /** 剥离 lx 内核不支持的 experimental 子字段（clash_api / v2ray_api / external_ui）。 */
    private fun stripUnsupported(config: JsonObject): JsonObject {
        val exp = config["experimental"] as? JsonObject ?: return config
        val filtered = exp.filterKeys { it !in UNSUPPORTED_EXPERIMENTAL_KEYS }
        if (filtered.size == exp.size) return config
        return JsonObject(config.toMutableMap().apply {
            if (filtered.isEmpty()) remove("experimental") else put("experimental", JsonObject(filtered))
        })
    }

    private fun mergeObject(base: JsonObject, overlay: JsonObject): JsonObject = buildJsonObject {
        base.forEach { (k, v) -> put(k, v) }
        overlay.forEach { (k, v) ->
            val existing = base[k]
            put(k, mergeElement(existing, v, k))
        }
    }

    private fun mergeElement(base: JsonElement?, overlay: JsonElement, key: String): JsonElement {
        if (base == null) return overlay
        return when {
            base is JsonObject && overlay is JsonObject -> mergeObject(base, overlay)
            base is JsonArray && overlay is JsonArray -> mergeArray(base, overlay, key)
            else -> overlay   // 标量/类型不一致：高优先级（overlay）胜出
        }
    }

    private fun mergeArray(base: JsonArray, overlay: JsonArray, key: String): JsonArray {
        val baseIds = base.mapNotNull { identityOf(it) }
        val overlayIds = overlay.mapNotNull { identityOf(it) }

        // 双方都带 identity（tag/name）→ 按 identity 合并
        val identityMergeable = baseIds.isNotEmpty() && overlayIds.isNotEmpty()
        if (identityMergeable) {
            val result = buildJsonArray {
                // overlay（高优先级）在前，保留其顺序与内容
                overlay.forEach { add(it) }
                val overlayIdSet = overlayIds.toSet()
                base.forEach { item ->
                    val id = identityOf(item)
                    if (id == null || id !in overlayIdSet) add(item)
                }
            }
            return result
        }

        // 规则类数组：高优先级在前，去重完全相同项
        return buildJsonArray {
            overlay.forEach { add(it) }
            val overlaySet = overlay.toSet()
            base.forEach { if (it !in overlaySet) add(it) }
        }
    }

    private fun identityOf(element: JsonElement): String? {
        if (element !is JsonObject) return null
        IDENTITY_KEYS.forEach { k ->
            val v = element[k]
            if (v is JsonPrimitive && v.isString) return v.content
        }
        return null
    }
}
