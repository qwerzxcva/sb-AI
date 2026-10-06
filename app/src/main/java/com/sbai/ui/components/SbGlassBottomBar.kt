package com.sbai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.opacity
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃底栏。紧凑宽度/大字体使用带完整无障碍语义的纯图标布局，
 * 不缩小系统字体或点击区域；极窄分屏允许横向滚动而非挤压五个入口。
 */
@Composable
fun SbGlassBottomBar(
    pageBackdrop: LayerBackdrop,
    items: List<SbNavItem>,
    useGlassBackdrop: Boolean = false,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 80.dp,
) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val resolvedHeight = barHeight.coerceAtLeast(48.dp)
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .then(
                if (useGlassBackdrop) {
                    Modifier.drawBackdrop(
                        backdrop = pageBackdrop,
                        shape = { CircleShape },
                        effects = {
                            blur(radius = 4f)
                            colorControls(brightness = 0.1f, saturation = 1.15f, contrast = 1.0f)
                            opacity(0.5f)
                        },
                        highlight = { Highlight(width = 0.5.dp, alpha = 0.25f) },
                        shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.18f)) },
                    )
                } else {
                    Modifier.background(colors.surfaceContainer.copy(alpha = 0.96f))
                }
            ),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(resolvedHeight)) {
            val itemWidth = (maxWidth / items.size.coerceAtLeast(1)).coerceAtLeast(48.dp)
            // 用实际分配宽度决策；扩展标签单行省略，完整名称保留在 Tab 语义中。
            val showLabels = resolvedHeight >= 72.dp &&
                itemWidth >= 64.dp && fontScale <= 1.3f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .height(resolvedHeight)
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(item.route) },
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = item.label
                            }
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = if (selected) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        if (showLabels) {
                            Text(
                                text = item.label,
                                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 底栏条目；label 同时用于可见文字和无障碍名称。 */
data class SbNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)
