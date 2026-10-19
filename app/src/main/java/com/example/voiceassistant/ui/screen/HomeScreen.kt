package com.example.voiceassistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.BuildConfig
import com.example.voiceassistant.state.AssistantUiState
import com.example.voiceassistant.update.UpdateState
import com.example.voiceassistant.util.PermissionState

@Composable
fun HomeScreen(
    enabled: Boolean,
    permissions: List<PermissionState>,
    runtime: AssistantUiState,
    updateState: UpdateState,
    onToggle: (Boolean) -> Unit,
    onOpenPermissionTab: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    val missing = permissions.filter { !it.granted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "语音助手",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME} · 识别 → 解析 → 执行 已打通，危险动作需确认",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (enabled) "助手运行中" else "助手已关闭",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (enabled) {
                            "悬浮球已就位，点一下试试录音"
                        } else {
                            "打开开关后启动常驻服务与悬浮球"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }

        VoiceStatusCard(runtime)

        UpdateCard(
            state = updateState,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate
        )

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("权限状态", style = MaterialTheme.typography.titleMedium)

                permissions.forEach { state ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.kind.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (state.granted) "已就绪" else "待开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.granted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }

                if (missing.isNotEmpty()) {
                    Button(onClick = onOpenPermissionTab, modifier = Modifier.fillMaxWidth()) {
                        Text("去完成 ${missing.size} 项授权")
                    }
                }
            }
        }

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前阶段说明", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "· 点悬浮球开始连续听，说完一句自动接着听下一句\n" +
                        "· 识别 → 意图解析 → 无障碍执行 全链路打通，结果写进「日志」页\n" +
                        "· 涉及支付/删除/发送等敏感操作会先弹确认卡片，15 秒未作答自动取消\n" +
                        "· 卡片弹出时直接说「确认」或「取消」同样有效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VoiceStatusCard(runtime: AssistantUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "语音",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = runtime.statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (runtime.recording) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            when {
                runtime.partialText.isNotBlank() -> Text(
                    text = runtime.partialText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                runtime.lastText.isNotBlank() -> Text(
                    text = "最近识别：${runtime.lastText}",
                    style = MaterialTheme.typography.bodyMedium
                )

                else -> Text(
                    text = "点悬浮球说话，识别结果会出现在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!runtime.overlayVisible) {
                Text(
                    text = "悬浮球未显示，请检查悬浮窗权限",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "版本更新",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "当前 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (state) {
                UpdateState.Idle -> Text(
                    text = "点「检查更新」从 GitHub Release 拉取最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在检查…", style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.UpToDate -> Text(
                    text = "已是最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                is UpdateState.Available -> {
                    Text(
                        text = "发现新版本 ${state.info.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.info.notes.isNotBlank()) {
                        Text(
                            text = state.info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("下载更新（${formatSize(state.info.size)}）")
                    }
                }

                is UpdateState.Downloading -> {
                    Text(
                        // 服务端不给 Content-Length 时进度恒为 0，别显示成「0% 卡住」
                        text = if (state.progress > 0) "下载中 ${state.progress}%" else "下载中…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = state.progress / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is UpdateState.Ready -> {
                    Text(
                        text = "已下载 ${state.info.versionName}，安装时由系统完成覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text("安装更新")
                    }
                }

                is UpdateState.Failed -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 检查中/下载中/待安装时不再给「检查更新」，避免把下载结果冲掉
            if (state !is UpdateState.Checking &&
                state !is UpdateState.Downloading &&
                state !is UpdateState.Ready
            ) {
                OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                    Text("检查更新")
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
