package com.adcleaner.app.cleaner

import android.os.Build

/** 检测本机品牌 / ROM / 型号，用于匹配品牌规则。 */
object BrandDetector {

    data class Info(
        val brand: String,        // xiaomi / oppo / vivo / honor / huawei / meizu / unknown
        val display: String,      // 给用户看的品牌名
        val rom: String,          // Build.DISPLAY
        val model: String,        // Build.MODEL
        val android: Int          // SDK_INT
    )

    fun detect(): Info {
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val (b, disp) = when {
            mfr.contains("xiaomi") || brand.contains("redmi") || brand.contains("xiaomi") ||
                mfr.contains("redmi") -> "xiaomi" to "小米 / 红米"
            mfr.contains("oppo") || brand.contains("oppo") || mfr.contains("oneplus") ||
                brand.contains("oneplus") || mfr.contains("realme") || brand.contains("realme") -> "oppo" to "OPPO / 一加 / realme"
            mfr.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") -> "vivo" to "vivo / iQOO"
            mfr.contains("honor") || brand.contains("honor") -> "honor" to "荣耀"
            mfr.contains("huawei") || brand.contains("huawei") -> "huawei" to "华为"
            mfr.contains("meizu") || brand.contains("meizu") -> "meizu" to "魅族"
            else -> "unknown" to "未识别"
        }
        return Info(b, disp, Build.DISPLAY ?: "", Build.MODEL ?: "", Build.VERSION.SDK_INT)
    }
}
