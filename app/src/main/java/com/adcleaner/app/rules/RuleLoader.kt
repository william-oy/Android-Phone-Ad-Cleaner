package com.adcleaner.app.rules

import android.content.Context
import com.adcleaner.app.model.BrandRule
import kotlinx.serialization.json.Json

/** 从 assets/rules 目录的 JSON 文件加载品牌规则。 */
object RuleLoader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val BRAND_FILES = listOf("xiaomi", "oppo", "vivo", "honor", "huawei", "meizu", "samsung")

    fun loadAll(ctx: Context): List<BrandRule> {
        val out = ArrayList<BrandRule>()
        for (f in BRAND_FILES) {
            runCatching {
                val text = ctx.assets.open("rules/$f.json").bufferedReader().use { it.readText() }
                out.add(json.decodeFromString<BrandRule>(text))
            }.onFailure {
                // 加载失败不致命，跳过该品牌
            }
        }
        return out
    }

    fun match(ctx: Context, brandId: String): BrandRule? =
        loadAll(ctx).firstOrNull { it.brand == brandId }
}
