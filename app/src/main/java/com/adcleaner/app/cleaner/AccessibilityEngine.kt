package com.adcleaner.app.cleaner

import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.adcleaner.app.model.BrandRule
import com.adcleaner.app.model.IntentSpec
import com.adcleaner.app.model.Section
import com.adcleaner.app.model.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 无障碍引擎：读 UI 树、找文字/开关、点击、翻页、回读、处理确认框。
 * 对照 rddmwmddr/android-ad-cleaner 的 tool/fast.py 能力实现，但运行在本机无障碍服务里，
 * 不依赖 adb / uiautomator2。
 */
class AccessibilityEngine(private val service: AdCleanerService) {

    /**
     * 取当前前台应用的根节点。
     * vivo/OriginOS 上 [service.rootInActiveWindow] 偶发返回宿主 App 窗口（无障碍服务所在进程），
     * 导致读不到真正前台应用的 UI（搜索框、导航项全"没找到"）。优先从 [service.windows]
     * 挑聚焦/激活的应用窗口（按 layer 取最上层兜底），再退回 rootInActiveWindow。
     */
    private fun root(): AccessibilityNodeInfo? {
        val wins = try { service.windows } catch (e: Exception) { null }
        if (wins != null) {
            val target = wins.firstOrNull { it.isActive || it.isFocused }
                ?: wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .maxByOrNull { it.layer }
            if (target != null) {
                val r = try { target.root } catch (e: Exception) { null }
                if (r != null) return r
            }
        }
        return try { service.rootInActiveWindow } catch (e: Exception) { null }
    }

    /** 诊断：列出当前窗口（类型/layer/聚焦/标题），用于"没找到"时定位 root() 读错窗口。 */
    private fun diagWindows(): String {
        val wins = try { service.windows } catch (e: Exception) { null } ?: return "windows=null"
        return wins.joinToString(";") { w ->
            val title = try { w.title?.toString() } catch (e: Exception) { null }
            "t${w.type}L${w.layer}${if (w.isActive) "A" else ""}${if (w.isFocused) "F" else ""}:${title ?: "?"}"
        }
    }

    // ---------------- 节点遍历 ----------------
    private fun collectAll(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>(128)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out.add(n)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                stack.addLast(c)
            }
        }
        return out
    }

    private fun nodeText(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    private fun isSwitch(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString().orEmpty()
        return n.isCheckable || cls.contains("Switch", true) ||
            cls.contains("CheckBox", true) || cls.contains("Toggle", true)
    }

    private data class SwitchInfo(val node: AccessibilityNodeInfo, val title: String, val checked: Boolean)

    /** 开关行标题：取父节点子树（两层）的所有非空文字，排除开关自身。 */
    private fun titleOf(n: AccessibilityNodeInfo): String {
        // vivo 等系统：开关标题在 contentDescription 里（text 为空），如「个性化广告.会基于...」
        val own = n.contentDescription?.toString()?.trim().orEmpty()
        if (own.isNotEmpty()) return own.substringBefore(".").trim()
        val parts = ArrayList<String>()
        val parent = n.parent ?: return nodeText(n)
        collectTexts(parent, parts, 0, 2, n)
        var title = parts.joinToString(" ").trim()
        if (title.isEmpty()) {
            var p: AccessibilityNodeInfo? = parent
            var d = 0
            while (p != null && d < 4) {
                val t = nodeText(p)
                if (t.isNotEmpty()) { title = t; break }
                p = p.parent; d++
            }
        }
        return title
    }

    private fun collectTexts(
        n: AccessibilityNodeInfo, out: ArrayList<String>,
        depth: Int, maxDepth: Int, exclude: AccessibilityNodeInfo
    ) {
        if (depth > maxDepth) return
        if (n !== exclude) {
            val t = nodeText(n)
            if (t.isNotEmpty()) out.add(t)
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collectTexts(c, out, depth + 1, maxDepth, exclude)
        }
    }

    private fun findSwitches(): List<SwitchInfo> {
        val r = root() ?: return emptyList()
        return collectAll(r).filter { isSwitch(it) }.map { SwitchInfo(it, titleOf(it), it.isChecked) }
    }

    private fun findTextNodes(text: String, exact: Boolean): List<AccessibilityNodeInfo> {
        val r = root() ?: return emptyList()
        return collectAll(r).filter {
            val t = nodeText(it)
            if (exact) t == text else t.contains(text)
        }
    }

    // ---------------- 点击 ----------------
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var d = 0
        while (n != null && d < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent; d++
        }
        val r = Rect(); node.getBoundsInScreen(r)
        return clickPoint(r.exactCenterX(), r.exactCenterY())
    }

    private fun clickPoint(x: Float, y: Float, durationMs: Long = 70): Boolean {
        return try {
            val path = Path(); path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gd = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gd, null, null)
        } catch (e: Exception) { false }
    }

    // ---------------- 翻页 ----------------
    private fun scrollForward(): Boolean {
        val r = root() ?: return false
        for (n in collectAll(r)) {
            if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        }
        return false
    }

    private fun swipeUp(durationMs: Long = 380): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val h = dm.heightPixels
        return try {
            val path = Path()
            path.moveTo(cx, h * 0.78f)
            path.lineTo(cx, h * 0.30f)
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) { false }
    }

    private suspend fun scrollDownOnce(): Boolean {
        if (scrollForward()) { delay(300); return true }
        swipeUp(); delay(420); return true
    }

    private fun scrollBackward(): Boolean {
        val r = root() ?: return false
        for (n in collectAll(r)) {
            if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        }
        return false
    }

    private fun swipeDown(durationMs: Long = 380): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val h = dm.heightPixels
        return try {
            val path = Path()
            path.moveTo(cx, h * 0.30f)
            path.lineTo(cx, h * 0.78f)
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) { false }
    }

    private suspend fun scrollUpOnce(): Boolean {
        if (scrollBackward()) { delay(300); return true }
        swipeDown(); delay(420); return true
    }

    // ---------------- 确认框 ----------------
    private val CONFIRM_WORDS = listOf("确定", "确认", "知道了", "仍要关闭", "确定关闭", "关闭", "好的", "仍要", "继续")
    private val CANCEL_WORDS = listOf("取消", "以后再说", "不允许", "禁止", "暂不", "不了", "放弃", "返回")

    private suspend fun confirmIfAny(): Boolean {
        val r = root() ?: return false
        val nodes = collectAll(r)
        for (w in CONFIRM_WORDS) {
            val hit = nodes.firstOrNull { nodeText(it) == w && it.isClickable }
                ?: nodes.firstOrNull {
                    nodeText(it).contains(w) && it.isClickable &&
                        CANCEL_WORDS.none { c -> nodeText(it).contains(c) }
                } ?: continue
            if (hit.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNode(hit)) {
                delay(500); return true
            }
        }
        return false
    }

    // ---------------- 等待 ----------------
    private suspend fun waitStable(ms: Long = 700, tries: Int = 4): AccessibilityNodeInfo? {
        repeat(tries) {
            val r = root()
            if (r != null && r.childCount > 0) return r
            delay(ms)
        }
        return root()
    }

    // ---------------- 打开页面 ----------------
    suspend fun launchIntent(spec: IntentSpec?) {
        if (spec == null) return
        val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (spec.flags ?: 0))
        spec.action?.let { intent.action = it }
        spec.data?.let { intent.data = Uri.parse(it) }
        spec.extras.forEach { (k, v) -> intent.putExtra(k, v) }
        spec.component?.let {
            // 必须用 unflattenFromString：它把 "com.vivo.browser/.BrowserActivity" 的前导点归一化为
            // "com.vivo.browser.BrowserActivity"。直接 ComponentName(pkg, ".cls") 不剥点，
            // 会报 "Unable to find explicit activity class"——这是之前所有 App 启动失败的真凶。
            val c = ComponentName.unflattenFromString(it)
            if (c != null) intent.component = c
        }
        try {
            service.startActivity(intent)
            CleanState.step("打开页面：${spec.action ?: spec.component ?: spec.data ?: "设置"}")
        } catch (e: Exception) {
            // 组件类名机型差异（如 vivo 商店 .MainEntryActivity 不存在）→ 兜底按包名启动默认界面
            val pkg = spec.component?.substringBefore("/")
            if (pkg != null) {
                try {
                    val fb = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(pkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(fb)
                    CleanState.step("打开页面：$pkg（按包名兜底）")
                } catch (e2: Exception) {
                    CleanState.warn("打开页面失败：${e.message}")
                }
            } else {
                CleanState.warn("打开页面失败：${e.message}")
            }
        }
        delay(1200)
    }

    // ---------------- 搜索直达 ----------------
    /** 设置搜索直达：点搜索入口 → 输入目标 → 点结果。各品牌设置页顶部都有搜索框，通用。 */
    private suspend fun searchAndOpen(query: String, waitMs: Long = 900): Boolean {
        waitStable()
        // 1. 找输入框（搜索框可能直接就是输入框，无需先点入口）
        var r = root() ?: return false
        var edit = findInputField(r)
        // 1.1 重试：设置页搜索框可能还在加载
        var tries = 0
        while (edit == null && tries < 8) {
            delay(600)
            r = root() ?: break
            edit = findInputField(r)
            tries++
        }
        // 2. 没有就直接是输入框 → 点搜索入口（优先 EditText），再找输入框
        if (edit == null) {
            val entry = collectAll(r).firstOrNull { n ->
                n.className?.toString()?.contains("EditText", true) == true
            } ?: collectAll(r).firstOrNull { n ->
                val t = nodeText(n)
                val cd = n.contentDescription?.toString().orEmpty()
                val cls = n.className?.toString().orEmpty()
                ((t.contains("搜索") || t.contains("Search") || cd.contains("搜索")) && n.isClickable) ||
                    cls.contains("Search", true)
            }
            if (entry == null) { CleanState.warn("没找到搜索入口"); return false }
            clickNode(entry)
            delay(1200)
            // 2.1 重试：搜索界面可能还在加载
            tries = 0
            while (edit == null && tries < 5) {
                r = root() ?: break
                edit = findInputField(r)
                if (edit == null) delay(500)
                tries++
            }
        }
        if (edit == null) { CleanState.warn("没找到搜索输入框（窗口:${diagWindows()}）"); return false }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        delay(1500)
        // 3. 点搜索结果（含目标名的可点击项）；排除搜索框本身（EditText，text 就是目标名）
        val results = findTextNodes(query, false).filter {
            it.className?.toString()?.contains("EditText", true) != true
        }
        val target = results.firstOrNull { it.isClickable } ?: results.firstOrNull()
        if (target != null) {
            clickNode(target)
            delay(waitMs); waitStable()
            return true
        }
        CleanState.warn("搜索「$query」没结果")
        return false
    }

    /** 找可输入文字的字段：EditText，或带「搜索」提示的输入框/搜索条。 */
    private fun findInputField(r: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return collectAll(r).firstOrNull { n ->
            n.className?.toString()?.contains("EditText", true) == true
        } ?: collectAll(r).firstOrNull { n ->
            val t = nodeText(n)
            val cd = n.contentDescription?.toString().orEmpty()
            val cls = n.className?.toString().orEmpty()
            val hint = if (android.os.Build.VERSION.SDK_INT >= 26) {
                n.hintText?.toString().orEmpty()
            } else ""
            (t.contains("搜索") || t.contains("Search") || cd.contains("搜索") || hint.contains("搜索")) &&
                (n.isEditable || cls.contains("SearchBar", true) || cls.contains("SearchView", true))
        }
    }

    // ---------------- 执行单步 ----------------
    suspend fun executeStep(s: Step): Boolean {
        when (s.action) {
            "openSettings", "launchApp" -> launchIntent(s.intent)
            "wait" -> delay(s.ms)
            "back" -> {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(s.ms)
            }
            "scrollDown" -> repeat(s.scrolls.coerceAtLeast(1)) { scrollDownOnce() }
            "confirm" -> confirmIfAny()
            "tap", "tapExact" -> {
                waitStable()
                val candidates = s.texts.ifEmpty { listOfNotNull(s.text) }
                for (txt in candidates) {
                    val nodes = findTextNodes(txt, s.exact)
                    val target = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()
                    if (target != null && clickNode(target)) { delay(s.ms); return true }
                }
                CleanState.warn("没找到入口「${candidates.joinToString("/")}」")
                return false
            }
            "tapIcon" -> {
                val r = waitStable() ?: return false
                val dm = service.resources.displayMetrics
                val hit = collectAll(r).firstOrNull { n ->
                    val cls = n.className?.toString().orEmpty()
                    val rb = Rect(); n.getBoundsInScreen(rb)
                    (n.isClickable || cls.contains("ImageView", true)) &&
                        rb.exactCenterX() > dm.widthPixels * 0.6f &&
                        rb.top < dm.heightPixels * 0.25f &&
                        nodeText(n).isEmpty()
                }
                if (hit != null) { clickNode(hit); delay(s.ms) }
                else CleanState.warn("没找到右上齿轮图标")
            }
            "tapIconLast" -> {
                // 右上角往往有多个无名图标（如应用商店「我的」页：客服 + 设置），
                // tapIcon 点的是第一个（客服），设置通常在最右一个——按右边界取最右。
                val r = waitStable() ?: return false
                val dm = service.resources.displayMetrics
                val hits = collectAll(r).filter { n ->
                    val cls = n.className?.toString().orEmpty()
                    val rb = Rect(); n.getBoundsInScreen(rb)
                    (n.isClickable || cls.contains("ImageView", true)) &&
                        rb.exactCenterX() > dm.widthPixels * 0.6f &&
                        rb.top < dm.heightPixels * 0.25f &&
                        nodeText(n).isEmpty()
                }
                val hit = hits.maxByOrNull { run { val rb = Rect(); it.getBoundsInScreen(rb); rb.right } }
                if (hit != null) { clickNode(hit); delay(s.ms) }
                else CleanState.warn("没找到右上角图标")
            }
            "tapTab" -> {
                val txt = s.text ?: return true
                val r = waitStable() ?: return false
                val hit = collectAll(r).firstOrNull { nodeText(it).contains(txt) && it.isClickable }
                if (hit != null) { clickNode(hit); delay(s.ms) }
                else CleanState.warn("没找到标签「$txt」")
            }
            "goInto" -> {
                waitStable()
                val candidates = s.texts.ifEmpty { listOfNotNull(s.text) }
                for (txt in candidates) {
                    val nodes = findTextNodes(txt, s.exact)
                    val target = nodes.firstOrNull { it.isClickable }
                        ?: nodes.firstOrNull { it.parent?.isClickable == true }
                    if (target != null) {
                        val click = target.takeIf { it.isClickable } ?: target.parent
                        if (click != null && clickNode(click)) {
                            delay(s.ms); waitStable(); return true
                        }
                    }
                }
                CleanState.warn("没找到入口「${candidates.joinToString("/")}」")
                return false
            }
            "search" -> {
                val q = s.text ?: return true
                searchAndOpen(q, s.ms)
            }
            "switchOff" -> {
                val closed = switchOff(s.targets, s.protectedKeywords, s.scrolls)
                CleanState.step("关掉 $closed 个开关")
                CleanState.addClosed(closed)
            }
            "switchOnly" -> {
                val closed = switchOnly(s.targets, s.scrolls)
                CleanState.step("关掉 $closed 个目标开关")
                CleanState.addClosed(closed)
            }
            "switchOn" -> {
                val opened = switchOn(s.targets, s.scrolls)
                CleanState.step("打开 $opened 个保护开关")
            }
            "readBack" -> {
                waitStable()
                val stillOn = findSwitches().count { it.checked && s.targets.any { t -> it.title.contains(t) } }
                if (stillOn > 0) CleanState.warn("回读：仍有 $stillOn 个开着")
                else CleanState.ok("回读确认：目标已关")
            }
            "skipIfMissing" -> {
                val txt = s.text ?: return true
                if (findTextNodes(txt, s.exact).isEmpty()) {
                    CleanState.step("未找到「$txt」，跳过本段")
                    return false
                }
            }
            else -> CleanState.warn("未知动作：${s.action}")
        }
        return true
    }

    /** 关掉标题含 targets 的开关，protected 词保留；翻 scrolls 屏。targets 空=本页全关。 */
    suspend fun switchOff(targets: List<String>, protected: List<String>, scrolls: Int): Int {
        var closed = 0
        for (page in 0..scrolls) {
            val switches = findSwitches()
            var acted = false
            for (sw in switches) {
                if (!sw.checked) continue
                if (protected.any { sw.title.contains(it) }) continue
                if (targets.isNotEmpty() && targets.none { sw.title.contains(it) }) continue
                if (clickNode(sw.node)) { delay(750); acted = true; closed++; confirmIfAny() }
            }
            if (page < scrolls) scrollDownOnce()
            delay(280)
        }
        return closed
    }

    suspend fun switchOnly(targets: List<String>, scrolls: Int): Int {
        var closed = 0
        for (page in 0..scrolls) {
            for (sw in findSwitches()) {
                if (!sw.checked) continue
                if (targets.none { sw.title.contains(it) }) continue
                if (clickNode(sw.node)) { delay(750); closed++; confirmIfAny() }
            }
            if (page < scrolls) scrollDownOnce()
            delay(280)
        }
        return closed
    }

    /** 打开保护类开关（限制广告跟踪等）：当前关着且标题含 targets 的，点开。 */
    suspend fun switchOn(targets: List<String>, scrolls: Int): Int {
        var opened = 0
        for (page in 0..scrolls) {
            for (sw in findSwitches()) {
                if (sw.checked) continue
                if (targets.none { sw.title.contains(it) }) continue
                if (clickNode(sw.node)) { delay(750); opened++; confirmIfAny() }
            }
            if (page < scrolls) scrollDownOnce()
            delay(280)
        }
        return opened
    }

    // ---------------- 无线调试自动配对导航 ----------------

    /** 读当前屏幕上的配对码对话框（设置页在前台时 root 即对话框内容）。 */
    fun readPairingCodeOnScreen(): Pair<String, String>? {
        val codeRegex = Regex("^\\d{6}$")
        val addrRegex = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}):(\\d+)$")
        val r = root() ?: return null
        var code: String? = null
        var addr: String? = null
        for (n in collectAll(r)) {
            val s = nodeText(n)
            if (code == null && codeRegex.matches(s)) code = s
            if (addr == null && addrRegex.matches(s)) addr = s
        }
        val c = code ?: return null
        val a = addr ?: return null
        return c to a
    }

    /** 配对导航专用手势滚动：down=true 看后面的内容。真实手势，与手动滑动一致。 */
    private fun gestureScroll(down: Boolean): Boolean = gestureScroll(down, 350, 0.78f, 0.28f)

    /**
     * 手势滚动（可调速度/距离）。
     * 慢速短距 = 无惯性甩动，一步约 1/3 屏，逐屏精确搜索不跳行；
     * 快速长距 = 带惯性，用于快速回顶。
     */
    private fun gestureScroll(down: Boolean, durationMs: Long, from: Float, to: Float): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val h = dm.heightPixels
        return try {
            val path = Path()
            if (down) { path.moveTo(cx, h * from); path.lineTo(cx, h * to) }
            else { path.moveTo(cx, h * to); path.lineTo(cx, h * from) }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) { false }
    }

    /** 真实手势点击节点中心（vivo 设置行对 performAction 有时不响应，手势与人手一致）。 */
    private fun gestureTapNode(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return clickPoint(r.exactCenterX(), r.exactCenterY())
    }

    /**
     * 自动导航到「使用配对码配对设备」对话框并读出 (配对码, ip:端口)。
     * 状态机：看到配对码框→读；看到配对码入口→点；主列表看到无线调试行→点；
     * 都没有→滚动。设置页保留旧滚动位置，所以先滚回顶部。
     */
    suspend fun openPairingDialog(scrolls: Int = 12): Pair<String, String>? {
        // 先回顶（手势上滑 22 次，覆盖最长页面），再进入状态机
        repeat(22) { gestureScroll(false); delay(560) }
        delay(900)
        var page = 0
        while (page <= scrolls + 4) {
            waitStable(700, 4)
            readPairingCodeOnScreen()?.let { return it }
            val hasEntry = findTextNodes("使用配对码配对设备", exact = false).isNotEmpty()
            val onSubPage = findTextNodes("设备名称", exact = false).isNotEmpty()
            val hasWireless = findTextNodes("无线调试", exact = false).isNotEmpty()
            android.util.Log.i("AdbPair", "nav page=$page entry=$hasEntry sub=$onSubPage wireless=$hasWireless")
            if (hasEntry) {
                for (n in findTextNodes("使用配对码配对设备", exact = false)) {
                    Log.i("AdbNav", "tap 使用配对码配对设备 -> ${gestureTapNode(n)}")
                    break
                }
                delay(1500)
            } else if (onSubPage) {
                // 在子页：滚动到配对码入口可见
                gestureScroll(true, 700, 0.55f, 0.35f); delay(900)
            } else if (hasWireless) {
                for (n in findTextNodes("无线调试", exact = false)) {
                    Log.i("AdbNav", "tap 无线调试 -> ${gestureTapNode(n)}")
                    break
                }
                delay(1500)
            } else if (page < scrolls) {
                gestureScroll(true, 900, 0.60f, 0.32f); delay(1000)
            } else {
                break
            }
            page++
        }
        // 诊断：引擎到底看到了哪个窗口、哪些文本
        val r = root()
        val sample = collectAll(r).take(60).mapNotNull { n -> nodeText(n).ifEmpty { null } }
        android.util.Log.i("AdbPair", "nav失败 diag: root=${r?.packageName} wins=${diagWindows()} texts=$sample")
        return null
    }

    // ---------------- 段 / 品牌 ----------------
    data class SectionResult(val ok: Boolean = false, val skipped: Boolean = false)

    suspend fun executeSection(sec: Section): SectionResult {
        for (s in sec.steps) {
            try {
                val cont = executeStep(s)
                if (!cont && s.action == "skipIfMissing") return SectionResult(skipped = true)
            } catch (e: CancellationException) {
                // 服务断开/取消：绝不吞，让任务干净停下（避免把剩余步骤全标成"Job was cancelled"）
                throw e
            } catch (e: Exception) {
                CleanState.fail("步骤出错：${e.message}")
            }
        }
        return SectionResult(ok = true)
    }

    suspend fun executeBrand(rule: BrandRule, includeAdb: Boolean, startSection: Int = 0) {
        val sections = rule.sections.filter { includeAdb || !it.requiresAdb }
        val total = sections.size
        if (startSection == 0) {
            CleanState.log("开始清理：${rule.displayName}，共 $total 段" +
                if (!includeAdb) "（增强模式未开，挂起广告包段已跳过）" else "")
        } else {
            CleanState.step("检测到服务中断，从第 ${startSection + 1} 段继续…")
        }
        sections.forEachIndexed { i, sec ->
            if (i < startSection) return@forEachIndexed // 断点前的段已完成，跳过
            CleanState.section(sec.name, i + 1, total)
            CleanState.step("路径：${sec.guidePath}")
            sec.pitfalls.forEach { CleanState.warn("坑：$it") }
            val res = executeSection(sec)
            if (res.skipped) CleanState.warn("本段跳过（未找到入口）")
            else CleanState.ok("「${sec.name}」完成")
        }
        CleanState.log("\n清理结束。请回桌面检查效果；个别 App 需重启后才生效。")
        CleanState.done()
    }
}
