package com.sbai.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.opacity
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * Kototoro 同款液态玻璃悬浮底栏（vendored kyant backdrop）。
 *
 * 用法：页面内容用 `Modifier.layerBackdrop(pageBackdrop)` 捕获，底栏用本组件采样并磨砂。
 * 与 haze 的区别：backdrop 是真·采样下方内容做折射/模糊（液态玻璃），
 * 带 highlight 顶光 + 柔和投影 + vibrancy，通透感更强。
 */
@Composable
fun SbGlassBottomBar(
    pageBackdrop: LayerBackdrop,
    items: List<SbNavItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        modifier = modifier
            .clip(CircleShape)
            .drawBackdrop(
                backdrop = pageBackdrop,
                shape = { CircleShape },
                effects = {
                    // 液态玻璃核心：模糊 + 提亮 + 微饱和（vibrancy）
                    blur(radius = 40f)
                    colorControls(brightness = 0.14f, saturation = 1.25f, contrast = 1.02f)
                    opacity(0.55f)
                },
                highlight = {
                    // 顶光描边（ClashFest lumen_hairline 效果），玻璃通透感关键
                    Highlight(width = 1.dp, alpha = 0.35f)
                },
                shadow = {
                    Shadow(radius = 20.dp, color = Color.Black.copy(alpha = 0.22f))
                },
            ),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(item.route) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        // Kototoro：无 indicator 背景，选中仅换强调色
                        indicatorColor = Color.Transparent,
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.onSurface,
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/** 底栏条目 */
data class SbNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)
