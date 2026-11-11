package com.adcleaner.app.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 基于 libadb-android（[AdbLibManager]）的挂起/还原广告包高层接口。
 *
 * 所有网络方法均为 `suspend`，应在协程（如 `lifecycleScope`）中调用。
 * 底层通过本机无线调试 adbd 执行 `pm suspend --user 0 <pkg>` /
 * `pm unsuspend --user 0 <pkg>`，挂起的包进程被冻结、不再弹广告，可随时还原。
 *
 * 连接层自动区分协议：经典端口（5555，CNXN+AUTH）与无线调试 TLS 端口
 * （A_STLS 升级）都走 [io.github.muntashirakon.adb.AdbConnection.connect]。
 */
class SuspendManager(context: Context, connectTimeoutSeconds: Long = 15) {

    private val appContext = context.applicationContext
    private val mgr = AdbLibManager(context, connectTimeoutSeconds)

    /** 连接本机 adbd 经典端口（如 5555）。首次会弹「允许 USB 调试」。 */
    suspend fun connect(port: Int): Result<Unit> = ioConnect(port)

    /** 连接无线调试（TLS）端口。已配对设备静默通过；未配对会抛 [io.github.muntashirakon.adb.AdbPairingRequiredException]。 */
    suspend fun connectTls(port: Int): Result<Unit> = ioConnect(port)

    /** 与配对端口完成 `adb pair`，成功后 [connectTls] 连接端口即可免授权直连。 */
    suspend fun pair(host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(mgr.pair(host, port, code)) { "配对未成功" }
                Unit
            }
        }

    /** 挂起指定包，返回 `pm suspend` 的输出。 */
    suspend fun suspendPackage(pkg: String): Result<String> =
        shellExec("pm suspend --user 0 $pkg")

    /** 还原指定包，返回 `pm unsuspend` 的输出。 */
    suspend fun unsuspendPackage(pkg: String): Result<String> =
        shellExec("pm unsuspend --user 0 $pkg")

    /** 通用 shell，返回命令输出。 */
    suspend fun shell(cmd: String): Result<String> = shellExec(cmd)

    /**
     * 查询设备上已安装的包名集合（`pm list packages`）。
     * 用于在挂起前过滤掉设备上不存在的包，防止「空成功」。
     */
    suspend fun listInstalledPackages(): Result<Set<String>> =
        shellExec("pm list packages").map { out ->
            out.lineSequence()
                .mapNotNull { line ->
                    if (line.startsWith("package:")) line.removePrefix("package:").trim() else null
                }
                .toSet()
        }

    /**
     * 挂起前检查包是否真的安装了（`pm path <pkg>` 有输出 = 已安装）。
     * 返回 (存在?, 错误信息?)。
     */
    suspend fun packageExists(pkg: String): Pair<Boolean, String?> {
        val r = shellExec("pm path $pkg")
        return if (r.isSuccess) {
            val out = r.getOrNull()?.trim().orEmpty()
            Pair(out.startsWith("package:"), null)
        } else {
            Pair(false, r.exceptionOrNull()?.message)
        }
    }

    /**
     * 查询当前已挂起的包名列表（best-effort）。
     *
     * Android 没有直接的「列出已挂起包」命令，这里跑一次 `dumpsys package`，
     * 解析每个 `Package [<name>]` 块内是否含 `suspended=true`。
     * 解析依赖 dumpsys 输出格式，跨版本可能不稳定，失败时返回空列表。
     */
    suspend fun listSuspended(): Result<List<String>> =
        shellExec("dumpsys package").map { out ->
            val suspended = mutableListOf<String>()
            val pkgRegex = Regex("Package \\[(.+?)\\]")
            var current: String? = null
            var currentSuspended = false
            for (line in out.lineSequence()) {
                val m = pkgRegex.find(line)
                if (m != null) {
                    if (current != null && currentSuspended) suspended.add(current)
                    current = m.groupValues[1]
                    currentSuspended = false
                } else if (current != null && line.contains("suspended=true")) {
                    currentSuspended = true
                }
            }
            if (current != null && currentSuspended) suspended.add(current)
            suspended
        }

    fun isConnected(): Boolean = mgr.isConnected

    fun disconnect() {
        try {
            mgr.disconnect()
        } catch (_: Exception) {
        }
    }

    // ---------- 内部 ----------

    /**
     * 连接语义：库在「已连接」时返回 false（表示无需重连，是正常情况而非失败），
     * 只有连接尝试真正失败时才抛异常。
     */
    private suspend fun ioConnect(port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!mgr.connect(port) && !mgr.isConnected) error("连接失败")
                Unit
            }
        }

    /** 用记住的端口/模式重连（上次 TLS 或经典，缺省回退 5555）。 */
    private suspend fun reconnect(): Result<Unit> {
        val port = AdbAutomation.resolvePort(appContext)
        return ioConnect(port)
    }

    /**
     * legacy shell：写命令 → 读到流关闭（adbd 输出完即关）。
     *
     * 自愈：adbd 对空闲 TLS 连接约 10 秒即关闭（`adb connection max_idle_timeout_ms`），
     * 首次执行失败时自动按记住的端口重连一次再试。
     *
     * 注意：libadb-android 的 [io.github.muntashirakon.adb.AdbStream.read] 在对端
     * 关闭流（= shell 正常输出完毕）时抛 `IOException("Stream closed.")` 而非返回 -1，
     * 这里按 EOF 处理；其余 IO 错误照常上抛。
     */
    private suspend fun shellExec(cmd: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { execShell(cmd) }.recoverCatching {
                disconnect()
                reconnect().exceptionOrNull()?.let { throw it }
                execShell(cmd)
            }
        }

    private fun execShell(cmd: String): String {
        val stream = mgr.openStream("shell:$cmd")
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = try {
                    stream.read(buf, 0, buf.size)
                } catch (e: java.io.IOException) {
                    if (e.message?.contains("closed", ignoreCase = true) == true) break
                    throw e
                }
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
        return out.toString("UTF-8")
    }
}
