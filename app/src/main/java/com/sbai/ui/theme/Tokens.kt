package com.sbai.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 界面几何 Token（参考 Kototoro 的 InterfaceStyleTokens，Material 3 Expressive 变体）。
 * 所有间距/圆角/控件高度统一从这里取，不散落在页面里。
 */
data class SbStyleTokens(
    val screenHorizontalPadding: Dp = 20.dp,
    val sectionVerticalSpacing: Dp = 20.dp,
    val groupCornerRadius: Dp = 28.dp,
    val sectionCornerRadius: Dp = 12.dp,
    val settingsGroupOuterCornerRadius: Dp = 24.dp,
    val settingsGroupInnerCornerRadius: Dp = 4.dp,
    val settingsItemGap: Dp = 2.dp,
    val settingsItemMinHeight: Dp = 56.dp,
    val settingsItemIconContainerSize: Dp = 40.dp,
    val controlCornerRadius: Dp = 20.dp,
    val controlHeight: Dp = 50.dp,
    val minimumTouchTarget: Dp = 48.dp,
    val dialogCornerRadius: Dp = 28.dp,
    val sheetCornerRadius: Dp = 36.dp,
)

val LocalSbStyleTokens = staticCompositionLocalOf { SbStyleTokens() }
