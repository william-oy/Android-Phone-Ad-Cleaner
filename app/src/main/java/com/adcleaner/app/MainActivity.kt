package com.adcleaner.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adcleaner.app.adb.AdbAutomation
import com.adcleaner.app.adb.SuspendManager
import com.adcleaner.app.adb.WirelessDebugActivity
import com.adcleaner.app.cleaner.AdCleanerService
import com.adcleaner.app.cleaner.BrandDetector
import com.adcleaner.app.cleaner.CleanOrchestrator
import com.adcleaner.app.cleaner.CleanState
import com.adcleaner.app.databinding.ActivityMainBinding
import com.adcleaner.app.guide.BrandGuideActivity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 主界面（适老化）：
 * - 一眼只看到一个超大主按钮；权限没开时点它会自动用调试权限打开，再直接开始清理。
 * - 三步进度条 + 大字当前动作，老人不用看技术日志。
 * - 语音播报开始/完成；高级功能全部收进「更多选项」。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceOn = true

    /** 正在通过 ADB 自动开无障碍（防重复点击）。 */
    private var autoEnabling = false

    private var lastUi = CleanState.Ui()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        voiceOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_VOICE, true)

        setupBrandCard()
        setupTts()
        setupButtons()
        observeCleanState()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ---------- 品牌卡：口语化告诉老人方案已自动匹配 ----------

    private fun setupBrandCard() {
        val info = BrandDetector.detect()
        if (info.brand == "unknown") {
            b.tvBrand.text = getString(R.string.tv_brand_unknown)
            b.tvBrandPlan.text = getString(R.string.tv_brand_plan_unknown)
        } else {
            b.tvBrand.text = "你的手机：${info.display}"
            b.tvBrandPlan.text = getString(R.string.tv_brand_plan_fmt, info.display)
        }
    }

    // ---------- 语音播报 ----------

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.SIMPLIFIED_CHINESE
        }
        refreshVoiceButton()
    }

    private fun speak(text: String) {
        if (!voiceOn || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "adcleaner_$text")
    }

    private fun refreshVoiceButton() {
        b.btnVoice.text = getString(if (voiceOn) R.string.btn_voice_on else R.string.btn_voice_off)
    }

    // ---------- 按钮 ----------

    private fun setupButtons() {
        b.btnStartClean.setOnClickListener {
            if (lastUi.running) {
                confirmStop()
            } else {
                ensureAccessibilityThenStart()
            }
        }

        b.btnEnableAccessibility.setOnClickListener { smartEnableAccessibility(andStart = false) }

        b.btnGuide.setOnClickListener { startActivity(Intent(this, BrandGuideActivity::class.java)) }
        b.btnWirelessDebug.setOnClickListener { startActivity(Intent(this, WirelessDebugActivity::class.java)) }

        b.btnRestore.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("还原说明")
                .setMessage(
                    "1. 挂起的广告包：进「增强模式」点全部还原。\n" +
                        "2. 应用内开关：打开「图文使用引导」选你的品牌，把清单里关掉的开关重新打开。\n" +
                        "3. 还原不影响系统更新，也不会卸载任何应用。"
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        b.btnVoice.setOnClickListener {
            voiceOn = !voiceOn
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_VOICE, voiceOn).apply()
            refreshVoiceButton()
            if (!voiceOn) tts?.stop()
        }

        b.btnToggleLogs.setOnClickListener {
            val show = b.scrollLogs.visibility != View.VISIBLE
            b.scrollLogs.visibility = if (show) View.VISIBLE else View.GONE
            b.btnToggleLogs.text = getString(if (show) R.string.btn_hide_logs else R.string.btn_show_logs)
        }
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_stop_clean))
            .setPositiveButton("确定停止") { _, _ ->
                CleanOrchestrator.cancel()
                speak(getString(R.string.tts_aborted))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 无障碍权限：ADB 自动开优先，失败引导手动 ----------

    /**
     * 用调试权限（无线 ADB）直接改系统设置打开无障碍——老人零操作。
     * 没连过 ADB 或连接失败 → 跳系统设置并弹大字分步说明。
     */
    private fun smartEnableAccessibility(andStart: Boolean) {
        if (AdCleanerService.isEnabled(this)) {
            refreshAccessibility()
            Toast.makeText(this, "权限已经是打开的", Toast.LENGTH_SHORT).show()
            return
        }
        if (autoEnabling) return
        autoEnabling = true
        b.btnEnableAccessibility.isEnabled = false
        b.btnEnableAccessibility.text = "正在帮您打开…"

        lifecycleScope.launch {
            val port = AdbAutomation.resolvePort(this@MainActivity)
            var ok = false
            val mgr = SuspendManager(this@MainActivity)
            try {
                ok = AdbAutomation.enableAccessibility(this@MainActivity, mgr, port).isSuccess
            } finally {
                mgr.disconnect()
            }
            autoEnabling = false
            b.btnEnableAccessibility.isEnabled = true
            b.btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility_elder)

            if (ok && AdCleanerService.isEnabled(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.toast_accessibility_auto_on, Toast.LENGTH_LONG).show()
                speak("权限帮您打开好了")
                refreshAccessibility()
                if (andStart) startClean()
            } else {
                Toast.makeText(this@MainActivity, R.string.toast_accessibility_auto_fail, Toast.LENGTH_LONG).show()
                showManualGuideDialog()
            }
        }
    }

    private fun showManualGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_manual_title))
            .setMessage(getString(R.string.dlg_manual_msg))
            .setPositiveButton(getString(R.string.dlg_go_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 一键清理：权限没开就先自动开，然后直接跑 ----------

    private fun ensureAccessibilityThenStart() {
        if (AdCleanerService.isEnabled(this)) {
            startClean()
        } else {
            smartEnableAccessibility(andStart = true)
        }
    }

    private fun startClean() {
        val started = CleanOrchestrator.start(this, includeAdb = false)
        if (started) {
            b.cardDone.visibility = View.GONE
            b.layoutProgress.visibility = View.VISIBLE
            speak(getString(R.string.tts_started))
            // 增强通道：顺手把品牌规则里的广告包也冻结（连不上就静默跳过，不影响主流程）
            lifecycleScope.launch { suspendAdPackagesViaAdb() }
        }
    }

    /**
     * 后台 ADB 冻结广告包：连解析端口（上次端口或 5555）→ 按品牌规则逐个 `pm suspend`。
     * 只在「一键清理」时跟着跑一次；没连过 ADB / 连不上都静默跳过，
     * 老人主流程（无障碍点开关）不受任何影响。
     */
    private suspend fun suspendAdPackagesViaAdb() {
        val port = AdbAutomation.resolvePort(this)
        val brand = BrandDetector.detect().brand
        val rule = com.adcleaner.app.rules.RuleLoader.match(this, brand) ?: return
        val targets = rule.adPackages.filter { !it.neverSuspend }
        if (targets.isEmpty()) return

        val mgr = SuspendManager(this)
        try {
            val r = mgr.connect(port)
            if (r.isFailure) return
            CleanState.log("\n── 冻结广告组件（调试权限）──")
            var ok = 0
            for (p in targets) {
                val res = mgr.suspendPackage(p.pkg)
                if (res.isSuccess) {
                    ok++
                    CleanState.ok("已冻结 ${p.name}")
                } else {
                    CleanState.warn("冻结 ${p.name} 未成功（可稍后在深度清理页重试）")
                }
            }
            if (ok > 0) CleanState.addClosed(ok)
        } finally {
            mgr.disconnect()
        }
    }

    // ---------- 状态刷新 ----------

    private fun observeCleanState() {
        lifecycleScope.launch {
            CleanState.ui.collect { ui ->
                val prev = lastUi
                lastUi = ui
                renderProgress(ui)
                renderPhases(ui, prev)
            }
        }
    }

    private fun renderProgress(ui: CleanState.Ui) {
        // 只在真正清理（跑过段/进行中/已完成）时显示进度卡：
        // 服务绑定等后台日志不该把一张空进度卡顶到老人脸上
        b.layoutProgress.visibility =
            if (ui.running || ui.done || ui.section.isNotEmpty()) View.VISIBLE else View.GONE

        b.tvProgress.text = if (ui.total > 0)
            "${ui.section}（${ui.progress}/${ui.total}）" else ui.section
        b.progressBar.max = ui.total.coerceAtLeast(1)
        b.progressBar.setProgressCompat(ui.progress.coerceIn(0, ui.total.coerceAtLeast(1)), true)
        b.tvCurrentAction.text = ui.lastStep

        val prevLog = b.tvLogs.text.toString()
        b.tvLogs.text = ui.log
        if (prevLog != ui.log && b.scrollLogs.visibility == View.VISIBLE) {
            b.scrollLogs.post { b.scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }

        // 主按钮随状态切换：清理中 → 红色「停止清理」
        if (ui.running) {
            b.btnStartClean.text = getString(R.string.btn_start_clean_running)
            b.btnStartClean.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.danger_red))
            )
        } else {
            b.btnStartClean.text = getString(R.string.btn_start_clean)
            b.btnStartClean.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.m3_primary))
            )
        }
    }

    private fun renderPhases(ui: CleanState.Ui, prev: CleanState.Ui) {
        // 完成瞬间：大绿卡 + 语音
        if (!prev.done && ui.done) {
            b.cardDone.visibility = View.VISIBLE
            b.tvDoneDetail.text = if (ui.closedCount > 0)
                getString(R.string.tv_done_detail_fmt, ui.closedCount)
            else getString(R.string.tv_done_detail_zero)
            speak(
                if (ui.closedCount > 0) getString(R.string.tts_done_fmt, ui.closedCount)
                else getString(R.string.tts_done_zero)
            )
        }
        if (prev.done && !ui.done) b.cardDone.visibility = View.GONE

        refreshAccessibility()
        refreshSteps(ui)
    }

    private fun refreshAccessibility() {
        val on = AdCleanerService.isEnabled(this)
        b.tvAccessibility.text = getString(
            if (on) R.string.tv_accessibility_on_elder else R.string.tv_accessibility_off_elder
        )
        b.tvAccessibility.setTextColor(getColor(if (on) R.color.safe_green else R.color.danger_red))
        b.tvAccessibility.setBackgroundResource(if (on) R.drawable.bg_status_safe else R.drawable.bg_status_danger)
        val tint = getColor(if (on) R.color.safe_green else R.color.danger_red)
        b.tvAccessibility.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tint)
        b.btnEnableAccessibility.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** 三步条高亮当前阶段。 */
    private fun refreshSteps(ui: CleanState.Ui) {
        val accOn = AdCleanerService.isEnabled(this)
        val (s1Bg, s2Bg, s3Bg) = when {
            ui.done -> Triple(R.drawable.bg_step_done, R.drawable.bg_step_done, R.drawable.bg_step_done)
            ui.running -> Triple(R.drawable.bg_step_done, R.drawable.bg_step_active, R.drawable.bg_step_inactive)
            accOn -> Triple(R.drawable.bg_step_done, R.drawable.bg_step_active, R.drawable.bg_step_inactive)
            else -> Triple(R.drawable.bg_step_active, R.drawable.bg_step_inactive, R.drawable.bg_step_inactive)
        }
        val (s1Fg, s2Fg, s3Fg) = when {
            ui.done -> Triple(white(), white(), white())
            ui.running -> Triple(white(), white(), gray())
            accOn -> Triple(white(), white(), gray())
            else -> Triple(white(), gray(), gray())
        }
        b.step1.setBackgroundResource(s1Bg); b.step1.setTextColor(s1Fg)
        b.step2.setBackgroundResource(s2Bg); b.step2.setTextColor(s2Fg)
        b.step3.setBackgroundResource(s3Bg); b.step3.setTextColor(s3Fg)
    }

    private fun white() = getColor(R.color.white)
    private fun gray() = getColor(R.color.m3_onSurfaceVariant)

    companion object {
        private const val PREFS = "adcleaner_prefs"
        private const val KEY_VOICE = "voice_on"
    }
}
