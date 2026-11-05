package com.adcleaner.app.guide

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.adcleaner.app.databinding.ActivityBrandGuideBinding

/** 品牌图文引导：选品牌 → 加载对应 html。 */
class BrandGuideActivity : AppCompatActivity() {

    private val brands = listOf(
        "小米 / HyperOS" to "xiaomi",
        "OPPO / 一加 / realme（ColorOS）" to "oppo",
        "vivo / iQOO（OriginOS）" to "vivo",
        "荣耀（MagicOS）" to "honor",
        "华为（HarmonyOS）" to "huawei",
        "魅族（Flyme）" to "meizu",
        "增强模式：无线调试挂起广告包" to "wireless",
        "通用：开始前的准备" to "index"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityBrandGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, brands.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.spinnerBrand.adapter = adapter

        b.spinnerBrand.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val key = brands[position].second
                b.webGuide.settings.javaScriptEnabled = false
                b.webGuide.loadUrl("file:///android_asset/guides/$key.html")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
