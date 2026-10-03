package com.sbai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sbai.ui.theme.LocalSbStyleTokens

// ---------------------------------------------------------------------------
// Kototoro 风格分组容器：组内条目 2dp 间距，首/尾条目大圆角，中间小圆角
// ---------------------------------------------------------------------------

enum class SbGroupItemPosition { SINGLE, FIRST, MIDDLE, LAST }

private fun groupItemShape(position: SbGroupItemPosition, outer: androidx.compose.ui.unit.Dp, inner: androidx.compose.ui.unit.Dp): Shape =
    when (position) {
        SbGroupItemPosition.SINGLE -> RoundedCornerShape(outer)
        SbGroupItemPosition.FIRST -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        SbGroupItemPosition.MIDDLE -> RoundedCornerShape(inner)
        SbGroupItemPosition.LAST -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    }

class SbGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) { items += content }
}

/** 分节标签 + 分组卡片列表（Kototoro SettingsPreferenceGroup 的移植） */
@Composable
fun SbGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: SbGroupScope.() -> Unit,
) {
    val scope = SbGroupScope().apply(content)
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val tokens = LocalSbStyleTokens.current
        Column(verticalArrangement = Arrangement.spacedBy(tokens.settingsItemGap)) {
            scope.items.forEachIndexed { index, itemContent ->
                val position = when {
                    scope.items.size == 1 -> SbGroupItemPosition.SINGLE
                    index == 0 -> SbGroupItemPosition.FIRST
                    index == scope.items.lastIndex -> SbGroupItemPosition.LAST
                    else -> SbGroupItemPosition.MIDDLE
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = groupItemShape(position, tokens.settingsGroupOuterCornerRadius, tokens.settingsGroupInnerCornerRadius),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    itemContent()
                }
            }
        }
    }
}

/** 设置条目行：图标容器（40dp 圆形）+ 标题/副标题 + 尾部控件 */
@Composable
fun SbItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalSbStyleTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = tokens.settingsItemMinHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(tokens.settingsItemIconContainerSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SbSwitchItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SbItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/** 可折叠分组（Kototoro SettingsCollapsiblePreferenceGroup 的移植） */
@Composable
fun SbCollapsibleGroup(
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: SbGroupScope.() -> Unit,
) {
    val scope = SbGroupScope().apply(content)
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val tokens = LocalSbStyleTokens.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = tokens.settingsItemMinHeight)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (summary != null) {
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.fillMaxWidth()) {
                    scope.items.forEach { itemContent ->
                        itemContent()
                    }
                }
            }
        }
    }
}

/** 状态徽章 */
@Composable
fun SbBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun SbSpacer() {
    Spacer(Modifier.height(LocalSbStyleTokens.current.sectionVerticalSpacing))
}
