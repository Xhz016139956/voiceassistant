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
        // 打开应用要查包管理器，耗时比其它动作高一个数量级，单独给更宽的超时
        val timeout = if (action is Action.OpenApp) OPEN_APP_TIMEOUT_MS else TIMEOUT_MS
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
        is Action.Confirm -> ExecResult.failure("确认动作应由上层处理，不能直接执行")
    }

    // ---------------- 打开应用 ----------------

    private suspend fun openApp(appName: String): ExecResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val target = appName.trim()
        if (target.isEmpty()) return@withContext ExecResult.failure("应用名为空")

        val startedAt = SystemClock.elapsedRealtime()

        // 只枚举带桌面入口的应用（几十个）。之前用 getInstalledApplications 逐个查 label，
        // 这台机器上有 150+ 应用，光查询就能吃掉 4 秒超时，用户看到的就是「说了没反应」。
        val candidates = runCatching {
            pm.queryIntentActivities(launcherIntent(), 0)
        }.getOrElse { return@withContext ExecResult.failure("读取应用列表失败") }

        val labelToPackage = LinkedHashMap<String, String>(candidates.size)
        val seenPackages = HashSet<String>(candidates.size)
        for (info in candidates) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seenPackages.add(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault("")
            if (label.isEmpty()) continue
            labelToPackage.putIfAbsent(label, pkg)
        }

        // 匹配优先级：完全相同 → 忽略大小写 → 前缀 → 包含（取最短的标签，避免「微信」命中「微信读书」）
        val matchedLabel = labelToPackage.keys.firstOrNull { it == target }
            ?: labelToPackage.keys.firstOrNull { it.equals(target, ignoreCase = true) }
            ?: labelToPackage.keys.firstOrNull { it.startsWith(target) }
            ?: labelToPackage.keys.filter { it.contains(target) }.minByOrNull { it.length }

        if (matchedLabel == null) {
            Log.w(TAG, "openApp miss target=$target, ${labelToPackage.size} launchers in ${SystemClock.elapsedRealtime() - startedAt}ms")
            return@withContext ExecResult.failure("没有找到应用「$target」")
        }

        val packageName = labelToPackage.getValue(matchedLabel)
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return@withContext ExecResult.failure("「$matchedLabel」没有可启动的界面")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        Log.i(
            TAG,
            "openApp target=$target -> $matchedLabel($packageName), lookup ${SystemClock.elapsedRealtime() - startedAt}ms"
        )

        runCatching { context.startActivity(launchIntent) }
            .fold(
                onSuccess = { ExecResult.success("已打开「$matchedLabel」") },
                onFailure = {
                    Log.e(TAG, "startActivity failed: $packageName", it)
                    ExecResult.failure("打开「$matchedLabel」失败：${it.message.orEmpty()}")
                }
            )
    }

    private fun launcherIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

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
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 300L
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODES = 1_500
        private const val MAX_TEXT_NODES = 200
        private const val MAX_SPEAK_LENGTH = 800
        private const val MAX_PARENT_DEPTH = 6

        private const val NEED_SERVICE = "无障碍服务未连接，请在系统设置里开启"
    }
}
