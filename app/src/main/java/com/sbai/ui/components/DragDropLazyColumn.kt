package com.sbai.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * 实现：记录被拖动项的 index 与累计位移；拖动过程中用 graphicsLayer 平移该项，
 * 当位移超过相邻项高度的一半时交换数据源顺序（回调 onMove），并把累计位移
 * 减去被交换项的高度，保证视觉连续。松手时提交最终顺序。
 *
 * @param items 数据源（顺序即优先级）
 * @param keyOf 稳定 key
 * @param onMove 拖动过程中交换 (from, to)
 * @param onDragEnd 松手，参数为最终顺序的 key 列表
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
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 当前拖动项的可视高度（用于判定交换阈值）
    var draggingHeight by remember { mutableFloatStateOf(0f) }

    // 松手 / 列表变化时提交顺序
    LaunchedEffect(draggingIndex) {
        if (draggingIndex == null) {
            onDragEnd(items.map(keyOf))
        }
    }

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
            val isDragging = draggingIndex == index
            val itemModifier = if (isDragging) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = dragOffset }
            } else {
                Modifier.zIndex(0f)
            }

            Box(
                modifier = itemModifier
                    .pointerInput(items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val current = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                dragOffset += dragAmount.y

                                // 用布局信息推算相邻项高度（首次拖动时缓存）
                                val info = listState.layoutInfo
                                val currentVisible = info.visibleItemsInfo
                                    .firstOrNull { it.index == current + (if (header != null) 1 else 0) }
                                if (currentVisible != null) {
                                    draggingHeight = currentVisible.size.toFloat()
                                }
                                val threshold = if (draggingHeight > 0f) draggingHeight / 2f else 80f

                                // 向下越过阈值 → 与下一项交换
                                if (dragOffset > threshold && current < items.lastIndex) {
                                    onMove(current, current + 1)
                                    dragOffset -= if (draggingHeight > 0f) draggingHeight else threshold * 2
                                    draggingIndex = current + 1
                                } else if (dragOffset < -threshold && current > 0) {
                                    // 向上越过阈值 → 与上一项交换
                                    onMove(current, current - 1)
                                    dragOffset += if (draggingHeight > 0f) draggingHeight else threshold * 2
                                    draggingIndex = current - 1
                                }
                            },
                            onDragEnd = {
                                draggingIndex = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggingIndex = null
                                dragOffset = 0f
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
