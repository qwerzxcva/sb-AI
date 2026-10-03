package com.sbai.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * 长按拖动排序的 LazyColumn（越靠上优先级越高）。
 *
 * 实现要点（修复索引过期问题）：
 *  - 以「key」而非「index」跟踪被拖项，重排后实时用 indexOfFirst 求当前索引，
 *    避免 pointerInput 闭包捕获过期 index。
 *  - 用 hasDragged 标记，仅真正拖过才在松手时提交顺序（首次组合/取消不触发落盘）。
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
                    .pointerInput(thisKey) {
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
                                // 取消不提交，仅复位
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
