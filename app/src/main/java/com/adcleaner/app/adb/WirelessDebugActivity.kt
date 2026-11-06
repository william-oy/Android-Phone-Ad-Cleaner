package com.adcleaner.app.adb

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adcleaner.app.R
import com.adcleaner.app.databinding.ActivityWirelessDebugBinding
import kotlinx.coroutines.launch

/**
 * 增强模式界面：填无线调试端口 → 连接本机 adbd → 列出常见广告包 → 挂起/还原。
 *
 * 用 [SuspendManager] 走 ADB 协议执行 `pm suspend --user 0 <pkg>`。
 * 所有网络调用在 `lifecycleScope` 协程里发起，底层切到 `Dispatchers.IO`。
 */
class WirelessDebugActivity : AppCompatActivity() {

    private lateinit var b: ActivityWirelessDebugBinding
    private val manager by lazy { SuspendManager(this) }

    /** 一个广告包条目：包名、显示名、是否允许挂起。 */
    private data class AdPkg(val pkg: String, val label: String, val safe: Boolean)

    /** 各品牌常见广告/推荐组件。safe=false 的会标红并禁用按钮（挂起会坏功能）。 */
    private val packages = listOf(
        AdPkg("com.miui.systemAdSolution", "小米·系统广告", true),
        AdPkg("com.opos.ads", "OPPO/一加·广告服务", true),
        AdPkg("com.heytap.pictorial", "OPPO·画报", true),
        AdPkg("com.oppo.instant.local.service", "OPPO·快应用服务", true),
        AdPkg("com.nearme.instant.card", "OPPO·快应用卡片", true),
        AdPkg("com.heytap.quicksearchbox", "OPPO·全局搜索", false),
        AdPkg("com.coloros.assistantscreen", "ColorOS·智能侧边栏", false),
        AdPkg("com.oplus.appdetail", "OPPO·应用详情", false),
    )

    /** 行视图句柄，便于刷新状态。 */
    private class RowState(val status: TextView, val suspendBtn: Button, val unsuspendBtn: Button)

    private val rowStates = mutableMapOf<String, RowState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWirelessDebugBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnOpenSettings.setOnClickListener {
            // 直接打开「开发者选项」（无线调试在其下）
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }

        b.btnConnect.setOnClickListener { onConnect() }
        b.btnPair.setOnClickListener { onPair() }

        buildPackageRows()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 协程作用域已取消，这里只做本地 socket 清理
        manager.disconnect()
    }

    private fun onPair() {
        val code = b.etPairCode.text.toString().trim()
        val port = b.etPairPort.text.toString().trim().toIntOrNull()
        if (code.length != 6 || code.any { !it.isDigit() } || port == null || port !in 1..65535) {
            b.tvStatus.text = getString(R.string.wd_pair_code_invalid)
            return
        }
        b.btnPair.isEnabled = false
        b.tvStatus.text = getString(R.string.wd_pairing)
        log("> pair 127.0.0.1:$port code=$code")

        lifecycleScope.launch {
            val r = manager.pair(port, code)
            b.btnPair.isEnabled = true
            if (r.isSuccess) {
                b.tvStatus.text = getString(R.string.wd_paired)
                log("< 配对成功，去填连接端口")
            } else {
                val reason = r.exceptionOrNull()?.message ?: "未知错误"
                b.tvStatus.text = getString(R.string.wd_pair_failed, reason)
                log("< 配对失败：$reason")
            }
        }
    }

    private fun onConnect() {
        val port = b.etPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            b.tvStatus.text = getString(R.string.wd_port_invalid)
            return
        }
        b.btnConnect.isEnabled = false
        b.tvStatus.text = getString(R.string.wd_connecting)
        log("> connect 127.0.0.1:$port")

        lifecycleScope.launch {
            val r = manager.connect(port)
            b.btnConnect.isEnabled = true
            if (r.isSuccess) {
                b.tvStatus.text = getString(R.string.wd_connected)
                b.containerPackages.visibility = View.VISIBLE
                log("< 已连接 adbd")
                refreshSuspended()
            } else {
                val reason = r.exceptionOrNull()?.message ?: "未知错误"
                b.tvStatus.text = getString(R.string.wd_connect_failed, reason)
                log("< 连接失败：$reason")
            }
        }
    }

    /** 构建广告包列表行。 */
    private fun buildPackageRows() {
        b.containerPackages.removeAllViews()
        rowStates.clear()
        val pad = (12 * resources.displayMetrics.density).toInt()

        for (p in packages) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setPadding(0, pad, 0, pad) }
            }

            val name = TextView(this).apply {
                text = if (p.safe) "${p.label}\n${p.pkg}"
                else "${p.label}\n${p.pkg}\n⚠ ${getString(R.string.wd_danger_no_suspend)}"
                textSize = 13f
                setTextColor(getColor(if (p.safe) R.color.text_primary else R.color.danger_red))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)

            if (p.safe) {
                val status = TextView(this).apply {
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = pad }
                }
                val suspendBtn = Button(this).apply { text = getString(R.string.wd_btn_suspend) }
                val unsuspendBtn = Button(this).apply { text = getString(R.string.wd_btn_unsuspend) }
                suspendBtn.setOnClickListener {
                    runPkg(p.pkg, suspend = true, status, suspendBtn, unsuspendBtn)
                }
                unsuspendBtn.setOnClickListener {
                    runPkg(p.pkg, suspend = false, status, suspendBtn, unsuspendBtn)
                }
                row.addView(status)
                row.addView(suspendBtn)
                row.addView(unsuspendBtn)
                rowStates[p.pkg] = RowState(status, suspendBtn, unsuspendBtn)
            } else {
                val warn = TextView(this).apply {
                    text = getString(R.string.wd_danger_no_suspend)
                    textSize = 12f
                    setTextColor(getColor(R.color.danger_red))
                }
                row.addView(warn)
            }

            // 行间分隔
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 0, 0, 0) }
                setBackgroundColor(0x22000000)
            }
            b.containerPackages.addView(row)
            b.containerPackages.addView(divider)
        }
    }

    /** 执行挂起或还原。 */
    private fun runPkg(
        pkg: String,
        suspend: Boolean,
        status: TextView,
        suspendBtn: Button,
        unsuspendBtn: Button,
    ) {
        suspendBtn.isEnabled = false
        unsuspendBtn.isEnabled = false
        status.text = getString(R.string.wd_working)
        val cmd = if (suspend) "pm suspend --user 0" else "pm unsuspend --user 0"
        log("> $cmd $pkg")

        lifecycleScope.launch {
            val r = if (suspend) manager.suspendPackage(pkg)
            else manager.unsuspendPackage(pkg)
            suspendBtn.isEnabled = true
            unsuspendBtn.isEnabled = true
            if (r.isSuccess) {
                val out = r.getOrNull()?.trim().orEmpty()
                status.text = getString(if (suspend) R.string.wd_suspended else R.string.wd_active)
                log("< ${out.ifEmpty { "ok" }}")
            } else {
                status.text = getString(R.string.wd_failed)
                log("< 失败：${r.exceptionOrNull()?.message}")
            }
        }
    }

    /** 连接成功后刷新各包挂起状态。 */
    private fun refreshSuspended() {
        lifecycleScope.launch {
            val r = manager.listSuspended()
            if (!r.isSuccess) return@launch
            val suspended = r.getOrNull().orEmpty().toSet()
            for (p in packages) {
                val st = rowStates[p.pkg] ?: continue
                st.status.text = getString(
                    if (p.pkg in suspended) R.string.wd_suspended else R.string.wd_active
                )
            }
        }
    }

    private fun log(msg: String) {
        b.tvLog.append(msg + "\n")
        // 新日志自动滚到底，方便直接看到最新输出（也能手动上下拉动翻看）
        b.svLog.post { b.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
