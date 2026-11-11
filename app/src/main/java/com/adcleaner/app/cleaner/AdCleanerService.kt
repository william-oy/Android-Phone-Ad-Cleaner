package com.adcleaner.app.cleaner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.adcleaner.app.model.BrandRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍服务：app 的「手和眼」。主界面通过 CleanOrchestrator 调 runRule，
 * 引擎在本服务进程里读屏、点开关、翻页。
 *
 * 可靠性：vivo/OriginOS 会在清理运行中**临时断开**无障碍服务（onUnbind）。
 * 这里把断点记在 companion（跨实例存活），服务重连时**自动续跑**，最多 3 次，
 * 避免系统频繁断开导致清理永远跑不完。
 */
class AdCleanerService : AccessibilityService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: AccessibilityEngine? = null
    private var job: Job? = null

    // 配对流程语音播报（老人点完按钮不用盯屏幕，听声音即可）
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    companion object {
        @Volatile
        var instance: AdCleanerService? = null
            private set

        // —— 断点续跑状态（companion：服务实例被重建也能存活）——
        @Volatile
        private var pendingRule: BrandRule? = null
        @Volatile
        private var pendingResumeFrom: Int = 0
        @Volatile
        private var pendingIncludeAdb: Boolean = false
        @Volatile
        private var pendingInterrupted: Boolean = false
        @Volatile
        private var resumeAttempts: Int = 0

        private const val MAX_RESUME = 3

        fun isEnabled(context: android.content.Context): Boolean {
            val s = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            // 兼容两种存储格式：完整类名（com.adcleaner.app/com.adcleaner.app.cleaner.AdCleanerService）
            // 和缩写（com.adcleaner.app/.cleaner.AdCleanerService）
            return s.contains("com.adcleaner.app") && s.contains("AdCleanerService")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 服务可能复用同一实例（scope 已被 onUnbind 取消），必须重建 scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = AccessibilityEngine(this)
        initTts()
        CleanState.log("无障碍服务已就绪，可以开始清理。")
        // 若清理被系统中断过，自动续跑
        val rule = pendingRule
        if (pendingInterrupted && rule != null && resumeAttempts < MAX_RESUME) {
            resumeAttempts++
            pendingInterrupted = false
            val from = pendingResumeFrom
            val inc = pendingIncludeAdb
            CleanState.log("服务已重连，自动继续清理…")
            startJob(rule, inc, from, reset = false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 主动读屏，不依赖事件流
    }

    override fun onInterrupt() {}

    private fun initTts() {
        tts = android.speech.tts.TextToSpeech(applicationContext) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = java.util.Locale.SIMPLIFIED_CHINESE
        }
    }

    /** 与主界面共用同一语音开关（adcleaner_prefs / voice_on）。 */
    private fun speak(text: String) {
        val on = getSharedPreferences("adcleaner_prefs", MODE_PRIVATE).getBoolean("voice_on", true)
        if (!on || !ttsReady) return
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "pair_$text")
    }

    /**
     * 从当前屏幕读取无线调试「配对码对话框」的内容：
     * 返回 (6位配对码, ip:配对端口)。对话框不在屏幕上时返回 null。
     * 老人因此不用手抄任何数字——点一下自动配对即可。
     */
    fun readPairingCodeAndPort(): Pair<String, String>? {
        val roots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (w in windows) {
                w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {}
        var code: String? = null
        var addr: String? = null
        val codeRegex = Regex("^\\d{6}$")
        val addrRegex = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}):(\\d+)$")
        val seen = HashSet<android.view.accessibility.AccessibilityNodeInfo>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || !seen.add(node)) return
            val t = node.text?.toString()?.trim().orEmpty()
            val cd = node.contentDescription?.toString()?.trim().orEmpty()
            for (s in listOf(t, cd)) {
                if (code == null && codeRegex.matches(s)) code = s
                if (addr == null && addrRegex.matches(s)) addr = s
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        roots.forEach { walk(it) }
        val c = code ?: return null
        val a = addr ?: return null
        return c to a
    }

    /**
     * 一键自动配对流（后台执行，界面订阅 [com.adcleaner.app.adb.AdbPairState.status]）：
     * 无障碍引擎驱动设置页打开配对码对话框 → 读码 → SPAKE2 配对 → NSD 发现端口 → TLS 连接。
     * 调用前调用方需已把开发者选项页拉起（app 在前台时启动 Activity 才不受限制）。
     */
    fun startAutoPairFlow() {
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                // 授权弹窗确认器：TLS 握手期间设备会弹「允许调试」，自动点允许
                val watchJob = launch {
                    repeat(45) {
                        kotlinx.coroutines.delay(700)
                        try {
                            val ok = engine?.tapUsbAuthDialogIfAny() ?: false
                            if (ok) android.util.Log.i("AdbPair", "授权弹窗已确认")
                        } catch (_: Exception) {}
                    }
                }
                try {
                    autoPairInner()
                } finally {
                    watchJob.cancel()
                }
            } ?: run {
                android.util.Log.w("AdbPair", "自动配对整体超时（120s）")
                com.adcleaner.app.adb.AdbPairState.status.value = "自动配对超时，请重试"
                com.adcleaner.app.adb.AdbPairState.done.value = false
                speak("配对超时了，请再试一次")
            }
        }
    }

    private suspend fun autoPairInner() {
        fun fail(msg: String) {
            com.adcleaner.app.adb.AdbPairState.status.value = msg
            com.adcleaner.app.adb.AdbPairState.done.value = false
            CleanState.log("  ✗ $msg")
        }
        try {
            val eng = engine
            if (eng == null) {
                fail("无障碍服务未就绪")
                return
            }
            com.adcleaner.app.adb.AdbPairState.status.value = "正在打开配对码页…"
            CleanState.log("\n── 无线调试自动配对 ──")
            android.util.Log.i("AdbPair", "flow: 开始自动配对")
            val info = eng.openPairingDialog(8)
            if (info == null) {
                android.util.Log.i("AdbPair", "flow: openPairingDialog 失败（没找到配对码页）")
                fail("没找到配对码页：请手动打开「使用配对码配对设备」后重试")
                return
            }
            val (code, addr) = info
            android.util.Log.i("AdbPair", "flow: 读到配对码 $code addr=$addr")
            CleanState.step("读取到配对码 $code（$addr）")
            com.adcleaner.app.adb.AdbPairState.status.value = "正在配对（配对码 $code）…"

            val host = addr.substringBefore(":")
            val pairPort = addr.substringAfter(":").toIntOrNull() ?: 0
            if (pairPort <= 0) {
                fail("配对端口异常：$addr")
                return
            }
            val mgr = com.adcleaner.app.adb.SuspendManager(applicationContext)
            try {
                com.adcleaner.app.adb.AdbPairState.status.value = "正在配对（配对码 $code）…"
                val pr = mgr.pair(host, pairPort, code)
                if (pr.isFailure) throw (pr.exceptionOrNull() ?: RuntimeException("配对未成功"))
                android.util.Log.i("AdbPair", "flow: 配对成功")
                CleanState.ok("配对成功")
                speak("配对成功，正在连接")
                com.adcleaner.app.adb.AdbPairState.status.value = "配对成功！正在寻找连接端口…"

                val ep = com.adcleaner.app.adb.AdbWireless.discover(
                    applicationContext, com.adcleaner.app.adb.AdbWireless.SERVICE_CONNECT
                )
                android.util.Log.i("AdbPair", "flow: NSD connect 端点=${ep?.host}:${ep?.port}")
                if (ep == null) {
                    fail("没发现连接端口：请保持「无线调试」打开后重试")
                    return
                }
                com.adcleaner.app.adb.AdbPairState.status.value = "正在连接端口 ${ep.port}…"
                CleanState.step("NSD 发现连接端口 ${ep.port}，TLS 连接中…")
                val r = mgr.connectTls(ep.port)
                if (r.isFailure) {
                    fail("TLS 连接失败：${r.exceptionOrNull()?.message}")
                    return
                }
                com.adcleaner.app.adb.AdbAutomation.saveLastPort(applicationContext, ep.port, tls = true)
                CleanState.ok("已连接无线调试（端口 ${ep.port}）")
                com.adcleaner.app.adb.AdbPairState.status.value = "配对并连接成功（端口 ${ep.port}）"
                com.adcleaner.app.adb.AdbPairState.connectedPort.value = ep.port
                com.adcleaner.app.adb.AdbPairState.done.value = true
                speak("已经连上了，深度清理可以用啦")
            } finally {
                mgr.disconnect()
            }
            // 尝试回到本应用；后台启动受限时留通知即可
            try {
                startActivity(
                    android.content.Intent(this@AdCleanerService, com.adcleaner.app.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.adcleaner.app.adb.AdbPairState.status.value = "配对失败：${e.message}"
            com.adcleaner.app.adb.AdbPairState.done.value = false
            CleanState.log("  ✗ 配对失败：${e.message}")
            speak("配对没有成功，您可以再点一次试试")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 记录断点：清理仍在跑（没完成、没取消）才需要续跑
        val ui = CleanState.ui.value
        if (ui.running && !ui.done && !ui.aborted) {
            pendingResumeFrom = (ui.progress - 1).coerceAtLeast(0)
            pendingInterrupted = true
            CleanState.log("无障碍服务被系统中断，等待自动继续…")
        }
        instance = null
        engine = null
        scope.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ttsReady = false
        return super.onUnbind(intent)
    }

    fun runRule(rule: BrandRule, includeAdb: Boolean) {
        pendingRule = rule
        pendingResumeFrom = 0
        pendingIncludeAdb = includeAdb
        pendingInterrupted = false
        resumeAttempts = 0
        startJob(rule, includeAdb, 0, reset = true)
    }

    private fun startJob(rule: BrandRule, includeAdb: Boolean, from: Int, reset: Boolean) {
        job?.cancel()
        if (reset) CleanState.reset()
        job = scope.launch {
            try {
                engine?.executeBrand(rule, includeAdb, from)
            } catch (e: CancellationException) {
                CleanState.aborted()
                throw e
            } catch (e: Exception) {
                CleanState.fail("清理异常：${e.message}")
                CleanState.aborted()
            }
        }
    }

    fun cancel() {
        pendingInterrupted = false
        job?.cancel()
        CleanState.aborted()
        CleanState.log("已中止清理。")
    }
}
