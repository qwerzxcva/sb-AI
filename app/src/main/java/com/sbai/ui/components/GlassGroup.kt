package com.sbai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃风格的分组容器。
 * 
 * 特性：
 * 1. 使用 GlassSurface 作为背景，呈现磨砂玻璃效果
 * 2. 标题在顶部，内容在下方
 * 3. 支持自定义内边距和间距
 */
@Composable
fun GlassGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 12.dp),
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            content()
        }
    }
}

/**
 * 玻璃分组容器内的项目
 */
@Composable
fun GlassGroupItem(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        content()
    }
}
