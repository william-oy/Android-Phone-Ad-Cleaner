package com.adcleaner.app.model

import kotlinx.serialization.Serializable

/**
 * 品牌/ROM 规则：一个品牌一份，含多个清理段、可挂起广告包、品牌坑。
 * 数据来源：rddmwmddr/android-ad-cleaner 的 docs/清单 与 recipes，经提炼为品牌共性路径。
 */
@Serializable
data class BrandRule(
    val brand: String,                       // "xiaomi"
    val displayName: String,                 // "小米 / 红米（HyperOS）"
    val manufacturerKeywords: List<String>,  // Build.MANUFACTURER 命中词，如 ["xiaomi","redmi"]
    val romIdentifiers: List<String> = emptyList(), // ro.build.version.* 命中词
    val sections: List<Section>,
    val adPackages: List<AdPackage> = emptyList(),
    val pitfalls: List<Pitfall> = emptyList(),
    val notes: String = ""
)

/** 一个清理段，对应一个设置区域（系统广告、应用商店、浏览器、营销通知…）。 */
@Serializable
data class Section(
    val id: String,                          // "system_ad"
    val name: String,                        // "系统广告"
    val description: String,                 // 给用户看的一句话说明
    val guidePath: String,                   // 图文路径："设置 → 隐私与安全 → 更多安全设置 → 广告服务"
    val steps: List<Step>,
    val targets: List<String> = emptyList(), // 要关的开关标题词
    val protectedKeywords: List<String> = emptyList(), // 保留开关词（保护类/功能类）
    val requiresAdb: Boolean = false,        // 是否需要 adb（挂起包/断网）
    val pitfalls: List<String> = emptyList() // 本段坑提示
)

/**
 * 一个原子操作。action 取值：
 *  openSettings   打开系统设置（intent 指定 action/component/scheme）
 *  launchApp      启动 App（intent.component 或包名）
 *  tap            点击含 [text] 或 [texts] 之一的可点节点（texts=多入口兜底）
 *  tapExact       精确匹配文字点击
 *  tapIcon        右上区域无文字齿轮图标
 *  tapTab         底部/顶部标签
 *  goInto         点入口进子页（点完等页面稳定）
 *  search         设置搜索直达：点搜索框 → 输入 text → 点结果（各品牌通用，替代写死路径）
 *  switchOff      关掉标题含 targets 的开关，protectedKeywords 保留
 *  switchOnly     只关 targets 指定的开关
 *  switchOn       打开保护类开关（限制广告跟踪等）
 *  scrollDown     往下翻 scrolls 屏
 *  confirm        处理"确认关闭"弹窗（点 确定/确认/知道了）
 *  wait           等待 ms
 *  back           返回
 *  readBack       回读确认（核对开关状态）
 *  skipIfMissing  找不到 text 就跳过本段
 */
@Serializable
data class Step(
    val action: String,
    val text: String? = null,
    val texts: List<String> = emptyList(),
    val exact: Boolean = false,
    val scrolls: Int = 6,
    val targets: List<String> = emptyList(),
    val protectedKeywords: List<String> = emptyList(),
    val intent: IntentSpec? = null,
    val ms: Long = 800L,
    val note: String? = null
)

@Serializable
data class IntentSpec(
    val action: String? = null,              // android.settings.SETTINGS 等
    val data: String? = null,                // scheme uri，如 gs://search/setting
    val component: String? = null,           // "com.android.settings/.ad.AdServiceSettings"
    val flags: Int? = null                   // 默认带 NEW_TASK
)

/** 可挂起的广告/快应用包（增强模式，需 adb）。 */
@Serializable
data class AdPackage(
    val pkg: String,
    val name: String,
    val effect: String,                      // 挂起后效果
    val neverSuspend: Boolean = false,       // 永不挂起（会坏功能）
    val keepNetworkBlock: Boolean = false,   // 挂起后尝试断网
    val restoreHint: String = ""             // 还原说明
)

@Serializable
data class Pitfall(
    val title: String,
    val desc: String,
    val severity: String = "warn"            // info / warn / danger
)
