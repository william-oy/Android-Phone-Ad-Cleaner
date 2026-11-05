package com.adcleaner.app.adb

import android.content.Context

/**
 * 包装 [AdbClient]，提供挂起/还原广告包的高层接口。
 *
 * 所有网络方法均为 `suspend`，应在协程（如 `lifecycleScope`）中调用。
 * 底层通过本机无线调试 adbd 执行 `pm suspend --user 0 <pkg>` /
 * `pm unsuspend --user 0 <pkg>`，挂起的包进程被冻结、不再弹广告，可随时还原。
 */
class SuspendManager(context: Context) {

    private val keyManager = AdbKeyManager(context)
    private val client = AdbClient(keyManager)

    /** 连接本机 adbd（端口在无线调试设置页可见）。首次会弹「允许 USB 调试」。 */
    suspend fun connect(port: Int): Result<Unit> = client.connect(port)

    /** 挂起指定包，返回 `pm suspend` 的输出。 */
    suspend fun suspendPackage(pkg: String): Result<String> =
        client.shellExec("pm suspend --user 0 $pkg")

    /** 还原指定包，返回 `pm unsuspend` 的输出。 */
    suspend fun unsuspendPackage(pkg: String): Result<String> =
        client.shellExec("pm unsuspend --user 0 $pkg")

    /** 通用 shell，返回命令输出。 */
    suspend fun shell(cmd: String): Result<String> = client.shellExec(cmd)

    /**
     * 查询当前已挂起的包名列表（best-effort）。
     *
     * Android 没有直接的「列出已挂起包」命令，这里跑一次 `dumpsys package`，
     * 解析每个 `Package [<name>]` 块内是否含 `suspended=true`。
     * 解析依赖 dumpsys 输出格式，跨版本可能不稳定，失败时返回空列表。
     */
    suspend fun listSuspended(): Result<List<String>> =
        client.shellExec("dumpsys package").map { out ->
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

    fun isConnected(): Boolean = client.isConnected()

    fun disconnect() = client.disconnect()
}
