package app.listentogether.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.android.ListenApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun AudioOutputButton(controller: AppController, modifier: Modifier) {
    val context = LocalContext.current
    val output = (context.applicationContext as ListenApp).output
    val state by output.state.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { output.showError(e.message ?: "连接失败，请稍后重试") }
        }
    }
    Box(modifier) {
        RoundIcon(onClick = { open = true; output.scan() }, active = state.device != null) {
            Icon(
                if (state.device != null) Icons.Rounded.Speaker else Icons.Rounded.Cast,
                contentDescription = state.label,
                tint = if (state.device != null) Palette.Accent else Palette.Text,
                modifier = Modifier.size(20.dp),
            )
        }
        if (state.error != null && state.device != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(7.dp)
                    .clip(CircleShape).background(Palette.Accent),
            )
        }
    }
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = { if (!state.connecting) open = false },
        containerColor = Palette.Surface,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier.padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Palette.Outline),
            )
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("播放设备", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val rescanEnabled = !state.scanning && !state.connecting
                Row(
                    Modifier.clip(CircleShape).background(Palette.SurfaceHigh)
                        .alpha(if (rescanEnabled) 1f else 0.5f)
                        .clickable(enabled = rescanEnabled, onClick = output::scan)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(15.dp), tint = Palette.Muted)
                    Text("重新查找", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                }
            }
            Text("音箱与手机需连接同一 Wi-Fi。投放时使用宽松同步，不调整倍速。", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            if (state.device != null && !state.seekControlAvailable) Text("此音箱不支持进度同步，会从歌曲开头播放。", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            if (state.device != null && !state.volumeControlAvailable) Text("音量请用音箱按键调整；App 静音会暂停音箱播放。", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            DeviceOption(
                name = "本机 / 蓝牙",
                icon = Icons.Rounded.PhoneAndroid,
                selected = state.device == null,
                enabled = !state.connecting,
            ) {
                act { output.usePhone(); controller.refreshPlayback(); open = false }
            }
            Text("DLNA 音箱", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))
            if (state.scanning || state.connecting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Accent)
                    Text(if (state.connecting) "正在连接…" else "正在查找…", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            state.devices.forEach { device ->
                DeviceOption(
                    name = device.name,
                    icon = Icons.Rounded.Speaker,
                    selected = state.device?.id == device.id,
                    enabled = !state.connecting,
                ) {
                    act { output.select(device); controller.refreshPlayback(); open = false }
                }
            }
            if (!state.scanning && state.devices.isEmpty()) {
                Text(
                    "还没找到设备，请确认音箱已开启 DLNA，且 Wi-Fi 没有隔离设备。",
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.SurfaceHigh)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            state.error?.let {
                Text(
                    it, color = Palette.Accent, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.AccentSoft)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 设备选项：样式对齐 SyncModeOption，左侧单选圆点 + 设备图标 + 名称。 */
@Composable
private fun DeviceOption(name: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, label = "border")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) Palette.Accent else Palette.Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) Palette.Accent else Palette.Muted)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
