package com.adcleaner.app.cleaner

import android.content.Context

/** 编排：检测品牌 → 匹配规则 → 驱动无障碍服务跑流程。 */
object CleanOrchestrator {

    /** 返回 true=已启动；false=未启动（服务没开或没匹配规则）。 */
    fun start(ctx: Context, includeAdb: Boolean): Boolean {
        val svc = AdCleanerService.instance
        if (svc == null) {
            CleanState.reset()
            CleanState.fail("无障碍服务没开。请先点「开启无障碍权限」。")
            return false
        }
        val info = BrandDetector.detect()
        val rule = com.adcleaner.app.rules.RuleLoader.match(ctx, info.brand)
        if (rule == null) {
            CleanState.reset()
            CleanState.fail("暂未识别到「${info.display}」的专用规则（brand=${info.brand}）。\n可点「图文使用引导」照清单手动关，或选通用流程。")
            CleanState.aborted()
            return false
        }
        svc.runRule(rule, includeAdb)
        return true
    }

    fun cancel() = AdCleanerService.instance?.cancel() ?: Unit
}
