package com.sbai.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 底栏可见性共享控制器（跨组件真源）。
 *
 * 修复 bug：进入二级页面（如添加 DNS 服务器）时上滑会隐藏底栏，
 * 但二级页面关闭后底栏状态没有恢复，导致列表页唤不出底栏。
 * 现在二级页面在 onDismiss/onBack 时显式调用 show() 恢复。
 */
object BottomBarController {
    private val _visible = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = _visible

    fun show() {
        _visible.value = true
    }

    fun hide() {
        _visible.value = false
    }
}

/**
 * 二级页面挂载时调用：编辑器激活期间隐藏底栏，页面销毁（关闭/返回）时恢复。
 *
 * 修复 bug：进入二级页面（如添加 DNS 服务器）时，底栏仍被 MainActivity 渲染在顶层，
 * 编辑器内上滑会触发 nestedScroll 隐藏底栏；保存返回列表页后底栏状态未恢复。
 * 现在：编辑器激活即隐藏底栏（编辑器是整页，本就不该有底栏），销毁时恢复。
 */
@androidx.compose.runtime.Composable
fun RestoreBottomBarOnDispose() {
    androidx.compose.runtime.DisposableEffect(Unit) {
        BottomBarController.hide()
        onDispose { BottomBarController.show() }
    }
}
