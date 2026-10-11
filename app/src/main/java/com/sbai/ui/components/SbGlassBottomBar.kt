package com.sbai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * 半透明玻璃底栏。内容保持锐利，玻璃感来自透明度、边框和高光。
 */
@Composable
fun SbGlassBottomBar(
    items: List<SbNavItem>,
    useGlassBackdrop: Boolean = true,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 80.dp,
) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val resolvedHeight = barHeight.coerceAtLeast(48.dp)

    Box(
        modifier = modifier
            .clip(CircleShape)
            .height(resolvedHeight)
            .background(
                colors.surfaceContainer.copy(alpha = if (useGlassBackdrop) 0.72f else 0.96f),
                CircleShape,
            )
            .border(
                width = 1.dp,
                color = colors.onSurface.copy(alpha = if (useGlassBackdrop) 0.16f else 0.08f),
                shape = CircleShape,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val itemWidth = (maxWidth / items.size.coerceAtLeast(1)).coerceAtLeast(48.dp)
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
