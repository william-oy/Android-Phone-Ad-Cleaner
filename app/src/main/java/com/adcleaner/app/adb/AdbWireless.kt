package com.adcleaner.app.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 用 mDNS/NSD 发现手机的无线调试服务（手机会主动广播）：
 *  - `_adb-tls-connect._tcp`：连接端口（配对成功后连这个）
 *  - `_adb-tls-pairing._tcp`：配对端口（「使用配对码配对设备」弹窗存活期间广播）
 *
 * 发现即免填端口：老人点一下按钮，端口自己找到。
 */
object AdbWireless {

    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"
    const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"

    data class Endpoint(val host: String, val port: Int, val serviceName: String)

    /** 在 [timeoutMs] 内发现第一个可解析的 [type] 服务；超时返回 null。 */
    suspend fun discover(ctx: Context, type: String, timeoutMs: Long = 12000): Endpoint? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var discovery: NsdManager.DiscoveryListener? = null
                var resolved = false

                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        if (!resolved && cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (resolved) return
                        resolved = true
                        val host = info.host?.hostAddress ?: return cont.resume(null)
                        try { nsd.stopServiceDiscovery(discovery!!) } catch (_: Exception) {}
                        if (cont.isActive) {
                            cont.resume(Endpoint(host, info.port, info.serviceName ?: ""))
                        }
                    }
                }

                discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String?) {}
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (resolved) return
                        if (serviceInfo.serviceType?.startsWith(type.substringBefore(".")) == true &&
                            serviceInfo.serviceType?.contains(type.substringAfter("._tcp")) == true) {
                            try {
                                nsd.resolveService(serviceInfo, resolveListener)
                            } catch (_: Exception) {}
                        }
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                    override fun onDiscoveryStopped(serviceType: String?) {}
                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                }

                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery!!)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try { nsd.stopServiceDiscovery(discovery!!) } catch (_: Exception) {}
                }
            }
        }
    }
}
