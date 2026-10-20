package com.example.voiceassistant.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.voiceassistant.intent.Action
import com.example.voiceassistant.intent.SwipeDirection
import com.example.voiceassistant.intent.VolumeCommand
import com.example.voiceassistant.intent.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 无障碍执行器：把 [Action] 变成真实操作。
 *
 * 设计约束：
 *  - 每个动作都有超时（默认 4 秒），绝不无限等待
 *  - 失败一律返回可播报的中文原因，不抛异常给上层
 *  - 节点查找有数量上限，避免超大页面遍历卡死
 *  - 不打印节点树内容（隐私）
 */
class AccessibilityActionExecutor(
    private val context: Context,
    private val serviceProvider: () -> AccessibilityService?
) : ActionExecutor {

    override suspend fun execute(action: Action): ExecResult {
        // 打开应用要查包管理器；发消息要走完整个界面流程，两者都比单步动作慢得多
        val timeout = when (action) {
            is Action.OpenApp -> OPEN_APP_TIMEOUT_MS
            is Action.SendMessage -> SEND_MESSAGE_TIMEOUT_MS
            else -> TIMEOUT_MS
        }
        val startedAt = SystemClock.elapsedRealtime()

        val result = withTimeoutOrNull(timeout) {
            withContext(Dispatchers.Main) { dispatch(action) }
        }

        val cost = SystemClock.elapsedRealtime() - startedAt
        if (result == null) {
            Log.w(TAG, "timeout ${cost}ms: ${action.describe()}")
            return ExecResult.failure("操作超时，界面没有响应")
        }
        Log.i(TAG, "done ok=${result.ok} cost=${cost}ms: ${result.message}")
        return result
    }

    private suspend fun dispatch(action: Action): ExecResult = when (action) {
        is Action.OpenApp -> openApp(action.appName)
        is Action.Back -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "已返回上一页")
        is Action.Home -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "已回到桌面")
        is Action.ClickText -> clickText(action.text)
        is Action.ClickIndex -> clickIndex(action.index)
        is Action.Swipe -> swipe(action.direction, action.distanceRatio)
        is Action.InputText -> inputText(action.text)
        is Action.Volume -> volume(action.command)
        is Action.SpeakScreen -> speakScreen()
        is Action.Notify -> ExecResult.failure("提醒功能将在后续模块接入")
        is Action.SendMessage -> sendMessage(action)
        is Action.Confirm -> ExecResult.failure("确认动作应由上层处理，不能直接执行")
    }

    // ---------------- 打开应用 ----------------

    private suspend fun openApp(appName: String): ExecResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val target = appName.trim()
        if (target.isEmpty()) return@withContext ExecResult.failure("应用名为空")

        val startedAt = SystemClock.elapsedRealtime()
        val match = findApp(target)
            ?: return@withContext ExecResult.failure("没有找到应用「$target」")

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            ?: return@withContext ExecResult.failure("「${match.label}」没有可启动的界面")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        Log.i(
            TAG,
            "openApp target=$target -> ${match.label}(${match.packageName}), " +
                "lookup ${SystemClock.elapsedRealtime() - startedAt}ms"
        )

        runCatching { context.startActivity(launchIntent) }
            .fold(
                onSuccess = { ExecResult.success("已打开「${match.label}」") },
                onFailure = {
                    Log.e(TAG, "startActivity failed: ${match.packageName}", it)
                    ExecResult.failure("打开「${match.label}」失败：${it.message.orEmpty()}")
                }
            )
    }

    private fun launcherIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private data class AppMatch(val label: String, val packageName: String)

    /**
     * 按名字找已安装应用（只枚举桌面入口，几十个，避开 150+ 应用全量查 label 的 4 秒超时）。
     * 匹配优先级：完全相同 → 忽略大小写 → 前缀 → 包含（取最短标签，避免「微信」命中「微信读书」）。
     */
    private fun findApp(target: String): AppMatch? {
        val pm = context.packageManager
        val candidates = runCatching {
            pm.queryIntentActivities(launcherIntent(), 0)
        }.getOrNull() ?: return null

        val labelToPackage = LinkedHashMap<String, String>(candidates.size)
        val seenPackages = HashSet<String>(candidates.size)
        for (info in candidates) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seenPackages.add(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault("")
            if (label.isEmpty()) continue
            labelToPackage.putIfAbsent(label, pkg)
        }

        val matchedLabel = labelToPackage.keys.firstOrNull { it == target }
            ?: labelToPackage.keys.firstOrNull { it.equals(target, ignoreCase = true) }
            ?: labelToPackage.keys.firstOrNull { it.startsWith(target) }
            ?: labelToPackage.keys.filter { it.contains(target) }.minByOrNull { it.length }
            ?: return null

        return AppMatch(matchedLabel, labelToPackage.getValue(matchedLabel))
    }

    // ---------------- 发消息（复合动作） ----------------

    /**
     * 打开 App → 搜索联系人 → 进会话 → 输入 → 发送。
     *
     * 每一步失败都返回具体原因（而不是笼统的"失败"），因为这类自动化最怕的就是
     * "说了没反应"却不知道卡在哪一步；日志里也会记每一步耗时。
     */
    private suspend fun sendMessage(action: Action.SendMessage): ExecResult {
        val contact = action.contact.trim()
        val content = action.content.trim().take(500)
        if (contact.isEmpty()) return ExecResult.failure("没听清要发给谁")
        if (content.isEmpty()) return ExecResult.failure("没说发什么内容")

        val opened = openApp(action.appName)
        if (!opened.ok) return opened

        val match = withContext(Dispatchers.IO) { findApp(action.appName) }
        if (match != null && !awaitForeground(match.packageName, FOREGROUND_WAIT_MS)) {
            return ExecResult.failure("「${action.appName}」没有切到前台")
        }
        delay(STEP_DELAY_MS)

        // 1) 搜索框：优先找"搜索"入口（内容描述/文字）并点开
        if (!openSearchEntry()) {
            return ExecResult.failure("没找到「${action.appName}」的搜索入口")
        }
        delay(STEP_DELAY_MS)

        val searchBox = findFocusedEditable() ?: findEditable(preferBottom = false)
            ?: return ExecResult.failure("搜索框没有出现")
        if (!setText(searchBox, contact)) return ExecResult.failure("联系人名字输不进去")
        delay(SEARCH_DELAY_MS)

        // 2) 点搜索结果里最匹配的那一项
        val hit = findTextNode(contact) ?: return ExecResult.failure("搜索结果里没有「$contact」")
        val clicked = clickNodeOrAncestor(hit, contact)
        if (!clicked.ok) return clicked
        delay(CHAT_DELAY_MS)

        // 3) 聊天输入框（屏幕下半部分那个）
        val input = findEditable(preferBottom = true)
            ?: return ExecResult.failure("没找到聊天输入框")
        clickNode(input)
        delay(300)
        if (!setText(input, content)) return ExecResult.failure("消息内容输入失败")
        delay(STEP_DELAY_MS)

        // 4) 发送
        val send = findSendButton() ?: return ExecResult.failure("没找到发送按钮")
        val sent = clickNodeOrAncestor(send, "发送")
        if (!sent.ok) return sent
        delay(300)

        Log.i(TAG, "sendMessage ok: app=${action.appName} contact=$contact len=${content.length}")
        return ExecResult.success("已给「$contact」发送：$content")
    }

    /** 轮询当前前台包名，等目标 App 真的到前台（冷启动可能要几秒） */
    private suspend fun awaitForeground(packageName: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val pkg = runCatching { rootNode()?.packageName?.toString() }.getOrNull()
            if (pkg == packageName) return true
            delay(200)
        }
        return false
    }

    /** 点开搜索：找「搜索」文字/描述，或者屏幕上第一个可编辑框 */
    private suspend fun openSearchEntry(): Boolean {
        val root = rootNode() ?: return false
        val nodes = collectNodes(root)

        val searchNode = nodes.firstOrNull {
            val desc = it.contentDescription?.toString().orEmpty()
            val text = it.text?.toString().orEmpty()
            it.isVisibleToUser && (desc.contains("搜索") || text.contains("搜索"))
        }
        if (searchNode != null) {
            clickNodeOrAncestor(searchNode, "搜索")
            return true
        }

        val editable = findEditable(preferBottom = false)
        if (editable != null) {
            clickNode(editable)
            return true
        }
        return false
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        return runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
    }

    /** 可编辑框；preferBottom 选屏幕最下方那个（聊天输入框），否则选最上方（搜索框） */
    private fun findEditable(preferBottom: Boolean): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        val metrics = context.resources.displayMetrics
        val editable = collectNodes(root).filter { it.isEditable && it.isVisibleToUser }
        if (editable.isEmpty()) return null

        val withBounds = editable.mapNotNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) null else node to rect
        }
        if (withBounds.isEmpty()) return null

        return if (preferBottom) {
            // 下半屏优先，且取最靠下的
            withBounds.filter { it.second.top > metrics.heightPixels / 2 }
                .maxByOrNull { it.second.top }?.first
                ?: withBounds.maxByOrNull { it.second.top }?.first
        } else {
            withBounds.minByOrNull { it.second.top }?.first
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
        if (ok) return true
        // 少数输入框不认 SET_TEXT，用粘贴兜底
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("msg", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY())
    }

    /** 找联系人/结果项：优先精确同名，其次包含；排除搜索框本身 */
    private fun findTextNode(keyword: String): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        val nodes = collectNodes(root).filter { it.isVisibleToUser && !it.isEditable }
        return nodes.firstOrNull { it.text?.toString()?.trim() == keyword }
            ?: nodes.firstOrNull { it.text?.toString()?.contains(keyword) == true }
            ?: nodes.firstOrNull { it.contentDescription?.toString()?.contains(keyword) == true }
    }

    private fun findSendButton(): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        val nodes = collectNodes(root).filter { it.isVisibleToUser }
        return nodes.firstOrNull { it.text?.toString()?.trim() == "发送" }
            ?: nodes.firstOrNull { it.contentDescription?.toString()?.trim() == "发送" }
            ?: nodes.firstOrNull { it.text?.toString()?.contains("发送") == true }
    }

    // ---------------- 全局动作 ----------------

    private fun globalAction(actionId: Int, successMessage: String): ExecResult {
        val service = serviceProvider()
            ?: return ExecResult.failure(NEED_SERVICE)
        return if (service.performGlobalAction(actionId)) {
            ExecResult.success(successMessage)
        } else {
            ExecResult.failure("系统拒绝了该操作")
        }
    }

    // ---------------- 点击文字 ----------------

    private suspend fun clickText(target: String): ExecResult {
        val root = rootNode() ?: return ExecResult.failure(NEED_SERVICE)
        val keyword = target.trim()
        if (keyword.isEmpty()) return ExecResult.failure("没有指定要点击的文字")

        val nodes = collectNodes(root)

        // 多策略：精确文本 → 精确描述 → 包含文本 → 包含描述
        val exactText = nodes.firstOrNull { it.text?.toString()?.trim() == keyword }
        val exactDesc = nodes.firstOrNull { it.contentDescription?.toString()?.trim() == keyword }
        val fuzzyText = nodes.firstOrNull { it.text?.toString()?.contains(keyword) == true }
        val fuzzyDesc = nodes.firstOrNull { it.contentDescription?.toString()?.contains(keyword) == true }

        val targetNode = exactText ?: exactDesc ?: fuzzyText ?: fuzzyDesc
            ?: return ExecResult.failure("屏幕上没有找到「$keyword」")

        return clickNodeOrAncestor(targetNode, keyword)
    }

    private suspend fun clickNodeOrAncestor(node: AccessibilityNodeInfo, label: String): ExecResult {
        var cursor: AccessibilityNodeInfo? = node
        var depth = 0
        while (depth < MAX_PARENT_DEPTH) {
            val current = cursor ?: break
            if (current.isClickable) {
                val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return ExecResult.success("已点击「$label」")
                break
            }
            cursor = runCatching { current.parent }.getOrNull()
            depth++
        }

        // 兜底：按节点中心坐标做手势点击（适合自绘控件）
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return ExecResult.failure("「$label」不可点击")
        }
        val ok = dispatchTap(rect.exactCenterX(), rect.exactCenterY())
        return if (ok) ExecResult.success("已点击「$label」") else ExecResult.failure("「$label」点击失败")
    }

    private suspend fun clickIndex(index: Int): ExecResult {
        val root = rootNode() ?: return ExecResult.failure(NEED_SERVICE)
        val nodes = collectNodes(root)
        val clickables = nodes.filter { it.isClickable && it.isVisibleToUser }

        if (clickables.isEmpty()) return ExecResult.failure("当前界面没有可点击元素")
        if (index < 1 || index > clickables.size) {
            return ExecResult.failure("只有 ${clickables.size} 个可点击元素，没有第 $index 个")
        }

        val node = clickables[index - 1]
        val label = node.text?.toString()?.trim()
            ?: node.contentDescription?.toString()?.trim()
            ?: "第 $index 个元素"

        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ExecResult.success("已点击第 $index 个「$label」")
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val ok = dispatchTap(rect.exactCenterX(), rect.exactCenterY())
        return if (ok) ExecResult.success("已点击第 $index 个「$label」")
        else ExecResult.failure("第 $index 个元素点击失败")
    }

    // ---------------- 滑动 ----------------

    private suspend fun swipe(direction: SwipeDirection, ratio: Float): ExecResult {
        val service = serviceProvider() ?: return ExecResult.failure(NEED_SERVICE)
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val safeRatio = ratio.coerceIn(0.1f, 1.0f)

        val path = Path()
        when (direction) {
            SwipeDirection.UP -> {
                val span = height * safeRatio / 2f
                path.moveTo(centerX, centerY + span)
                path.lineTo(centerX, centerY - span)
            }

            SwipeDirection.DOWN -> {
                val span = height * safeRatio / 2f
                path.moveTo(centerX, centerY - span)
                path.lineTo(centerX, centerY + span)
            }

            SwipeDirection.LEFT -> {
                val span = width * safeRatio / 2f
                path.moveTo(centerX + span, centerY)
                path.lineTo(centerX - span, centerY)
            }

            SwipeDirection.RIGHT -> {
                val span = width * safeRatio / 2f
                path.moveTo(centerX - span, centerY)
                path.lineTo(centerX + span, centerY)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()

        val ok = dispatchGesture(service, gesture)
        return if (ok) ExecResult.success("已向${direction.label}滑动")
        else ExecResult.failure("滑动失败，可能被系统手势拦截")
    }

    // ---------------- 输入文字 ----------------

    private fun inputText(text: String): ExecResult {
        val root = rootNode() ?: return ExecResult.failure(NEED_SERVICE)
        if (text.isEmpty()) return ExecResult.failure("没有要输入的内容")

        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?: findFirstEditable(root)
            ?: return ExecResult.failure("没有找到输入框，请先点一下输入区域")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) ExecResult.success("已输入「$text」")
        else ExecResult.failure("输入失败，该输入框可能不支持直接赋值")
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findFirstEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    // ---------------- 音量 ----------------

    private fun volume(command: VolumeCommand): ExecResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ExecResult.failure("拿不到音频服务")

        return when (command) {
            VolumeCommand.UP -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                ExecResult.success("音量已增大")
            }

            VolumeCommand.DOWN -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                ExecResult.success("音量已减小")
            }

            VolumeCommand.MUTE -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                ExecResult.success("已静音")
            }

            VolumeCommand.UNMUTE -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                ExecResult.success("已取消静音")
            }
        }
    }

    // ---------------- 读屏 ----------------

    private fun speakScreen(): ExecResult {
        val root = rootNode() ?: return ExecResult.failure(NEED_SERVICE)
        val texts = LinkedHashSet<String>()
        collectTexts(root, texts, 0)

        if (texts.isEmpty()) return ExecResult.failure("当前界面没有可读文字")

        val content = texts.joinToString("，").take(MAX_SPEAK_LENGTH)
        return ExecResult.success(content)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_TEXT_NODES) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }

        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectTexts(child, out, depth + 1)
        }
    }

    // ---------------- 屏幕快照（多步执行用） ----------------

    /**
     * 给多步 agent 看的"当前屏幕"，一句话一句话拼成模型能读的纯文本。
     *
     * 关键约束：**可点击元素的序号必须与 [clickIndex] 完全同序**，
     * 否则模型点第 3 个，实际点了别的，比报错更糟。两处都用
     * `collectNodes(root).filter { isClickable && isVisibleToUser }`，顺序一致。
     *
     * 隐私：只给可见文字，不给包内结构、不给账号类长串；总长度封顶。
     */
    fun snapshotForAgent(): String {
        val root = runCatching { rootNode() }.getOrNull() ?: return ""
        val nodes = runCatching { collectNodes(root) }.getOrNull().orEmpty()
        if (nodes.isEmpty()) return ""

        val builder = StringBuilder(1024)

        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (packageName.isNotBlank()) {
            builder.append("前台应用：").append(packageName).append('\n')
        }

        // 文字：去掉空串和重复项，太长就截断（模型不需要整页文案）
        val texts = LinkedHashSet<String>()
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it.take(40)) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { texts.add(it.take(40)) }
            if (texts.size >= MAX_SNAPSHOT_TEXTS) break
        }
        builder.append("屏幕文字：")
        builder.append(if (texts.isEmpty()) "（无）" else texts.joinToString(" | "))
        builder.append('\n')

        val clickables = nodes.filter { it.isClickable && it.isVisibleToUser }
        builder.append("可点击元素（序号可直接用于 click_index）：\n")
        if (clickables.isEmpty()) {
            builder.append("（无）\n")
        } else {
            // 与 clickIndex 同序，所以只截取前 N 个，序号依然对应
            clickables.take(MAX_SNAPSHOT_NODES).forEachIndexed { i, node ->
                val label = (node.text?.toString()?.trim().orEmpty().ifEmpty {
                    node.contentDescription?.toString()?.trim().orEmpty()
                }).take(24)
                builder.append(i + 1).append(". ")
                if (node.isEditable) builder.append("[输入框] ")
                builder.append(label.ifEmpty { "(无文字)" }).append('\n')
            }
        }

        return builder.toString().take(MAX_SNAPSHOT_CHARS)
    }

    // ---------------- 基础设施 ----------------

    private fun service(): AccessibilityService? = serviceProvider()

    private fun rootNode(): AccessibilityNodeInfo? = runCatching {
        serviceProvider()?.rootInActiveWindow
    }.getOrNull()

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(64)
        collectNodesRecursive(root, result, 0)
        return result
    }

    private fun collectNodesRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_TREE_DEPTH || out.size >= MAX_NODES) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectNodesRecursive(child, out, depth + 1)
        }
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val service = serviceProvider() ?: return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(service, gesture)
    }

    private suspend fun dispatchGesture(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
        val accepted = runCatching { service.dispatchGesture(gesture, callback, null) }
            .getOrDefault(false)
        if (!accepted && continuation.isActive) {
            continuation.resume(false)
        }
    }

    companion object {
        private const val TAG = "VoiceExec"
        private const val TIMEOUT_MS = 4_000L
        private const val OPEN_APP_TIMEOUT_MS = 10_000L

        /** 发消息要经过"启动→搜索→进会话→输入→发送"，给足 30 秒（微信冷启动很慢） */
        private const val SEND_MESSAGE_TIMEOUT_MS = 30_000L
        private const val FOREGROUND_WAIT_MS = 10_000L
        private const val STEP_DELAY_MS = 500L
        private const val SEARCH_DELAY_MS = 1_200L
        private const val CHAT_DELAY_MS = 1_200L
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 300L
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODES = 1_500
        private const val MAX_TEXT_NODES = 200
        private const val MAX_SPEAK_LENGTH = 800
        private const val MAX_PARENT_DEPTH = 6

        /** 屏幕快照的体量上限：模型上下文和手机算力都吃不下整页 */
        private const val MAX_SNAPSHOT_TEXTS = 40
        private const val MAX_SNAPSHOT_NODES = 30
        private const val MAX_SNAPSHOT_CHARS = 1_600

        private const val NEED_SERVICE = "无障碍服务未连接，请在系统设置里开启"
    }
}
