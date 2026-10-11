package com.sbai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbai.ui.theme.LocalSbStyleTokens

/**
 * 悬浮玻璃底栏占用的高度（栏高 ~80dp + 下边距 12dp + 手势条余量）。
 * 所有可滚动页面必须用它作为底部 contentPadding，否则最后几项会被底栏挡住。
 */
val BottomBarClearance = 132.dp

/** FAB 需要额外抬高的距离，使其落在悬浮玻璃底栏上方而不重叠。 */
val FabBottomBarClearance = BottomBarClearance - 16.dp

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
    internal val items = mutableListOf<Pair<Any?, @Composable () -> Unit>>()
    fun item(content: @Composable () -> Unit) { items += null to content }
    fun item(key: Any?, content: @Composable () -> Unit) { items += key to content }
}

/**
 * 选择卡片：标题 + 说明，选中用主色容器。负载均衡模式卡的样式，供全站点选复用。
 */
@Composable
fun SbChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.75f) else colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 液态玻璃选择卡片：标题 + 说明，选中用主色容器。使用玻璃磨砂效果。
 */
@Composable
fun GlassChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val backgroundColor = if (selected) {
        colors.primaryContainer.copy(alpha = 0.92f)
    } else {
        colors.surfaceContainer.copy(alpha = 0.90f)
    }
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = if (selected) colors.primary.copy(alpha = 0.5f) else colors.outline.copy(alpha = 0.2f),
                shape = shape,
            )
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.75f) else colors.onSurfaceVariant,
            )
        }
    }
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
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val tokens = LocalSbStyleTokens.current
        Column(verticalArrangement = Arrangement.spacedBy(tokens.settingsItemGap)) {
            scope.items.forEachIndexed { index, (itemKey, itemContent) ->
                val position = when {
                    scope.items.size == 1 -> SbGroupItemPosition.SINGLE
                    index == 0 -> SbGroupItemPosition.FIRST
                    index == scope.items.lastIndex -> SbGroupItemPosition.LAST
                    else -> SbGroupItemPosition.MIDDLE
                }
                // 用 key 包裹：节点列表等大量 item 时，未变化的 item 跳过重组（回首页卡顿主因之一）
                val actualKey = itemKey ?: index
                androidx.compose.runtime.key(actualKey) {
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
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 带计数徽章的条目（Kototoro 液态玻璃增强） */
@Composable
fun SbItemWithBadge(
    title: String,
    badge: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalSbStyleTokens.current
    Row(
        modifier = Modifier
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    // 修复双击 bug：Switch 的 onCheckedChange 置 null（仅展示），整行 onClick 统一处理 toggle，
    // 避免「点开关时行点击和开关回调各触发一次、互相抵消」导致开关弹回/看似无效。
    SbItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = true) },
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
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (summary != null) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    scope.items.forEach { (itemKey, itemContent) ->
                        androidx.compose.runtime.key(itemKey) { itemContent() }
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

/**
 * 液态玻璃分组容器：使用玻璃磨砂效果。
 */
@Composable
fun GlassGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: SbGroupScope.() -> Unit,
) {
    val scope = SbGroupScope().apply(content)
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val tokens = LocalSbStyleTokens.current
        
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = tokens.settingsGroupOuterCornerRadius,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(tokens.settingsItemGap),
            ) {
                scope.items.forEachIndexed { index, (itemKey, itemContent) ->
                    val actualKey = itemKey ?: index
                    androidx.compose.runtime.key(actualKey) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            itemContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SbSpacer() {
    Spacer(Modifier.height(LocalSbStyleTokens.current.sectionVerticalSpacing))
}
