package com.adcleaner.app.adb

import android.util.Log
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.RSAPrivateCrtKeySpec
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
 * TLS 层两条路径：JSSE(Conscrypt)+反射 exporter 优先，失败回退 BouncyCastle TLS。
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

        // 路径A：JSSE(Conscrypt) TLS + 反射 exporter；不可用再走路径B（BC TLS）
        val jsse = runCatching { jsseHandshake(socket, host, port, ctx) }.getOrNull()
        if (jsse != null) {
            Log.i(TAG, "路径A（JSSE）：握手成功，exporter=${jsse.exported.size}B")
            val fullPswd = pairingCode.toByteArray(Charsets.US_ASCII) + jsse.exported
            val guid = exchangePairingPackets(jsse.ssl.inputStream, jsse.ssl.outputStream, km, fullPswd)
            jsse.ssl.close()
            guid
        } else {
            Log.i(TAG, "路径A不可用，走路径B（BC TLS）")
            socket.close()
            pairBcTls(ctx, host, port, pairingCode, priv, cert, km)
        }
    }

    // ---------------- 路径A：JSSE(Conscrypt) ----------------

    class JsseSession(val ssl: javax.net.ssl.SSLSocket, val exported: ByteArray)

    /** JSSE TLS 握手（客户端证书 + 信任全放行）+ 反射探测 Conscrypt exporter。 */
    private fun jsseHandshake(
        raw: java.net.Socket,
        host: String,
        port: Int,
        ctx: android.content.Context,
    ): JsseSession {
        val ks = AdbCertManager.buildKeyStore(ctx)
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
        sslCtx.init(kmf.keyManagers, trustAll, null)
        val ssl = sslCtx.socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        ssl.startHandshake()
        ssl.soTimeout = 15000
        Log.i(TAG, "JSSE 握手成功 ${ssl.session.protocol} ${ssl.session.cipherSuite}")

        // 反射探测 Conscrypt 的 exportKeyingMaterial（隐藏 API，各版本签名不一）
        val m = ssl.javaClass.methods.firstOrNull { it.name == "exportKeyingMaterial" }
            ?: ssl.javaClass.methods.firstOrNull { it.name.contains("xport") }
        if (m == null) {
            val names = ssl.javaClass.methods.map { it.name }.filter { it.contains("xport") || it.contains("Keying") }
            throw IllegalStateException("Conscrypt 无 exporter API（候选=$names）")
        }
        Log.i(TAG, "exporter 方法: ${m.name}(${m.parameterTypes.joinToString()})")
        val exported: ByteArray = when (m.parameterTypes.size) {
            3 -> {
                // (label: String, length: Int, context: bytes) 或 (bytes, len, ctx) 两种顺序都试
                runCatching { m.invoke(ssl, "adb-label\u0000", 64, null) as? ByteArray }
                    .getOrElse { m.invoke(ssl, b("adb-label\u0000"), 64) as? ByteArray }
            }
            2 -> m.invoke(ssl, b("adb-label\u0000"), 64) as? ByteArray
            else -> null
        } ?: throw IllegalStateException("exporter 调用返回空")
        if (exported.size != 64) throw IllegalStateException("exporter 长度异常: ${exported.size}")
        return JsseSession(ssl, exported)
    }

    private fun b(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    // ---------------- 路径B：BouncyCastle TLS ----------------

    private suspend fun pairBcTls(
        ctx: android.content.Context,
        host: String,
        port: Int,
        pairingCode: String,
        priv: PrivateKey,
        cert: java.security.cert.X509Certificate,
        km: AdbKeyManager,
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val socket = java.net.Socket()
        socket.tcpNoDelay = true
        socket.connect(java.net.InetSocketAddress(host, port), 8000)
        socket.soTimeout = 15000

        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        val tlsClient = PairingTlsClient(priv, cert)
        try {
            kotlinx.coroutines.withTimeoutOrNull(25000) { protocol.connect(tlsClient) }
                ?: throw IllegalStateException("TLS1.3 握手超时（25s）——BC 与 BoringSSL 协商卡住")
            Log.i(TAG, "BC TLS 握手成功")
        } catch (e: IllegalStateException) {
            socket.close(); throw e
        } catch (e: Exception) {
            socket.close()
            throw IllegalStateException("TLS 握手失败：${e.message}")
        }
        val exported = tlsClient.exported
            ?: throw IllegalStateException("exporter 不可用（notifyHandshakeComplete 未触发）")
        val fullPswd = pairingCode.toByteArray(Charsets.US_ASCII) + exported

        val guid = exchangePairingPackets(protocol.inputStream, protocol.outputStream, km, fullPswd)
        socket.close()
        guid
    }

    /** 配对专用 TLS 客户端：TLS1.3、客户端证书、服务端证书全放行。 */
    internal class PairingTlsClient(
        private val clientPriv: PrivateKey,
        private val clientCert: java.security.cert.X509Certificate,
    ) : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {

        /** 公开访问器：在子类内部读 protected 的 context 没问题。 */
        fun cryptoParameters(): org.bouncycastle.tls.crypto.TlsCryptoParameters =
            org.bouncycastle.tls.crypto.TlsCryptoParameters(context)

        /** 握手完成时在 BC 允许的窗口内导出 keying material（其他时机抛 IllegalStateException）。 */
        var exported: ByteArray? = null
        override fun notifyHandshakeComplete() {
            try {
                exported = context.exportKeyingMaterial("adb-label\u0000", null, 64)
                Log.i(TAG, "exporter(8)=${exported?.take(8)?.joinToString("") { "%02x".format(it) }}")
            } catch (e: Exception) {
                Log.i(TAG, "exporter 失败: $e")
            }
        }

        override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
                // 配对阶段不校验服务端证书（对齐 AOSP：verify callback 全放行）
                Log.d(TAG, "server cert accepted: ${serverCertificate != null}")
            }

            override fun getClientCredentials(request: org.bouncycastle.tls.CertificateRequest): TlsCredentials? {
                // 关键：必须用服务端请求里的 wire 实例。手搓的 alg 经
                // SignatureScheme.from 得到错误 id（0x0404），会错走 PKCS1 签名器
                val algs = request.supportedSignatureAlgorithms
                var alg: org.bouncycastle.tls.SignatureAndHashAlgorithm? = null
                if (algs != null) {
                    for (o in algs) {
                        val a = o as org.bouncycastle.tls.SignatureAndHashAlgorithm
                        if (a.signature == org.bouncycastle.tls.SignatureAlgorithm.rsa_pss_rsae_sha256) { alg = a; break }
                    }
                }
                if (alg == null) {
                    android.util.Log.i(TAG, "服务端未请求 rsa_pss_rsae_sha256，返回 null（不发客户端证书）")
                    @Suppress("USELESS_CAST")
                    return null as TlsCredentials?
                }
                return makeClientSigner(clientPriv, clientCert, cryptoParameters(), alg)
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
        val crt = kf.getKeySpec(priv, RSAPrivateCrtKeySpec::class.java)
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
            Certificate(ByteArray(0), arrayOf(org.bouncycastle.tls.CertificateEntry(tlsCert, null))),
            sigAlg
        )
    }

    // ---------------- PairingPacket 交换（两条路径共用） ----------------

    private fun exchangePairingPackets(
        inp: InputStream,
        out: OutputStream,
        km: AdbKeyManager,
        fullPswd: ByteArray,
    ): String {
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
        return guid
    }

    // ---------------- PairingPacket 读写 ----------------

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = byteArrayOf(
            1.toByte(), type.toByte(),
            (payload.size shr 24).toByte(), (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(), payload.size.toByte()
        )
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
