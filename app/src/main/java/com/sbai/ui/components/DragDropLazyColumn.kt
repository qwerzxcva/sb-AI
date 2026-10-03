package com.sbai.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * 长按拖动排序的 LazyColumn（越靠上优先级越高）。
 *
 * 关键修复（之前拖拽失效/中断的根因）：
 *  - pointerInput 的 key 用 items.size（列表尺寸），**不用**被拖项的 key。
 *    被拖项的 key 会在重排时移动 index，导致 pointerInput 被重组销毁、拖拽中断。
 *  - 用 draggingKey（内容 key）跟踪被拖项，重排后用 indexOfFirst 求实时 index。
 *  - 位移用累计 offset，越过相邻项高度一半即交换数据源（onMove），
 *    交换后从 offset 里减去该项高度，保证视觉连续、不跳变。
 *  - 松手（onDragEnd）提交最终顺序；取消（onDragCancel）只复位不提交。
 */
@Composable
fun <T> DragDropLazyColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
    onDragEnd: (List<Any>) -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    listState: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T, isDragging: Boolean) -> Unit,
) {
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var hasDragged by remember { mutableStateOf(false) }

    // 被拖项的实时 index（重排后自动跟随）
    val draggingIndex = draggingKey?.let { k -> items.indexOfFirst { keyOf(it) == k } } ?: -1

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = if (contentBottomPadding != androidx.compose.ui.unit.Dp.Unspecified) {
            androidx.compose.foundation.layout.PaddingValues(bottom = contentBottomPadding)
        } else {
            androidx.compose.foundation.layout.PaddingValues()
        },
    ) {
        if (header != null) item(key = "__header__") { header() }

        itemsIndexed(items, key = { _, item -> keyOf(item) }) { index, item ->
            val thisKey = keyOf(item)
            val isDragging = draggingKey == thisKey
            val itemModifier = if (isDragging) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = dragOffset }
            } else {
                Modifier.zIndex(0f)
            }

            Box(
                modifier = itemModifier
                    // 关键：key 用 items.size 而非 thisKey，避免重排时 pointerInput 被销毁
                    .pointerInput(items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = thisKey
                                dragOffset = 0f
                                hasDragged = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val current = draggingIndex
                                if (current < 0) return@detectDragGesturesAfterLongPress
                                dragOffset += dragAmount.y
                                hasDragged = true

                                val info = listState.layoutInfo
                                val offsetItems = if (header != null) 1 else 0
                                val currentVisible = info.visibleItemsInfo
                                    .firstOrNull { it.index == current + offsetItems }
                                val height = currentVisible?.size?.toFloat() ?: 0f
                                val threshold = if (height > 0f) height / 2f else 80f

                                if (dragOffset > threshold && current < items.lastIndex) {
                                    onMove(current, current + 1)
                                    dragOffset -= if (height > 0f) height else threshold * 2
                                } else if (dragOffset < -threshold && current > 0) {
                                    onMove(current, current - 1)
                                    dragOffset += if (height > 0f) height else threshold * 2
                                }
                            },
                            onDragEnd = {
                                if (hasDragged) {
                                    onDragEnd(items.map(keyOf))
                                }
                                draggingKey = null
                                dragOffset = 0f
                                hasDragged = false
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffset = 0f
                                hasDragged = false
                            },
                        )
                    },
            ) {
                itemContent(index, item, isDragging)
            }
        }

        if (footer != null) item(key = "__footer__") { footer() }
    }
}
