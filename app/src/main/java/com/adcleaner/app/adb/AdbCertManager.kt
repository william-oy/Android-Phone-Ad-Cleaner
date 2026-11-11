package com.adcleaner.app.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 无线调试 TLS 用的自签证书：由 AdbKeyManager 的 RSA 密钥派生，
 * 配对与连接必须使用同一张证书（设备在配对时记录客户端证书）。
 * 证书 PEM 持久化在 prefs，与密钥对同生命周期。
 */
object AdbCertManager {

    private const val KEY_CERT = "tls_cert_pem"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("adb_keys", Context.MODE_PRIVATE)

    /** 生成（或取缓存）自签证书；过期则自动重新生成。 */
    fun getCertificate(ctx: Context): X509Certificate {
        val cached = prefs(ctx).getString(KEY_CERT, null)
        if (cached != null) {
            try {
                val pem = cached.trim()
                val b64 = pem.lines()
                    .filter { !it.startsWith("-----") }
                    .joinToString("")
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(Base64.decode(b64, Base64.NO_WRAP).inputStream()) as X509Certificate
                // 过期检查：有效期剩余不足 7 天则重新生成
                if (cert.notAfter.time > System.currentTimeMillis() + 7L * 24 * 3600 * 1000) {
                    return cert
                }
                Log.i("AdbCert", "证书即将过期或已过期，重新生成")
            } catch (_: Exception) {
                // 解析失败则重新生成
            }
        }
        val cert = generate(ctx)
        prefs(ctx).edit().putString(KEY_CERT, cert).apply()
        return loadPem(cert)
    }

    private fun generate(ctx: Context): String {
        val km = AdbKeyManager(ctx)
        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(km.getPrivateKeyPkcs8())
        ) as java.security.interfaces.RSAPrivateCrtKey
        val pub = kf.generatePublic(java.security.spec.RSAPublicKeySpec(priv.modulus, priv.publicExponent))

        val name = X500Name("CN=adbpair")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            Date(now - 60_000L),
            Date(now + 365L * 24 * 3600 * 1000),
            name,
            pub
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(priv)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return pemEncode(cert)
    }

    private fun pemEncode(cert: X509Certificate): String {
        val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        val sb = StringBuilder()
        sb.append("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 64, b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }

    private fun loadPem(pem: String): X509Certificate {
        val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.decode(b64, Base64.NO_WRAP).inputStream()) as X509Certificate
    }

    /** Java 私钥（TLS 握手用）。 */
    fun getPrivateKey(ctx: Context): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(AdbKeyManager(ctx).getPrivateKeyPkcs8())
        )

    /** JSSE 用的内存 KeyStore（证书+私钥）。 */
    fun buildKeyStore(ctx: Context): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("adbpair", getPrivateKey(ctx), CharArray(0), arrayOf(getCertificate(ctx)))
        return ks
    }
}
