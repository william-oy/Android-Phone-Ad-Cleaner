package com.adcleaner.app.adb

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adcleaner.app.R
import com.adcleaner.app.databinding.ActivityWirelessDebugBinding
import kotlinx.coroutines.launch

/**
 * 深度清理界面（适老化）：
 * - 打开本页自动尝试上次成功过的端口，连上后老人只需点「一键冻结全部广告组件」。
 * - 首次配对、逐个组件、日志等高级内容全部默认收起。
 * - 连接后还可用调试权限帮用户打开无障碍（主界面同一能力）。
 */
class WirelessDebugActivity : AppCompatActivity() {

    private lateinit var b: ActivityWirelessDebugBinding
    private val manager by lazy { SuspendManager(this) }

    /** 本次会话连接成功的端口（自动开无障碍要用）。 */
    private var connectedPort = -1

    /** 一个广告包条目：包名、显示名、是否允许挂起。 */
    private data class AdPkg(val pkg: String, val label: String, val safe: Boolean)

    /** 各品牌常见广告/推荐组件。safe=false 的标红不参与冻结（挂起会坏功能）。 */
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
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        b.btnAutoPair.setOnClickListener { onAutoPair() }
        b.btnConnect.setOnClickListener { onConnect() }
        b.btnPair.setOnClickListener { onPair() }
        b.btnSuspendAll.setOnClickListener { suspendAll() }
        b.btnUnsuspendAll.setOnClickListener { unsuspendAll() }
        b.btnEnableAccessibility.setOnClickListener { enableAccessibilityViaAdb() }

        // 默认收起的高级区
        b.btnTogglePair.setOnClickListener { toggle(b.containerPair) }
        b.btnTogglePkgList.setOnClickListener { toggle(b.containerPackages) }
        b.btnToggleLog.setOnClickListener { toggle(b.svLog) }

        buildPackageRows()
        tryAutoReconnect()
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
        manager.disconnect()
    }

    // ---------- 连接 ----------

    /** 打开页面自动重连上次成功的端口（TLS/经典自动区分）：连上直接进入操作区，老人零输入。 */
    private fun tryAutoReconnect() {
        val port = AdbAutomation.resolvePort(this)
        val tls = AdbAutomation.lastIsTls(this)
        b.tvStatus.text = getString(R.string.wd_auto_reconnecting)
        lifecycleScope.launch {
            val r = if (tls) manager.connectTls(port) else manager.connect(port)
            if (r.isSuccess) onConnected(port, tls = tls) else resetToIdle()
        }
    }

    private fun resetToIdle() {
        b.tvStatus.text = getString(R.string.wd_status_idle_elder)
        b.tvStatus.setTextColor(getColor(R.color.warn_orange))
        b.tvStatus.setBackgroundResource(R.drawable.bg_status_danger)
        b.containerConnect.visibility = View.VISIBLE
        b.containerActions.visibility = View.GONE
    }

    private fun onConnected(port: Int, tls: Boolean) {
        connectedPort = port
        // 无论手动连还是自动重连成功，都记下端口与模式：主界面一键清理与下次自动重连都靠它
        AdbAutomation.saveLastPort(this, port, tls)
        b.tvStatus.text = if (tls) getString(R.string.wd_tls_connected_fmt, port)
        else getString(R.string.wd_auto_connect_ok_fmt, port)
        b.tvStatus.setTextColor(getColor(R.color.safe_green))
        b.tvStatus.setBackgroundResource(R.drawable.bg_status_safe)
        b.containerConnect.visibility = View.GONE
        b.containerActions.visibility = View.VISIBLE
        refreshSuspended()
    }

    private fun onConnect() {
        val port = b.etPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            setStatus(getString(R.string.wd_port_invalid))
            return
        }
        b.btnConnect.isEnabled = false
        setStatus(getString(R.string.wd_connecting))
        log("> connect 127.0.0.1:$port")

        lifecycleScope.launch {
            // 先试经典协议（adb tcpip），失败再试无线调试 TLS（配对过即可直连）
            var r = manager.connect(port)
            var tlsUsed = false
            if (r.isFailure) {
                log("< 经典协议失败，尝试无线调试 TLS…")
                r = manager.connectTls(port)
                tlsUsed = r.isSuccess
            }
            b.btnConnect.isEnabled = true
            if (r.isSuccess) {
                onConnected(port, tls = tlsUsed)
                log("< 已连接 adbd${if (tlsUsed) "（TLS）" else ""}")
            } else {
                val reason = r.exceptionOrNull()?.message ?: "未知错误"
                setStatus(getString(R.string.wd_connect_failed, reason))
                log("< 连接失败：$reason")
            }
        }
    }

    /**
     * 一键自动配对（零输入）：
     * 1. 无障碍读取屏幕上的 6 位配对码和配对端口（要求配对码对话框在屏幕上）
     * 2. 走 SPAKE2 + TLS 配对协议（设备登记我们的公钥）
     * 3. NSD 发现连接端口，TLS 直连
     */
    private fun onAutoPair() {
        lifecycleScope.launch {
            setStatus(getString(R.string.wd_auto_pair_reading))
            val info = com.adcleaner.app.cleaner.AdCleanerService.instance?.readPairingCodeAndPort()
            if (info == null) {
                setStatus(getString(R.string.wd_auto_pair_need_dialog))
                log("< 没读到配对码：请先打开「使用配对码配对设备」对话框")
                return@launch
            }
            val (code, addr) = info
            val host = addr.substringBefore(":")
            val pairPort = addr.substringAfter(":").toIntOrNull() ?: 0
            if (pairPort <= 0) {
                setStatus(getString(R.string.wd_auto_pair_need_dialog))
                return@launch
            }
            setStatus(getString(R.string.wd_auto_pairing_fmt, code))
            log("> 自动配对 $host:$pairPort code=$code")

            val guid = try {
                AdbPairing.pair(this@WirelessDebugActivity, host, pairPort, code)
            } catch (e: Exception) {
                setStatus(getString(R.string.wd_connect_failed, e.message ?: "未知错误"))
                log("< 配对失败：${e.message}")
                return@launch
            }
            log("< 配对成功（设备 $guid），正在发现连接端口…")
            setStatus(getString(R.string.wd_auto_pair_ok))

            val ep = AdbWireless.discover(this@WirelessDebugActivity, AdbWireless.SERVICE_CONNECT)
            val tlsPort = ep?.port
            if (tlsPort == null) {
                setStatus(getString(R.string.wd_nsd_no_connect))
                log("< 没发现连接端口：请确认「无线调试」保持打开")
                return@launch
            }
            setStatus(getString(R.string.wd_auto_pair_connecting_fmt, tlsPort))
            log("> TLS connect 127.0.0.1:$tlsPort")
            val r = manager.connectTls(tlsPort)
            if (r.isSuccess) {
                onConnected(tlsPort, tls = true)
                log("< 已连接无线调试（TLS）")
            } else {
                val reason = r.exceptionOrNull()?.message ?: "未知错误"
                setStatus(getString(R.string.wd_connect_failed, reason))
                log("< TLS 连接失败：$reason")
            }
        }
    }

    private fun onPair() {
        val code = b.etPairCode.text.toString().trim()
        val port = b.etPairPort.text.toString().trim().toIntOrNull()
        if (code.length != 6 || code.any { !it.isDigit() } || port == null || port !in 1..65535) {
            setStatus(getString(R.string.wd_pair_code_invalid))
            return
        }
        b.btnPair.isEnabled = false
        setStatus(getString(R.string.wd_pairing))
        log("> pair 127.0.0.1:$port code=$code")

        lifecycleScope.launch {
            val guid = try {
                AdbPairing.pair(this@WirelessDebugActivity, "127.0.0.1", port, code)
            } catch (e: Exception) {
                null
            }
            b.btnPair.isEnabled = true
            if (guid != null) {
                setStatus(getString(R.string.wd_paired))
                log("< 配对成功（设备 $guid），去点「自动配对并连接」即可连上")
            } else {
                val reason = "配对握手失败"
                setStatus(getString(R.string.wd_pair_failed, reason))
                log("< 配对失败：$reason")
            }
        }
    }

    // ---------- 一键冻结 / 全部还原 ----------

    /** 逐个冻结 safe 的广告包，状态条实时报进度。 */
    private fun suspendAll() {
        val targets = packages.filter { it.safe }
        if (connectedPort <= 0 || targets.isEmpty()) return
        setBusy(true)
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            targets.forEachIndexed { i, p ->
                setStatus(getString(R.string.wd_suspend_all_progress_fmt, p.label, i + 1, targets.size))
                val r = manager.suspendPackage(p.pkg)
                if (r.isSuccess) {
                    ok++
                    rowStates[p.pkg]?.status?.text = getString(R.string.wd_suspended)
                } else {
                    fail++
                    rowStates[p.pkg]?.status?.text = getString(R.string.wd_failed)
                }
                log("< ${p.pkg}：${if (r.isSuccess) "ok" else (r.exceptionOrNull()?.message ?: "失败")}")
            }
            setBusy(false)
            setStatus(getString(R.string.wd_suspend_all_done_fmt, ok, fail))
        }
    }

    /** 把已知列表里当前处于挂起状态的包全部还原。 */
    private fun unsuspendAll() {
        if (connectedPort <= 0) return
        setBusy(true)
        lifecycleScope.launch {
            val suspendedRes = manager.listSuspended()
            val targets = if (suspendedRes.isSuccess)
                packages.filter { it.pkg in suspendedRes.getOrNull().orEmpty() && it.safe }
            else packages.filter { it.safe }
            if (targets.isEmpty()) {
                setBusy(false)
                setStatus(getString(R.string.wd_unsuspend_all_done_fmt, 0, 0))
                return@launch
            }
            var ok = 0
            var fail = 0
            targets.forEachIndexed { i, p ->
                setStatus(getString(R.string.wd_suspend_all_progress_fmt, p.label, i + 1, targets.size))
                val r = manager.unsuspendPackage(p.pkg)
                if (r.isSuccess) {
                    ok++
                    rowStates[p.pkg]?.status?.text = getString(R.string.wd_active)
                } else {
                    fail++
                    rowStates[p.pkg]?.status?.text = getString(R.string.wd_failed)
                }
                log("< unsuspend ${p.pkg}：${if (r.isSuccess) "ok" else (r.exceptionOrNull()?.message ?: "失败")}")
            }
            setBusy(false)
            setStatus(getString(R.string.wd_unsuspend_all_done_fmt, ok, fail))
        }
    }

    /** 用已连接的调试权限帮用户打开无障碍（同主界面）。 */
    private fun enableAccessibilityViaAdb() {
        if (connectedPort <= 0) return
        setBusy(true)
        lifecycleScope.launch {
            val r = AdbAutomation.enableAccessibility(this@WirelessDebugActivity, manager, connectedPort)
            setBusy(false)
            if (r.isSuccess) {
                Toast.makeText(this@WirelessDebugActivity, R.string.wd_acc_enabled, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@WirelessDebugActivity, R.string.toast_accessibility_auto_fail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.btnSuspendAll.isEnabled = !busy
        b.btnUnsuspendAll.isEnabled = !busy
        b.btnEnableAccessibility.isEnabled = !busy
    }

    private fun setStatus(text: String) {
        b.tvStatus.text = text
        b.tvStatus.setTextColor(getColor(R.color.m3_primary))
    }

    // ---------- 广告包列表 ----------

    /** 构建广告包列表行（大字大按钮）。 */
    private fun buildPackageRows() {
        b.containerPackages.removeAllViews()
        rowStates.clear()
        val pad = (14 * resources.displayMetrics.density).toInt()

        for (p in packages) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setPadding(0, pad, 0, pad) }
            }

            val name = TextView(this).apply {
                text = if (p.safe) p.label
                else "${p.label}\n⚠ ${getString(R.string.wd_danger_no_suspend)}"
                textSize = 17f
                setTextColor(getColor(if (p.safe) R.color.text_primary else R.color.danger_red))
                setLineSpacing(0f, 1.1f)
            }
            row.addView(name)

            // 包名缩小放第二行，需要排查时才看得
            val pkgName = TextView(this).apply {
                text = p.pkg
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            }
            row.addView(pkgName)

            if (p.safe) {
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, 0) }
                }
                val status = TextView(this).apply {
                    textSize = 15f
                    setTextColor(getColor(R.color.text_secondary))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                    )
                }
                val suspendBtn = Button(this).apply {
                    text = getString(R.string.wd_btn_suspend)
                    textSize = 15f
                }
                val unsuspendBtn = Button(this).apply {
                    text = getString(R.string.wd_btn_unsuspend)
                    textSize = 15f
                }
                suspendBtn.setOnClickListener {
                    runPkg(p.pkg, suspend = true, status, suspendBtn, unsuspendBtn)
                }
                unsuspendBtn.setOnClickListener {
                    runPkg(p.pkg, suspend = false, status, suspendBtn, unsuspendBtn)
                }
                actions.addView(status)
                actions.addView(suspendBtn)
                actions.addView(unsuspendBtn)
                row.addView(actions)
                rowStates[p.pkg] = RowState(status, suspendBtn, unsuspendBtn)
            } else {
                val warn = TextView(this).apply {
                    text = getString(R.string.wd_danger_no_suspend)
                    textSize = 14f
                    setTextColor(getColor(R.color.danger_red))
                }
                row.addView(warn)
            }

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(0x22000000)
            }
            b.containerPackages.addView(row)
            b.containerPackages.addView(divider)
        }
    }

    /** 执行挂起或还原（单包）。 */
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

    private fun toggle(view: View) {
        view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun log(msg: String) {
        b.tvLog.append(msg + "\n")
        b.svLog.post { b.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
