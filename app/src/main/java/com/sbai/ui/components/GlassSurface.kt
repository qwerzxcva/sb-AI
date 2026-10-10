package com.sbai.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.render.AndroidRenderEffect
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
 * @param blurRadius 模糊半径，默认 20dp（高质量）
 * @param alpha 背景透明度，默认 0.65f
 * @param borderAlpha 边框透明度，默认 0.3f
 * @param borderStroke 边框宽度，默认 1.dp
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 20.dp,
    alpha: Float = 0.65f,
    borderAlpha: Float = 0.3f,
    borderStroke: Dp = 1.dp,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { blurRadius.toPx() }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // 使用硬件加速的 RenderEffect 进行实时模糊
                    Modifier
                        .background(colors.surfaceContainer.copy(alpha = alpha))
                        .border(
                            width = borderStroke,
                            color = colors.outline.copy(alpha = borderAlpha),
                            shape = RoundedCornerShape(cornerRadius)
                        )
                        .graphicsLayer {
                            renderEffect = AndroidRenderEffect
                                .createBlurEffect(
                                    radiusX = blurRadiusPx,
                                    radiusY = blurRadiusPx,
                                    edgeTreatment = AndroidRenderEffect.EdgeTreatment.CLAMP,
                                )
                                .asComposeRenderEffect()
                        }
                } else {
                    // Android 12 以下使用静态半透明作为降级
                    Modifier
                        .background(colors.surfaceContainer.copy(alpha = alpha + 0.15f))
                        .border(
                            width = borderStroke,
                            color = colors.outline.copy(alpha = borderAlpha + 0.1f),
                            shape = RoundedCornerShape(cornerRadius)
                        )
                }
            ),
        content = content
    )
}

/**
 * 低模糊质量的玻璃表面，用于滚动时保持流畅
 */
@Composable
fun LowBlurGlassSurface(
    modifier: Modifier = Modifier,
    alpha: Float = 0.65f,
    borderAlpha: Float = 0.3f,
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
    alpha: Float = 0.7f,
    borderAlpha: Float = 0.35f,
    borderStroke: Dp = 1.dp,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        blurRadius = 25.dp,  // 高模糊半径
        alpha = alpha,
        borderAlpha = borderAlpha,
        borderStroke = borderStroke,
        cornerRadius = cornerRadius,
        content = content
    )
}
