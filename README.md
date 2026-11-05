# 去广告助手（AdCleaner）

> 装好就能清，不用电脑、不用数据线、不用 root。

一个安卓 app，自动帮你关掉国产手机系统里的广告、热搜、资讯推荐和营销推送。基于无障碍服务**在本机自动读屏点开关**，普通人装好 → 开个无障碍权限 → 一键清理即可。

本项目思路源自 [rddmwmddr/android-ad-cleaner](https://github.com/rddmwmddr/android-ad-cleaner)（MIT），把它「电脑 + ADB + 数据线」的方案，改造成「手机自己点」的 app：原项目靠 adb 远程读屏（uiautomator2）点开关，本 app 用安卓无障碍服务（AccessibilityService）在本机读屏点开关，**无需电脑、无需 USB 调试**。

## 它能做什么

| 类别 | 例子 | 怎么处理 |
|---|---|---|
| 系统广告 | 个性化广告推荐、广告跟踪（OAID） | 自动点系统设置里的开关 |
| 热搜资讯 | 下拉搜索热榜、负一屏资讯、浏览器信息流 | 自动点各 App 设置里的开关 |
| 推广位 | 应用商店轮播、开屏、活动专区 | 自动点商店「服务管理」开关 |
| 营销推送 | 各 App「内容与营销」「活动优惠」通知 | 按渠道关，订单/快递/更新通知保留 |
| 广告组件 | `com.opos.ads`、`com.miui.systemAdSolution` 等 | **增强模式**：开无线调试后 `pm suspend` 挂起 |

## 和原项目的区别

| | 原项目 | 本 app |
|---|---|---|
| 原理 | Python + ADB + uiautomator2 远程读屏 | 安卓无障碍服务本机读屏 |
| 要电脑 | 要（Mac/Windows + 数据线） | 不要 |
| 要 USB 调试 | 要 | 不要（增强模式要无线调试） |
| 要 root | 不要 | 不要 |
| 挂起广告包 | adb `pm suspend` | 增强模式：app 内置 ADB client 连本机 |
| 可还原 | 是 | 是 |

## 用法（普通人）

1. 装好 APK
2. 打开 app，点「开启无障碍权限」→ 在列表里找到「去广告助手·自动点开关」打开
3. 回到 app，点「一键清理广告」
4. 看实时进度，等完成
5. 卡住了？点「图文使用引导」选你的品牌，照图文一步步点

## 增强模式（挂起广告包，可选）

无障碍只能点开关，挂起广告组件包（`pm suspend`）需要 ADB 权限。增强模式让 app 自己当 ADB client 连本机无线调试，**仍不用电脑**：

1. 设置 → 系统 → 开发者选项 → 打开「无线调试」
2. 点「增强模式」→ 填无线调试设置页显示的端口 → 连接
3. 首次会弹「允许调试」，点允许
4. 选要挂起的广告包 → 挂起
5. 还原：同页点「还原」

> 增强模式需 Android 11+。挂起不等于进程立即退出，个别广告服务会被系统重新拉起（已知，见引导里的品牌坑）。

## 支持品牌

小米/红米、OPPO/一加/realme、vivo/iQOO、荣耀、华为、魅族。规则数据在 `app/src/main/assets/rules/*.json`，图文引导在 `app/src/main/assets/guides/*.html`，可自行增补。

## 云构建 APK（GitHub Actions）

不用本地装 Android 开发环境，push 即出包：

1. 把本仓库 push 到你的 GitHub
2. Actions 自动构建（`.github/workflows/build.yml`）
3. 在 Actions 运行记录里下载 `adcleaner-apk` artifact
4. 打 tag `v0.1.0` 会自动发 Release，APK 直接挂在 Release 页

构建产物：`adcleaner-debug.apk`（debug 签名，可安装运行）。要正式签名，把 keystore 转 base64 存 GitHub Secrets，改 workflow 用 `signingConfig`（见 workflow 注释）。

## 工程结构

```
app/
  src/main/
    java/com/adcleaner/app/
      MainActivity.kt                 主界面
      cleaner/                        无障碍服务 + 引擎 + 编排 + 状态
        AdCleanerService.kt           AccessibilityService
        AccessibilityEngine.kt        读屏/找开关/点击/翻页/回读
        CleanOrchestrator.kt          检测品牌→匹配规则→驱动服务
        BrandDetector.kt
        CleanState.kt                 UI 状态总线
      rules/RuleLoader.kt             加载 assets/rules/*.json
      model/BrandRule.kt              数据模型（JSON schema）
      adb/                            增强模式：ADB client + 挂起管理 + UI
      guide/                          图文引导页（WebView）
    assets/
      rules/*.json                    6 大品牌规则数据
      guides/*.html                   超详细图文引导
    res/                              布局/字符串/主题/图标
  build.gradle.kts
build.gradle.kts / settings.gradle.kts / gradle.properties
.github/workflows/build.yml           云构建
reference/                            原项目克隆（仅供研究，不进构建）
```

## 安全声明

- 只拨系统和 App 自带的开关；不卸载、不停用系统应用（`pm uninstall`/`pm disable-user` 都不用）。
- 挂起广告包用 `pm suspend`，一条命令可还原。
- 不改 system 分区，不刷模块，不影响系统更新。
- 不替你同意任何 App 协议；遇到协议页点「取消」。
- 保护类开关（限制广告跟踪等）会**打开**，不是关。
- 各厂商界面随系统更新变化，使用前请看图文引导里的品牌坑；结果以你的手机为准。

## 致谢

- 规则数据与品牌坑知识全部提炼自 [rddmwmddr/android-ad-cleaner](https://github.com/rddmwmddr/android-ad-cleaner)（MIT）的 `docs/清单/` 与 `recipes/`，感谢原作者与社区贡献者的真机实测。

## 许可证

MIT
