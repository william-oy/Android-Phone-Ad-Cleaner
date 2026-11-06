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
