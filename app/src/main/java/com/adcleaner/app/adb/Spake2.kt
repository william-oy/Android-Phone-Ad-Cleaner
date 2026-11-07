package com.adcleaner.app.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BoringSSL curve25519 SPAKE2（客户端 = Alice），逐行移植自已实测通过的原型
 * scratch/pair_proto.py（协议依据 AOSP packages/modules/adb pairing_auth/pairing_auth.cpp
 * 与 boringssl crypto/curve25519/spake25519.c）。
 *
 * 无线调试「配对码」就是这里的 pswd：6 位数字 ASCII。
 */
object Spake2 {

    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.valueOf(2).pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modPow(P - BigInteger.TWO, P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow((P - BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    // ed25519 基点 (RFC 8032)
    private val BASE_X = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BASE_Y = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    // SPAKE2 固定点 M（Alice 用）/ N（Bob 用），编码取自 spake25519.c 注释
    private val M_ENC = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
    private val N_ENC = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

    val MY_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)   // sizeof 含 NUL
    val THEIR_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    // ---------------- 点运算（扩展坐标 X,Y,Z,T） ----------------

    private fun inv(x: BigInteger): BigInteger = x.modPow(P - BigInteger.TWO, P)

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val xx = y.multiply(y).subtract(BigInteger.ONE)
            .multiply(inv(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P)
        if (xx.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun decode(b32: ByteArray): BigIntegerArrayPoint? {
        val y = BigInteger(1, b32.reversedArray()).and(BigInteger.valueOf(2).pow(255).subtract(BigInteger.ONE))
        val sign = (b32[31].toInt() shr 7) and 1
        val x = recoverX(y, sign) ?: return null
        val yy = y.mod(P)
        return BigIntegerArrayPoint(x.mod(P), yy, BigInteger.ONE, x.multiply(yy).mod(P))
    }

    private fun toBytes(x: BigInteger, y: BigInteger, z: BigInteger): ByteArray {
        val zi = inv(z)
        val xi = x.multiply(zi).mod(P)
        val yi = y.multiply(zi).mod(P)
        val b = ByteArray(32)
        val yb = yi.toByteArray()   // 大端、可能带符号前缀
        // 转小端固定 32 字节（无符号）
        val yLe = ByteArray(32)
        val noSign = if (yb.size > 32 && yb[0].toInt() == 0) yb.copyOfRange(1, yb.size) else yb
        for (i in noSign.indices) {
            if (i >= 32) break
            yLe[i] = noSign[noSign.size - 1 - i]
        }
        for (i in 0 until 32) b[i] = yLe[i]
        b[31] = ((b[31].toInt() and 0x7f) or ((xi.and(BigInteger.ONE).toInt()) shl 7)).toByte()
        return b
    }

    private fun add(p: BigIntegerArrayPoint, q: BigIntegerArrayPoint): BigIntegerArrayPoint {
        val (X1, Y1, Z1, T1) = p
        val (X2, Y2, Z2, T2) = q
        val A = Y1.subtract(X1).multiply(Y2.subtract(X2)).mod(P)
        val B = Y1.add(X1).multiply(Y2.add(X2)).mod(P)
        val C = BigInteger.TWO.multiply(T1).multiply(T2).multiply(D).mod(P)
        val Dd = BigInteger.TWO.multiply(Z1).multiply(Z2).mod(P)
        val E = B.subtract(A)
        val F = Dd.subtract(C)
        val G = Dd.add(C)
        val H = B.add(A)
        return BigIntegerArrayPoint(E.multiply(F).mod(P), G.multiply(H).mod(P), F.multiply(G).mod(P), E.multiply(H).mod(P))
    }

    private fun sub(p: BigIntegerArrayPoint, q: BigIntegerArrayPoint): BigIntegerArrayPoint {
        val (X2, Y2, Z2, T2) = q
        return add(p, BigIntegerArrayPoint(P.subtract(X2), Y2, Z2, P.subtract(T2)))
    }

    private fun dbl(p: BigIntegerArrayPoint): BigIntegerArrayPoint {
        val (X1, Y1, Z1, _) = p
        val A = X1.multiply(X1).mod(P)
        val B = Y1.multiply(Y1).mod(P)
        val C = BigInteger.TWO.multiply(Z1).multiply(Z1).mod(P)
        val H = A.add(B)
        val E = H.subtract(X1.add(Y1).multiply(X1.add(Y1)).mod(P))
        val G = A.subtract(B)
        val F = C.add(G)
        return BigIntegerArrayPoint(E.multiply(F).mod(P), G.multiply(H).mod(P), F.multiply(G).mod(P), E.multiply(H).mod(P))
    }

    private fun scalarmult(scalar: BigInteger, pt: BigIntegerArrayPoint): BigIntegerArrayPoint {
        var r = IDENTITY
        var base = pt
        var n = scalar
        while (n.signum() > 0) {
            if (n.testBit(0)) r = add(r, base)
            base = dbl(base)
            n = n.shiftRight(1)
        }
        return r
    }

    private val IDENTITY = BigIntegerArrayPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private data class BigIntegerArrayPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        operator fun component1() = x
        operator fun component2() = y
        operator fun component3() = z
        operator fun component4() = t
    }

    private fun leToBigInt(b32: ByteArray): BigInteger =
        BigInteger(1, b32.reversedArray())

    private fun bigIntToLe(v: BigInteger, len: Int = 32): ByteArray {
        val be = v.toByteArray()
        // 大端无符号 -> 小端固定长度
        val unsigned = if (be.size > len && be[0].toInt() == 0) be.copyOfRange(1, be.size) else be
        val out = ByteArray(len)
        for (i in unsigned.indices) {
            if (i >= len) break
            out[i] = unsigned[unsigned.size - 1 - i]
        }
        return out
    }

    // ---------------- SPAKE2 客户端（Alice） ----------------

    class Client internal constructor(val myMsg: ByteArray, private val priv: BigInteger,
                                      private val ps: BigInteger, private val passwordHash: ByteArray) {
        /** 处理对端 SPAKE2 消息，返回 64 字节密钥材料。 */
        fun process(theirMsg: ByteArray): ByteArray {
            val qstar = decode(theirMsg) ?: throw IllegalStateException("对端点不在曲线上")
            val peersMask = scalarmult(ps, decode(N_ENC)!!)
            val q = sub(qstar, peersMask)
            val shared = scalarmult(priv, q)
            val dh = toBytes(shared.x, shared.y, shared.z)
            val h = MessageDigest.getInstance("SHA-512")
            fun upd(blob: ByteArray) {
                h.update(blob.size.toLong().toByteArrayLe8())
                h.update(blob)
            }
            upd(MY_NAME)
            upd(THEIR_NAME)
            upd(myMsg)
            upd(theirMsg)
            upd(dh)
            upd(passwordHash)
            return h.digest()
        }
    }

    private fun Long.toByteArrayLe8(): ByteArray {
        val b = ByteArray(8)
        var v = this
        for (i in 0 until 8) {
            b[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        return b
    }

    /** 生成 SPAKE2 客户端上下文。pswd = 6 位配对码 + 64 字节 TLS exported key。 */
    fun client(pswd: ByteArray): Client {
        val rnd = ByteArray(64)
        SecureRandom().nextBytes(rnd)
        val priv = leToBigInt(rnd).mod(L).shiftLeft(3)

        val pPt = scalarmult(priv, BigIntegerArrayPoint(BASE_X, BASE_Y, BigInteger.ONE, BASE_X.multiply(BASE_Y).mod(P)))

        val passwordHash = MessageDigest.getInstance("SHA-512").digest(pswd)
        var ps = leToBigInt(passwordHash).mod(L)
        for (bit in intArrayOf(1, 2, 4)) {
            if (ps.and(BigInteger.valueOf(bit.toLong())).signum() != 0) ps = ps.add(L)
        }

        val mask = scalarmult(ps, decode(M_ENC)!!)
        val pstar = add(pPt, mask)
        val myMsg = toBytes(pstar.x, pstar.y, pstar.z)

        return Client(myMsg, priv, ps, passwordHash)
    }
}
