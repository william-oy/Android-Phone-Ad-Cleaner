package com.adcleaner.app.cleaner

import android.content.Context

/** 编排：检测品牌 → 匹配规则 → 驱动无障碍服务跑流程。 */
object CleanOrchestrator {

    /** 返回 true=已启动；false=未启动（服务没开或没匹配规则）。 */
    fun start(ctx: Context, includeAdb: Boolean): Boolean {
        val svc = AdCleanerService.instance
        if (svc == null) {
            CleanState.reset()
            CleanState.fail("权限还没开好。请回主界面点「帮我打开无障碍权限」。")
            return false
        }
        val info = BrandDetector.detect()
        val rule = com.adcleaner.app.rules.RuleLoader.match(ctx, info.brand)
        if (rule == null) {
            CleanState.reset()
            CleanState.fail("这台手机（${info.display}）暂时没有专用清理方案。\n请点「图文使用引导」，照着清单手动关一下。")
            CleanState.aborted()
            return false
        }
        svc.runRule(rule, includeAdb)
        return true
    }

    fun cancel() = AdCleanerService.instance?.cancel() ?: Unit
}
