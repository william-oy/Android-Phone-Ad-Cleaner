package com.adcleaner.app.adb

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 用 ADB 权限自动完成「打开无障碍」等对老人最难的步骤。
 *
 * 原理：无线调试（ADB）有 `WRITE_SECURE_SETTINGS` 级别的能力，可以直接改
 * `settings put secure enabled_accessibility_services`，让 app 给自己开无障碍，
 * 免去老人在系统设置里翻找服务的全部步骤。一次配对成功后，后续只需知道端口。
 *
 * 端口记忆：无线调试每次开关后端口会变，但同一次开机内不变。这里把上次连接
 * 成功的端口存下来，下次打开 app 自动尝试，成功则老人全程无感。
 */
object AdbAutomation {

    private const val PREFS = "adcleaner_prefs"
    private const val KEY_LAST_PORT = "last_adb_port"
    private const val KEY_LAST_OK = "last_adb_ok"

    /** 本 app 的无障碍服务组件。 */
    private val SERVICE_COMPONENT =
        ComponentName("com.adcleaner.app", "com.adcleaner.app.cleaner.AdCleanerService")

    fun saveLastPort(ctx: Context, port: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LAST_PORT, port).putBoolean(KEY_LAST_OK, true).apply()
    }

    fun lastPort(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_PORT, -1)

    fun clearLastPort(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAST_OK, false).apply()
    }

    /**
     * 连接上次成功的端口（快速失败，约 3 秒超时由底层控制）。
     * 返回 Result；失败说明端口变了或无线调试没开。
     */
    suspend fun tryReconnect(ctx: Context, manager: SuspendManager): Result<Unit> {
        val port = lastPort(ctx)
        if (port <= 0) return Result.failure(IllegalStateException("没有上次端口"))
        return manager.connect(port).onSuccess { saveLastPort(ctx, port) }
    }

    /**
     * 连接 [port] 并把本 app 的无障碍服务直接写进系统设置（等于替用户打开开关）。
     *
     * 步骤：
     * 1. `settings get secure enabled_accessibility_services` 读现有值
     * 2. 若没包含本服务，冒号拼接后 `settings put secure` 写回
     * 3. `settings put secure accessibility_enabled 1` 总开关打开
     * 4. 回读确认
     *
     * 注意：合并而不是覆盖，避免把用户已有的其他无障碍服务（如读屏）顶掉。
     */
    suspend fun enableAccessibility(ctx: Context, manager: SuspendManager, port: Int): Result<Unit> {
        val flat = SERVICE_COMPONENT.flattenToString()

        val connectRes = manager.connect(port)
        if (connectRes.isFailure) {
            return Result.failure(
                connectRes.exceptionOrNull() ?: IllegalStateException("连接失败")
            )
        }
        saveLastPort(ctx, port)

        val cur = manager.shell("settings get secure enabled_accessibility_services")
            .getOrNull()?.trim().orEmpty()
        val merged = when {
            cur.isEmpty() || cur == "null" -> flat
            cur.contains(flat) -> null          // 已启用，不用写
            else -> "$cur:$flat"
        }
        if (merged != null) {
            val put = manager.shell("settings put secure enabled_accessibility_services \"$merged\"")
            if (put.isFailure) return Result.failure(put.exceptionOrNull()!!)
            if (put.getOrNull()?.contains("Exception") == true) {
                return Result.failure(IllegalStateException("写入无障碍设置被拒绝"))
            }
        }
        val on = manager.shell("settings put secure accessibility_enabled 1")
        if (on.isFailure) return Result.failure(on.exceptionOrNull()!!)

        // 回读确认；个别 ROM 写入后要一拍才生效，稍等再读
        kotlinx.coroutines.delay(400)
        val check = manager.shell("settings get secure accessibility_enabled").getOrNull()?.trim()
        return if (check == "1" || check == null) Result.success(Unit)
        else Result.failure(IllegalStateException("系统回读无障碍状态为 $check"))
    }

    /** 无障碍服务是否已在系统设置里登记（无需连接 ADB，直接读 Settings.Secure）。 */
    fun isAccessibilityRegistered(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any {
            it.equals(SERVICE_COMPONENT.flattenToString(), ignoreCase = true)
        }
    }
}
