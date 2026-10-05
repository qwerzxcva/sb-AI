package com.sbai.service

/**
 * 通知栏 / 快捷方式切换节点（参考 Throne proxy-connection 的 next/prev profile）。
 *
 * 给定一个代理组（selector/urltest），计算「下一个/上一个」可切换的出站 tag。
 * 纯逻辑，方便单测；实际的 selectOutbound 调用由调用方（VpnControlReceiver）执行。
 *
 * 语义：
 *  - items 按顺序排列；next 取当前 selected 之后第一个「可切换」的项，循环到开头；
 *    prev 反向，循环到末尾。
 *  - 跳过 selectable=false 的组（只有 leaf 节点可切）。
 *  - 无当前选中 / 只剩一个可选 → 返回 null（无法切换）。
 */
object NodeSwitcher {

    data class Item(val tag: String, val selectable: Boolean)

    /**
     * 计算切换目标。
     * @param items 组的全部成员（有序）
     * @param selected 当前选中的 tag
     * @param forward true=下一个，false=上一个
     * @return 目标 tag，或 null（无可切换）
     */
    fun nextTag(items: List<Item>, selected: String?, forward: Boolean): String? {
        val selectable = items.filter { it.selectable }
        if (selectable.isEmpty()) return null
        if (selectable.size == 1 && selectable.first().tag == selected) return null
        if (selected == null) return if (forward) selectable.first().tag else selectable.last().tag

        // 找到当前 selected 在 selectable 列表中的位置；不在（如当前选的是不可切项）则从头部/尾部开始
        val idx = selectable.indexOfFirst { it.tag == selected }
        return when {
            idx < 0 -> if (forward) selectable.first().tag else selectable.last().tag
            forward -> selectable[(idx + 1) % selectable.size].tag
            else -> selectable[(idx - 1 + selectable.size) % selectable.size].tag
        }
    }
}
