package com.example.voiceassistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.BuildConfig
import com.example.voiceassistant.data.JarvisSettings
import com.example.voiceassistant.state.AssistantUiState
import com.example.voiceassistant.tts.TtsEngineOption
import com.example.voiceassistant.tts.TtsVoiceOption
import com.example.voiceassistant.update.UpdateState
import com.example.voiceassistant.util.PermissionState

@Composable
fun HomeScreen(
    enabled: Boolean,
    permissions: List<PermissionState>,
    runtime: AssistantUiState,
    updateState: UpdateState,
    jarvis: JarvisSettings,
    onToggle: (Boolean) -> Unit,
    onOpenPermissionTab: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onToggleWake: (Boolean) -> Unit,
    onSaveLlm: (String, String, String) -> Unit,
    onTestLlm: (String, String, String) -> Unit,
    onPersistLlm: (String, String, String) -> Unit,
    probeText: String,
    probeOk: Boolean?,
    ttsVoices: List<TtsVoiceOption>,
    ttsEngines: List<TtsEngineOption>,
    onSaveTts: (String, String, Float, Float) -> Unit,
    onPreviewTts: (String) -> Unit,
    onToggleTtsMuted: (Boolean) -> Unit
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

        JarvisCard(
            jarvis = jarvis,
            onToggleWake = onToggleWake,
            onSaveLlm = onSaveLlm,
            onTestLlm = onTestLlm,
            onPersistLlm = onPersistLlm,
            probeText = probeText,
            probeOk = probeOk,
            ttsVoices = ttsVoices,
            ttsEngines = ttsEngines,
            onSaveTts = onSaveTts,
            onPreviewTts = onPreviewTts,
            onToggleTtsMuted = onToggleTtsMuted
        )

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
                    text = "· 说「贾维斯」唤醒后可直接聊天或下指令；点悬浮球则只处理单条指令\n" +
                        "· 识别 → 意图解析 → 无障碍执行 → 语音播报，全链路打通\n" +
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
private fun JarvisCard(
    jarvis: JarvisSettings,
    onToggleWake: (Boolean) -> Unit,
    onSaveLlm: (String, String, String) -> Unit,
    onTestLlm: (String, String, String) -> Unit,
    onPersistLlm: (String, String, String) -> Unit,
    probeText: String,
    probeOk: Boolean?,
    ttsVoices: List<TtsVoiceOption>,
    ttsEngines: List<TtsEngineOption>,
    onSaveTts: (String, String, Float, Float) -> Unit,
    onPreviewTts: (String) -> Unit,
    onToggleTtsMuted: (Boolean) -> Unit
) {
    var baseUrl by remember(jarvis.llmBaseUrl) { mutableStateOf(jarvis.llmBaseUrl) }
    var apiKey by remember(jarvis.llmApiKey) { mutableStateOf(jarvis.llmApiKey) }
    var model by remember(jarvis.llmModel) { mutableStateOf(jarvis.llmModel) }

    // 失焦即落盘：老版本只在点「保存」时才写，用户填完直接走开就会永远"未接"
    fun persistIfChanged() {
        val changed = baseUrl.trim() != jarvis.llmBaseUrl ||
            apiKey.trim() != jarvis.llmApiKey ||
            model.trim() != jarvis.llmModel
        if (changed && (baseUrl.isNotBlank() || model.isNotBlank())) {
            onPersistLlm(baseUrl, apiKey, model)
        }
    }

    // 已接 = 地址和模型都存下来了；已连通 = 真的试答成功过
    val statusText = when {
        !jarvis.llmConfigured -> "大脑未接"
        probeOk == true -> "大脑已连通"
        probeOk == false -> "连接失败"
        else -> "大脑已接"
    }
    val statusColor = when {
        !jarvis.llmConfigured || probeOk == false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "贾维斯",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("语音唤醒", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "说「贾维斯」直接唤醒；常驻监听会明显耗电",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = jarvis.wakeWordEnabled, onCheckedChange = onToggleWake)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("语音播报", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "关闭后只弹提示，不出声",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = !jarvis.ttsMuted, onCheckedChange = { onToggleTtsMuted(!it) })
            }

            VoiceSettings(
                jarvis = jarvis,
                voices = ttsVoices,
                engines = ttsEngines,
                onSaveTts = onSaveTts,
                onPreviewTts = onPreviewTts
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("模型地址") },
                placeholder = { Text("http://192.168.1.2:11434") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) persistIfChanged() }
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名（留空则自动选）") },
                placeholder = { Text("qwen3:1.7b") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) persistIfChanged() }
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key（局域网 Ollama 可留空）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) persistIfChanged() }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSaveLlm(baseUrl, apiKey, model) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存并测试")
                }
                OutlinedButton(
                    onClick = { onTestLlm(baseUrl, apiKey, model) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("只测试")
                }
            }

            if (probeText.isNotBlank()) {
                Text(
                    text = probeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (probeOk) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Text(
                text = "局域网 Ollama 填电脑 IP + 11434（电脑要开机、同一 WiFi）；" +
                    "连不上先点「只测试」看具体原因。" +
                    "云端填 https://api.deepseek.com 之类，模型名照服务商给的写。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 发音人 / 语速 / 音调。
 *
 * 安卓没有"猴哥"这种内置音色：能选什么完全取决于手机里装了哪个 TTS 引擎、
 * 该引擎报出哪些中文发音人。这里把它们全列出来，选完立刻能试听。
 */
@Composable
private fun VoiceSettings(
    jarvis: JarvisSettings,
    voices: List<TtsVoiceOption>,
    engines: List<TtsEngineOption>,
    onSaveTts: (String, String, Float, Float) -> Unit,
    onPreviewTts: (String) -> Unit
) {
    var pitch by remember(jarvis.ttsPitch) { mutableStateOf(jarvis.ttsPitch) }
    var rate by remember(jarvis.ttsRate) { mutableStateOf(jarvis.ttsRate) }
    var voiceMenu by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }

    val engineLabel = engines.firstOrNull { it.packageName == jarvis.ttsEngine }?.label
        ?: engines.firstOrNull { it.packageName.isEmpty() }?.label
        ?: "系统默认引擎"
    val voiceLabel = voices.firstOrNull { it.key == jarvis.ttsVoice }?.label ?: "引擎默认音色"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("发声引擎", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = engineLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                TextButton(onClick = { engineMenu = true }) { Text("换引擎") }
                DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("系统默认") },
                        onClick = {
                            engineMenu = false
                            onSaveTts("", "", pitch, rate)
                        }
                    )
                    engines.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                engineMenu = false
                                onSaveTts(option.packageName, "", pitch, rate)
                            }
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("发音人", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = voiceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onPreviewTts("俺老孙来也，师傅有何吩咐") }) { Text("试听") }
            Box {
                TextButton(onClick = { voiceMenu = true }) { Text("更换") }
                DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("引擎默认音色") },
                        onClick = {
                            voiceMenu = false
                            onSaveTts(jarvis.ttsEngine, "", pitch, rate)
                        }
                    )
                    voices.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                voiceMenu = false
                                onSaveTts(option.engine, option.key, pitch, rate)
                            }
                        )
                    }
                }
            }
        }

        Text(
            text = "语速 %.2fx".format(rate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = rate,
            onValueChange = { rate = it },
            valueRange = 0.5f..1.8f,
            onValueChangeFinished = {
                onSaveTts(jarvis.ttsEngine, jarvis.ttsVoice, pitch, rate)
                onPreviewTts("语速调到 ${"%.1f".format(rate)} 倍")
            }
        )

        Text(
            text = "音调 %.2fx（越低越沉、越高越尖）".format(pitch),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = pitch,
            onValueChange = { pitch = it },
            valueRange = 0.5f..1.8f,
            onValueChangeFinished = {
                onSaveTts(jarvis.ttsEngine, jarvis.ttsVoice, pitch, rate)
                onPreviewTts("音调调到 ${"%.1f".format(pitch)} 倍")
            }
        )

        if (voices.isEmpty()) {
            Text(
                text = "当前引擎没报出中文发音人。想要角色音色，可以装第三方 TTS（如 MultiTTS）" +
                    "导入音源后，这里会列出它的发音人。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    text = "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 把「这个包绑定的更新源」直接摊开：出问题时一眼能看出装的是哪个包，
            // 不必再靠猜（空串=本地构建没注入 updateRepo，检查更新必然失败）
            Text(
                text = BuildConfig.UPDATE_REPO.takeIf { it.isNotBlank() }
                    ?.let { "更新源：github.com/$it" }
                    ?: "更新源：未配置（这个包是没注入 updateRepo 的本地构建）",
                style = MaterialTheme.typography.labelSmall,
                color = if (BuildConfig.UPDATE_REPO.isBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

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
                        progress = { state.progress / 100f },
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
