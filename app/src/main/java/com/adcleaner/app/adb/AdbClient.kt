package com.adcleaner.app.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 纯 Kotlin 实现的 ADB 协议客户端。
 *
 * 连接本机无线调试的 adbd（`127.0.0.1:port`），完成 `CNXN` / `AUTH` 握手后，
 * 通过 `OPEN "shell:<cmd>"` 执行命令并收集 stdout/stderr 输出。
 *
 * 仅依赖 `java.net.Socket` / `java.security` / `java.util.zip`，不依赖任何第三方 ADB 库。
 *
 * 协议要点（24 字节小端消息头 + payload）：
 * - 头：command, arg0, arg1, data_length, data_crc32, magic(=command 取反)
 * - 握手：client→CNXN；server→AUTH(token)；client→AUTH(RSAPUBKEY=3, base64(自定义公钥结构)+\0)；
 *   用户在弹窗点「允许」后 server→CNXN，已授权则直接 CNXN。
 * - shell：client→OPEN(localId,0,"shell:cmd\n")；server→OKAY(remoteId,localId)；
 *   server→WRTE(输出) → client 回 OKAY 做流控确认；server→CLSE 结束。
 *
 * 注意：本实现使用 legacy shell（CNXN 不声明 shell_v2），因此 WRTE 载荷为原始输出，
 * 无需剥离 shell_v2 的流 ID 前缀字节，适合一次性命令。
 */
class AdbClient(private val keyManager: AdbKeyManager) {

    /** ADB 消息：24 字节头解析后的结构。 */
    private class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    companion object {
        // 命令常量：对应 ASCII 的小端 u32
        private const val A_CNXN = 0x4e584e43   // "CNXN"
        private const val A_AUTH = 0x48545541   // "AUTH"
        private const val A_OPEN = 0x4e45504f   // "OPEN"
        private const val A_WRTE = 0x45545257   // "WRTE"
        private const val A_OKAY = 0x59414b4f   // "OKAY"（即 READY：open 应答 / WRTE 流控确认）
        private const val A_CLSE = 0x45534c43   // "CLSE"

        // AUTH 的 arg0 类型
        private const val AUTH_TOKEN = 1        // server 下发 20 字节随机 token
        private const val AUTH_RSAPUBKEY = 3    // client 上送公钥（首次弹授权、已授权直接过）

        private const val ADB_VERSION = 0x01000001
        private const val MAX_PAYLOAD = 0x100000

        private const val HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 15000
        private const val AUTH_WAIT_TIMEOUT_MS = 120000   // 等用户在弹窗点「允许」
    }

    private val mutex = Mutex()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: BufferedOutputStream? = null
    private var localIdCounter = 1
    @Volatile
    private var connected = false

    fun isConnected(): Boolean = connected

    /**
     * 连接 adbd 并完成认证。首次连接时 adbd 会弹「允许 USB 调试」对话框，
     * 用户点「允许」后建立连接；已授权的密钥则直接通过。
     */
    suspend fun connect(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        cleanup() // 关闭可能残留的旧连接
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            sock.tcpNoDelay = true
            socket = sock
            input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            output = BufferedOutputStream(sock.getOutputStream())

            // 1. client -> CNXN(version, max_payload, "host::\0")
            //    不声明 shell_v2，使用 legacy shell，WRTE 载荷为原始输出。
            val cnxnPayload = "host::\u0000".toByteArray(Charsets.UTF_8)
            writeMessage(A_CNXN, ADB_VERSION, MAX_PAYLOAD, cnxnPayload)

            // 2. 读应答：CNXN（已授权）/ AUTH（需认证）/ CLSE（拒绝）
            val first = readMessage()
            when (first.command) {
                A_CNXN -> { /* 已授权，连接建立 */ }
                A_AUTH -> handleAuth(first)
                A_CLSE -> throw IOException("adbd 拒绝连接（CLSE）")
                else -> throw IOException("意外的握手应答 0x${"%08x".format(first.command)}")
            }

            connected = true
            Result.success(Unit)
        } catch (e: Exception) {
            cleanup()
            Result.failure(e)
        }
    }

    /** 处理 AUTH 握手：收到 token 后上送公钥，等待用户授权。 */
    private fun handleAuth(tokenMsg: AdbMessage) {
        if (tokenMsg.arg0 != AUTH_TOKEN) {
            throw IOException("意外的 AUTH 类型 ${tokenMsg.arg0}（期望 TOKEN=1）")
        }
        // 3. client -> AUTH(RSAPUBKEY=3, base64(自定义公钥结构) + '\0')
        val pubKeyPayload = keyManager.getPublicKeyAdbPayload()
        writeMessage(A_AUTH, AUTH_RSAPUBKEY, 0, pubKeyPayload)

        // 等待用户在「允许 USB 调试」弹窗操作，给足时间
        val sock = socket ?: throw IOException("socket 已关闭")
        sock.soTimeout = AUTH_WAIT_TIMEOUT_MS
        val resp = readMessage()
        sock.soTimeout = READ_TIMEOUT_MS

        when (resp.command) {
            A_CNXN -> { /* 授权成功，连接建立 */ }
            A_CLSE -> throw IOException("授权被拒绝：请在弹窗点「允许」（或该设备已禁用调试授权）")
            A_AUTH -> throw IOException("adbd 仍要求签名认证，本实现仅支持公钥授权模式")
            else -> throw IOException("授权后意外的应答 0x${"%08x".format(resp.command)}")
        }
    }

    /**
     * 执行一条 shell 命令，返回合并的 stdout/stderr 输出。
     *
     * 使用 legacy shell：OPEN `"shell:<cmd>\n"`，server 用 WRTE 推送原始输出，
     * client 每收到一条 WRTE 回 OKAY 做流控确认，server 执行完发 CLSE 结束。
     * 命令串行执行（[mutex] 保护），避免多流复用同一连接时消息错乱。
     */
    suspend fun shellExec(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        if (!connected) return@withContext Result.failure(IOException("未连接 adbd"))
        mutex.withLock {
            try {
                val localId = localIdCounter++
                val openPayload = ("shell:" + cmd + "\n").toByteArray(Charsets.UTF_8)
                writeMessage(A_OPEN, localId, 0, openPayload)

                // OPEN 应答：OKAY(remoteId, localId) 表示会话已建立；CLSE 表示被拒绝
                val okay = readMessage()
                if (okay.command == A_CLSE) {
                    throw IOException("shell 会话被拒绝（CLSE），命令未执行")
                }
                if (okay.command != A_OKAY) {
                    throw IOException("shell open 意外应答 0x${"%08x".format(okay.command)}")
                }
                val remoteId = okay.arg0

                val sb = StringBuilder()
                while (true) {
                    val msg = readMessage()
                    when (msg.command) {
                        A_WRTE -> {
                            if (msg.payload.isNotEmpty()) {
                                sb.append(String(msg.payload, Charsets.UTF_8))
                            }
                            // 流控确认：回 OKAY(localId, remoteId)，告知可继续发送
                            writeMessage(A_OKAY, localId, remoteId, ByteArray(0))
                        }
                        A_CLSE -> {
                            // 协议规定不必回应 CLSE；回一个也无害（已关闭则被忽略）
                            writeMessage(A_CLSE, localId, remoteId, ByteArray(0))
                            break
                        }
                        A_OKAY -> { /* open 应答已处理，后续不会再有；忽略 */ }
                        else -> throw IOException("shell 读到意外消息 0x${"%08x".format(msg.command)}")
                    }
                }
                Result.success(sb.toString())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun disconnect() {
        cleanup()
    }

    private fun cleanup() {
        connected = false
        try { output?.flush() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    // ---- 协议底层：消息读写 ----

    private fun writeMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = output ?: throw IOException("未连接")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(crc32(payload))
        header.putInt(command.inv())   // magic = command ^ 0xFFFFFFFF
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readMessage(): AdbMessage {
        val inp = input ?: throw IOException("未连接")
        val header = ByteArray(24)
        inp.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val len = buf.int
        val crc = buf.int
        val magic = buf.int

        if (magic != command.inv()) {
            throw IOException("ADB 消息 magic 校验失败")
        }
        if (len < 0 || len > MAX_PAYLOAD) {
            throw IOException("ADB 消息长度异常: $len")
        }
        val payload = ByteArray(len)
        if (len > 0) inp.readFully(payload)
        if (crc32(payload) != crc) {
            throw IOException("ADB 消息 CRC32 校验失败")
        }
        return AdbMessage(command, arg0, arg1, payload)
    }

    private fun crc32(data: ByteArray): Int {
        val c = CRC32()
        c.update(data)
        return c.value.toInt()
    }
}
