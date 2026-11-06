package com.adcleaner.app.cleaner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.adcleaner.app.model.BrandRule
import kotlinx.coroutines.CancellationException

/**
 * 无障碍服务：app 的「手和眼」。主界面通过 CleanOrchestrator 调 runRule，
 * 引擎在本服务进程里读屏、点开关、翻页。
 */
class AdCleanerService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: AccessibilityEngine? = null
    private var job: Job? = null

    companion object {
        @Volatile
        var instance: AdCleanerService? = null
            private set

        private const val SVC = "com.adcleaner.app.cleaner.AdCleanerService"

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
        engine = AccessibilityEngine(this)
        CleanState.log("无障碍服务已就绪，可以开始清理。")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 主动读屏，不依赖事件流
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        engine = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    fun runRule(rule: BrandRule, includeAdb: Boolean) {
        job?.cancel()
        CleanState.reset()
        job = scope.launch {
            try {
                engine?.executeBrand(rule, includeAdb)
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
        job?.cancel()
        CleanState.aborted()
        CleanState.log("已中止清理。")
    }
}
