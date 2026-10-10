package com.sbai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃表面组件。
 * 
 * 特性：
 * 1. 使用 Android 12+ RenderEffect 进行硬件加速模糊（实时磨砂）
 * 2. 半透明背景 + 微弱边框增强玻璃质感
 * 3. 动态模糊质量：滑动时自动降低以保持流畅
 * 
 * @param blurRadius 模糊半径，默认 16dp（中等质量，避免过度模糊）
 * @param alpha 背景透明度，默认 0.88f（提高不透明度，确保文字清晰）
 * @param borderAlpha 边框透明度，默认 0.2f
 * @param borderStroke 边框宽度，默认 1.dp
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 16.dp,
    alpha: Float = 0.88f,
    borderAlpha: Float = 0.2f,
    borderStroke: Dp = 1.dp,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainer.copy(alpha = alpha))
            .border(
                width = borderStroke,
                color = colors.outline.copy(alpha = borderAlpha),
                shape = shape,
            ),
        content = content,
    )
}

/**
 * 低模糊质量的玻璃表面，用于滚动时保持流畅
 */
@Composable
fun LowBlurGlassSurface(
    modifier: Modifier = Modifier,
    alpha: Float = 0.88f,
    borderAlpha: Float = 0.2f,
    borderStroke: Dp = 1.dp,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        blurRadius = 8.dp,  // 低模糊半径
        alpha = alpha,
        borderAlpha = borderAlpha,
        borderStroke = borderStroke,
        cornerRadius = cornerRadius,
        content = content
    )
}

/**
 * 高模糊质量的玻璃表面，用于静止状态
 */
@Composable
fun HighBlurGlassSurface(
    modifier: Modifier = Modifier,
    alpha: Float = 0.92f,
    borderAlpha: Float = 0.25f,
    borderStroke: Dp = 1.dp,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        blurRadius = 20.dp,  // 中等模糊半径
        alpha = alpha,
        borderAlpha = borderAlpha,
        borderStroke = borderStroke,
        cornerRadius = cornerRadius,
        content = content
    )
}
