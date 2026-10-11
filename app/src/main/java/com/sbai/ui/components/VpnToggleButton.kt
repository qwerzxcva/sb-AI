package com.sbai.ui.components

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sbai.service.SbAiVpnService
import com.sbai.service.SbCommandClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FlClash 风格 VPN 电源开关按钮：独立圆形按钮，悬浮在底栏旁。
 *
 * 状态真源 = CommandClient 是否连上 :core 进程的 CommandServer（coreConnected），
 * 与 HomeScreen 的 toggleVpn 完全一致——:core 进程的 SbAiVpnService.status
 * 是进程内 StateFlow 不跨进程共享，UI 侧读到的恒为 Stopped。
 *
 * 点击：未连接 → 请求 VPN 权限 → 启动；已连接 → 停止。
 */
@Composable
fun VpnToggleButton(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 60.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coreConnected by SbCommandClient.connectedToService.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpn(context)
            scope.launch(Dispatchers.IO) { SbCommandClient.connectWithRetry() }
        }
    }

    fun toggle() {
        if (coreConnected) {
            stopVpn(context)
            SbCommandClient.disconnect()
            return
        }
        val intent = VpnService.prepare(context)
        if (intent != null) {
            permissionLauncher.launch(intent)
        } else {
            startVpn(context)
            scope.launch(Dispatchers.IO) { SbCommandClient.connectWithRetry() }
        }
    }

    // FlClash 观感：运行中 = 强调色圆盘；停止 = 灰底圆盘 + 电源图标
    Surface(
        onClick = { toggle() },
        shape = CircleShape,
        color = if (coreConnected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .size(size)
            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.25f))
            .clip(CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = if (coreConnected) "停止 VPN" else "启动 VPN",
                tint = if (coreConnected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

private fun startVpn(context: Context) {
    val intent = Intent(context, SbAiVpnService::class.java)
        .setAction(SbAiVpnService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopVpn(context: Context) {
    val intent = Intent(context, SbAiVpnService::class.java)
        .setAction(SbAiVpnService.ACTION_STOP)
    context.startService(intent)
}
