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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface

/**
 * Kototoro 同款液态玻璃悬浮底栏。
 *
 * 这里不再用 backdrop 采样折射：pageBackdrop 会把整个 NavHost 录制成
 * GraphicsLayer，首页节点列表每次重组/滚动都触发一次全屏离屏录制 + blur，
 * 实测是首页卡顿的主要来源。改用高透明度 Surface + 阴影达到相近的悬浮观感。
 */
@Composable
fun SbGlassBottomBar(
    items: List<SbNavItem>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clip(CircleShape),
        shape = CircleShape,
        color = colors.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
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
                    // 底栏只保留图标，名称只作为无障碍 contentDescription，
                    // 不占视觉宽度（五个入口时文字会被压扁或省略）。
                    label = null,
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
@Immutable
data class SbNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)
