package com.adcleaner.app.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * libadb-android 的接入层：把 app 现有的 RSA 密钥对与自签证书交给库。
 *
 * 协议细节（SPAKE2 配对、A_STLS 升级、AUTH 签名、CRC 兼容、legacy shell）
 * 全部由库处理——这是 Shizuku 同款实现路线，适配各类国产 ROM。
 * 密钥/证书沿用 [AdbKeyManager]/[AdbCertManager]（配对过的设备已记录此公钥）。
 *
 * 每次连接 new 一个实例，用完 [disconnect]；不要长期持有（库内部有静态 TLS 缓存）。
 */
class AdbLibManager(context: Context, connectTimeoutSeconds: Long = 15) : AbsAdbConnectionManager() {

    private val appContext = context.applicationContext

    init {
        setHostAddress("127.0.0.1")
        setApi(Build.VERSION.SDK_INT)
        // 连接超时；授权弹窗由无障碍引擎自动点「允许」后重连即可
        setTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        setThrowOnUnauthorised(false)
    }

    override fun getPrivateKey(): PrivateKey = AdbCertManager.getPrivateKey(appContext)

    override fun getCertificate(): Certificate = AdbCertManager.getCertificate(appContext)

    override fun getDeviceName(): String = "AdCleaner"
}
