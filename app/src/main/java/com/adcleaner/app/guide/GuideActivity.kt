package com.adcleaner.app.guide

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.adcleaner.app.databinding.ActivityGuideBinding

/** 图文引导页：WebView 加载 assets/guides/*.html。 */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(com.adcleaner.app.R.string.guide_title)
        b.toolbar.setNavigationOnClickListener { finish() }
        val asset = intent.getStringExtra("asset") ?: "guides/index.html"
        b.webGuide.settings.javaScriptEnabled = false
        b.webGuide.loadUrl("file:///android_asset/$asset")
    }
}
