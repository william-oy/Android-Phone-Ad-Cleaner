package com.adcleaner.app.adb

import android.util.Log
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Base64 as JBase64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 无线调试「配对码」配对协议客户端（Android 11+）。
 *
 * 协议 = AOSP packages/modules/adb：
 *  1. 配对端口直接进行 TLS 1.3 握手（双方各持证书，配对阶段校验放行）
 *  2. TLS-Exporter（label "adb-label\0"，无 context）导出 64 字节，拼到配对码后面作为 SPAKE2 口令
 *  3. PairingPacket（version=1, type, payload 大端长度）在 TLS 内交换 SPAKE2 消息
 *  4. HKDF-SHA256 派生 AES-128-GCM 密钥，加密交换 PeerInfo（8192 字节定长：type + 公钥行）
 *
 * 实现先在 Python 原型（scratch/pair_proto.py）上真机验证通过后移植。
 */
object AdbPairing {

    private const val TAG = "AdbPairing"
    private const val PA_SPAKE2_MSG = 0
    private const val PA_PEER_INFO = 1
    private const val MAX_PAYLOAD = 8192 * 2

    /**
     * 执行配对，成功返回设备 GUID。
     * @param host 配对服务 IP
     * @param port 配对端口（手机「使用配对码配对设备」页显示的端口）
     * @param pairingCode 6 位配对码（ASCII）
     */
    suspend fun pair(
        ctx: android.content.Context,
        host: String,
        port: Int,
        pairingCode: String,
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val km = AdbKeyManager(ctx)
        val cert = AdbCertManager.getCertificate(ctx)
        val priv = AdbCertManager.getPrivateKey(ctx)

        val socket = java.net.Socket()
        socket.tcpNoDelay = true
        socket.connect(java.net.InetSocketAddress(host, port), 8000)
        socket.soTimeout = 15000

        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        val tlsClient = PairingTlsClient(priv, cert)
        try {
            kotlinx.coroutines.withTimeoutOrNull(25000) { protocol.connect(tlsClient) }
                ?: throw IllegalStateException("TLS1.3 握手超时（25s）——BC 与 BoringSSL 协商卡住")
            Log.i(TAG, "TLS 握手成功")
        } catch (e: IllegalStateException) {
            socket.close(); throw e
        } catch (e: Exception) {
            socket.close()
            throw IllegalStateException("TLS 握手失败：${e.message}")
        }

        // TLS-Exporter：BoringSSL 的 label 含 '\0'，无 context
        val exported = tlsClient.exportKey("adb-label\u0000", 64) ?: ByteArray(0)
        Log.i(TAG, "exporter=${exported.size}B ${exported.take(8).joinToString("") { "%02x".format(it) }}")
        val fullPswd = pairingCode.toByteArray(Charsets.US_ASCII) + exported

        // ---- PairingPacket 交换 ----
        val inp: InputStream = protocol.inputStream
        val out: OutputStream = protocol.outputStream

        val spake = Spake2.client(fullPswd)
        Log.i(TAG, "my spake2 msg=${spake.myMsg.joinToString("") { "%02x".format(it) }}")
        writePacket(out, PA_SPAKE2_MSG, spake.myMsg)
        val (theirType, theirMsg) = readPacket(inp)
        check(theirType == PA_SPAKE2_MSG) { "期望 SPAKE2_MSG，收到 type=$theirType" }
        Log.i(TAG, "their spake2 msg=${theirMsg.joinToString("") { "%02x".format(it) }}")
        val keyMaterial = spake.process(theirMsg)
        Log.i(TAG, "spake2 key=${keyMaterial.take(8).joinToString("") { "%02x".format(it) }}…")

        // ---- 加密交换 PeerInfo ----
        val gcmKey = deriveGcmKey(keyMaterial)
        val peerInfo = ByteArray(8192)
        peerInfo[0] = 0  // ADB_RSA_PUB_KEY
        val line = (km.getPublicKeyLine() + " adbpair@adcleaner").toByteArray(Charsets.US_ASCII)
        System.arraycopy(line, 0, peerInfo, 1, minOf(line.size, 8191))
        val ct = gcmSeal(gcmKey, 0, peerInfo)
        writePacket(out, PA_PEER_INFO, ct)

        val (respType, respCt) = readPacket(inp)
        check(respType == PA_PEER_INFO) { "期望 PEER_INFO，收到 type=$respType" }
        val theirInfo = try {
            gcmOpen(gcmKey, 0, respCt)
        } catch (e: Exception) {
            throw IllegalStateException("解密对端信息失败（配对码不符或 SPAKE2 实现差异）：${e.javaClass.simpleName}")
        }

        // 设备侧 PeerInfo：type=1(ADB_DEVICE_GUID)，data 为 GUID 字符串
        val guid = theirInfo.drop(1).takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.UTF_8)
        Log.i(TAG, "配对成功，设备 GUID=$guid")
        socket.close()
        guid
    }

    /** 配对专用 TLS 客户端：TLS1.3、客户端证书、服务端证书全放行。 */
    private class PairingTlsClient(
        private val clientPriv: PrivateKey,
        private val clientCert: java.security.cert.X509Certificate,
    ) : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {

        /** 公开访问器：在子类内部读 protected 的 context 没问题。 */
        fun cryptoParameters(): org.bouncycastle.tls.crypto.TlsCryptoParameters =
            org.bouncycastle.tls.crypto.TlsCryptoParameters(context)

        /** RFC 5705/8446 exporter（label 含 NUL、无 context，与 BoringSSL 一致）。 */
        fun exportKey(label: String, length: Int): ByteArray? =
            context.exportKeyingMaterial(label, null, length)

        override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
                // 配对阶段不校验服务端证书（对齐 AOSP：verify callback 全放行）
                Log.d(TAG, "server cert accepted: ${serverCertificate != null}")
            }

            override fun getClientCredentials(request: org.bouncycastle.tls.CertificateRequest): TlsCredentials {
                android.util.Log.i(TAG, "getClientCredentials: 收到证书请求 sigAlgs=${request.supportedSignatureAlgorithms?.size}")
                val sigAlg = org.bouncycastle.tls.SignatureAndHashAlgorithm(
                    org.bouncycastle.tls.HashAlgorithm.sha256,
                    org.bouncycastle.tls.SignatureAlgorithm.rsa_pss_rsae_sha256
                )
                val cred = makeClientSigner(clientPriv, clientCert, cryptoParameters(), sigAlg)
                android.util.Log.i(TAG, "getClientCredentials: 完成 -> ${cred.javaClass.simpleName}")
                return cred
            }
        }
    }

    private fun makeClientSigner(
        priv: PrivateKey,
        cert: java.security.cert.X509Certificate,
        cryptoParams: org.bouncycastle.tls.crypto.TlsCryptoParameters,
        sigAlg: org.bouncycastle.tls.SignatureAndHashAlgorithm,
    ): TlsCredentials {
        val kf = KeyFactory.getInstance("RSA")
        val crt = kf.getKeySpec(priv, java.security.spec.RSAPrivateCrtKeySpec::class.java)
        val params = RSAPrivateCrtKeyParameters(
            crt.modulus, crt.publicExponent, crt.privateExponent,
            crt.primeP, crt.primeQ, crt.primeExponentP, crt.primeExponentQ, crt.crtCoefficient
        )
        val crypto = BcTlsCrypto(SecureRandom())
        val tlsCert = BcTlsCertificate(crypto, cert.encoded)
        return BcDefaultTlsCredentialedSigner(
            cryptoParams,
            crypto,
            params,
            Certificate(arrayOf(tlsCert)),
            sigAlg
        )
    }

    // ---------------- PairingPacket ----------------

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = byteArrayOf(1.toByte(), type.toByte(),
            (payload.size shr 24).toByte(), (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(), payload.size.toByte())
        out.write(header)
        out.write(payload)
        out.flush()
    }

    private fun readPacket(inp: InputStream): Pair<Int, ByteArray> {
        val header = readFully(inp, 6)
        check(header[0].toInt() == 1) { "packet version ${header[0]}" }
        val type = header[1].toInt() and 0xff
        val len = ((header[2].toInt() and 0xff) shl 24) or ((header[3].toInt() and 0xff) shl 16) or
            ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
        check(len > 0 && len <= MAX_PAYLOAD) { "payload 长度异常: $len" }
        return type to readFully(inp, len)
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            check(r > 0) { "对端提前关闭（已收 $off/$n）" }
            off += r
        }
        return buf
    }

    // ---------------- AES-128-GCM（对应 aes_128_gcm.cpp） ----------------

    private fun deriveGcmKey(keyMaterial: ByteArray): ByteArray {
        // HKDF-SHA256(salt=null, ikm=keyMaterial, info="adb pairing_auth aes-128-gcm key", L=16)
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        val prk = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            doFinal(keyMaterial)
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOf(16)
    }

    /** nonce = 12 字节：前 8 字节为 LE64 序号，其余 0（BoringSSL memcpy 语义）。 */
    private fun nonceFor(seq: Long): ByteArray {
        val n = ByteArray(12)
        var v = seq
        for (i in 0 until 8) {
            n[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        return n
    }

    private fun gcmSeal(key: ByteArray, seq: Long, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(seq)))
        return c.doFinal(plain)
    }

    private fun gcmOpen(key: ByteArray, seq: Long, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(seq)))
        return c.doFinal(ct)
    }
}
