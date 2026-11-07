package com.adcleaner.app.adb

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * 管理 ADB RSA 密钥对：首次生成 2048 位 RSA（公钥指数 e=65537），持久化到 SharedPreferences。
 *
 * 发给 adbd 的公钥采用 Android 自定义 [RSAPublicKey] 二进制结构（**非**标准 DER/PEM，
 * 也**非** X.509 SubjectPublicKeyInfo），base64 编码后追加 '\0'。结构定义见 AOSP
 * `libcrypto_utils/android_pubkey.cpp`：
 *
 * ```
 * uint32 modulus_size_words   // 模数长度（32 位字数），2048 位 => 64
 * uint32 n0inv                // Montgomery 参数：-1 / n[0] mod 2^32
 * uint8  modulus[256]         // RSA 模数 n，小端字节序
 * uint8  rr[256]              // Montgomery 参数 R^2 mod n，小端字节序（R = 2^2048）
 * uint32 exponent             // 公钥指数 e（65537）
 * ```
 *
 * 之所以不用 `publicKey.encoded`（X.509 SubjectPublicKeyInfo）：adbd 的 pubkey 解析器
 * 只认上面这个自定义结构，发 SubjectPublicKeyInfo 会导致授权静默失败。
 */
class AdbKeyManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 首次访问时加载或生成。放在 `by lazy` 里，确保只在 IO 协程里（连接握手时）
     * 才做相对耗时的 RSA 生成，不在主线程执行。
     */
    private val keyPair: KeyPair by lazy { load() ?: generate() }

    /**
     * 返回发给 adbd 的 AUTH(RSAPUBKEY) 载荷：`base64(android_pubkey 结构) + '\0'`。
     * 调用方在 [AdbClient] 的 IO 协程里使用。
     */
    fun getPublicKeyAdbPayload(): ByteArray {
        val pub = keyPair.public as? RSAPublicKey
            ?: error("ADB 密钥未正确初始化")
        val blob = encodeAndroidPubkey(pub.modulus, pub.publicExponent)
        val b64 = Base64.encodeToString(blob, Base64.NO_WRAP)
        return (b64 + "\u0000").toByteArray(Charsets.US_ASCII)
    }

    /**
     * 配对协议用的公钥：标准 X.509 SubjectPublicKeyInfo（= `RSAPublicKey.encoded`）。
     * 无线调试配对（Android 11+）的 `adb pair` 上送的就是这个 SPKI 结构，
     * 与 AUTH 用的自定义 android_pubkey 结构不同。
     */
    fun getPublicKeySpki(): ByteArray =
        (keyPair.public as? RSAPublicKey)?.encoded
            ?: error("ADB 密钥未正确初始化")

    /**
     * android_pubkey 的 base64 行（不带 name）：写入 PeerInfo 发给设备，
     * 设备会把它原样写进 adb_keys，之后的 AUTH 握手即自动通过。
     */
    fun getPublicKeyLine(): String {
        val pub = keyPair.public as? RSAPublicKey
            ?: error("ADB 密钥未正确初始化")
        val blob = encodeAndroidPubkey(pub.modulus, pub.publicExponent)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /** PKCS#8 私钥 DER（自签证书与 TLS 用）。 */
    fun getPrivateKeyPkcs8(): ByteArray =
        keyPair.private.encoded

    private fun load(): KeyPair? {
        val privB64 = prefs.getString(KEY_PRIV, null) ?: return null
        val pubB64 = prefs.getString(KEY_PUB, null) ?: return null
        return try {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
            KeyPair(pub, priv)
        } catch (e: Exception) {
            // 存储损坏则丢弃，重新生成
            null
        }
    }

    private fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        // 显式指定 2048 位 + F4(65537)，保证与 adbd 期望的指数一致
        gen.initialize(RSAKeyGenParameterSpec(KEY_SIZE, RSAKeyGenParameterSpec.F4))
        val kp = gen.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    companion object {
        private const val PREFS_NAME = "adb_keys"
        private const val KEY_PRIV = "rsa_private_pkcs8"
        private const val KEY_PUB = "rsa_public_x509"
        private const val KEY_SIZE = 2048
        private const val MODULUS_BYTES = KEY_SIZE / 8          // 256
        private const val MODULUS_SIZE_WORDS = KEY_SIZE / 32    // 64

        /**
         * 按 `android_pubkey.cpp` 的 RSAPublicKey 结构编码为 524 字节：
         * `4 + 4 + 256 + 256 + 4`。
         *
         * @param n RSA 模数（必须正好 2048 位）
         * @param e RSA 公钥指数（65537）
         */
        fun encodeAndroidPubkey(n: BigInteger, e: BigInteger): ByteArray {
            require(n.bitLength() == KEY_SIZE) {
                "仅支持 ${KEY_SIZE} 位 RSA 模数，当前为 ${n.bitLength()} 位"
            }
            val out = ByteArray(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4)
            var p = 0

            // modulus_size_words (LE u32) = 64
            p = writeLe32(out, p, MODULUS_SIZE_WORDS)

            // n0inv = -1 / n[0] mod 2^32，其中 n[0] 是模数的最低 32 位
            val mod2_32 = BigInteger.ONE.shiftLeft(32)          // 2^32
            val n0 = n.mod(mod2_32)                             // n 的最低 32 位（必为奇数）
            val inv = n0.modInverse(mod2_32)                    // n[0]^-1 mod 2^32
            val n0inv = mod2_32.subtract(inv).mod(mod2_32)      // -inv mod 2^32
            p = writeLe32(out, p, n0inv)

            // modulus：256 字节小端
            p = writeFixed(out, p, toUnsignedLe(n, MODULUS_BYTES))

            // rr = R^2 mod n，R = 2^2048（256 字节小端）
            val r = BigInteger.ONE.shiftLeft(KEY_SIZE)          // 2^2048
            val rr = r.multiply(r).mod(n)                       // 2^4096 mod n
            p = writeFixed(out, p, toUnsignedLe(rr, MODULUS_BYTES))

            // exponent (LE u32)
            p = writeLe32(out, p, e)

            check(p == out.size) { "编码长度不匹配：$p / ${out.size}" }
            return out
        }

        /**
         * 把 [BigInteger] 转成固定 [length] 字节的小端无符号数组。
         * `BigInteger.toByteArray()` 返回大端、带符号（正数且最高位为 1 时会多一个 0x00 前缀），
         * 这里统一左侧补零到大端 [length] 字节后再反转成小端。
         */
        private fun toUnsignedLe(value: BigInteger, length: Int): ByteArray {
            val be = value.toByteArray()
            // 去掉可能的前导符号字节 0x00（仅当它使长度超出时才需要；不超时保留也无害，等价于前导零）
            val mag = if (be.isNotEmpty() && be[0] == 0.toByte() && be.size > length) {
                be.copyOfRange(1, be.size)
            } else {
                be
            }
            require(mag.size <= length) { "数值过长：${mag.size} > $length 字节" }
            val padded = ByteArray(length)
            System.arraycopy(mag, 0, padded, length - mag.size, mag.size) // 大端左对齐补零
            val le = ByteArray(length)
            for (i in 0 until length) le[i] = padded[length - 1 - i]
            return le
        }

        private fun writeFixed(buf: ByteArray, offset: Int, data: ByteArray): Int {
            System.arraycopy(data, 0, buf, offset, data.size)
            return offset + data.size
        }

        private fun writeLe32(buf: ByteArray, offset: Int, value: Int): Int {
            buf[offset] = value.toByte()
            buf[offset + 1] = (value ushr 8).toByte()
            buf[offset + 2] = (value ushr 16).toByte()
            buf[offset + 3] = (value ushr 24).toByte()
            return offset + 4
        }

        private fun writeLe32(buf: ByteArray, offset: Int, value: BigInteger): Int =
            writeLe32(buf, offset, value.toInt())
    }
}
