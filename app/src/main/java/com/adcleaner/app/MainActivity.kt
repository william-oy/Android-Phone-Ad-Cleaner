package com.adcleaner.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adcleaner.app.adb.WirelessDebugActivity
import com.adcleaner.app.cleaner.AdCleanerService
import com.adcleaner.app.cleaner.BrandDetector
import com.adcleaner.app.cleaner.CleanOrchestrator
import com.adcleaner.app.cleaner.CleanState
import com.adcleaner.app.databinding.ActivityMainBinding
import com.adcleaner.app.guide.BrandGuideActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val info = BrandDetector.detect()
        b.tvBrand.text = if (info.brand == "unknown") getString(R.string.tv_brand_unknown)
        else "你的手机：${info.display}"
        b.tvRomInfo.text = "型号 ${info.model} · Android ${info.android} · ${info.rom}"

        b.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.btnStartClean.setOnClickListener {
            if (!AdCleanerService.isEnabled(this)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            CleanOrchestrator.start(this, includeAdb = false)
        }

        b.btnRestore.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("还原说明")
                .setMessage(
                    "1. 挂起的广告包：进「增强模式」点还原（unsuspend）。\n" +
                        "2. 应用内开关：打开「图文使用引导」选你的品牌，把清单里关掉的开关重新打开。\n" +
                        "3. 还原不影响系统更新，也不会卸载任何应用。"
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        b.btnWirelessDebug.setOnClickListener {
            startActivity(Intent(this, WirelessDebugActivity::class.java))
        }

        b.btnGuide.setOnClickListener {
            startActivity(Intent(this, BrandGuideActivity::class.java))
        }

        lifecycleScope.launch {
            CleanState.ui.collect { ui ->
                b.tvProgress.text = if (ui.total > 0)
                    "${ui.section}（${ui.progress}/${ui.total}）" else ui.section
                b.progressBar.max = ui.total.coerceAtLeast(1)
                b.progressBar.progress = ui.progress
                // 日志有新内容才自动滚到底：不打断手动翻阅，又能看到最新输出
                val prev = b.tvLogs.text.toString()
                b.tvLogs.text = ui.log
                b.layoutProgress.visibility =
                    if (ui.log.isNotEmpty() || ui.running) View.VISIBLE else View.GONE
                if (prev != ui.log) b.scrollLogs.post {
                    b.scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibility()
    }

    private fun refreshAccessibility() {
        val on = AdCleanerService.isEnabled(this)
        b.tvAccessibility.text = getString(if (on) R.string.tv_accessibility_on else R.string.tv_accessibility_off)
        b.tvAccessibility.setTextColor(getColor(if (on) R.color.safe_green else R.color.danger_red))
        b.tvAccessibility.setBackgroundResource(if (on) R.drawable.bg_status_safe else R.drawable.bg_status_danger)
        val tint = getColor(if (on) R.color.safe_green else R.color.danger_red)
        b.tvAccessibility.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tint)
    }
}
