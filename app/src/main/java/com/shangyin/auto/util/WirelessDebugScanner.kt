package com.shangyin.auto.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * 无线调试服务发现：实时探测系统广播的 adb 配对 / 连接端口。
 * 这是「无线调试启动」能自动拿到 IP:端口 的关键。
 */
class WirelessDebugScanner(
    private val context: Context,
    private val onResult: (type: String, host: String, port: Int) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    companion object {
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private var running = false

    fun start() {
        if (running) return
        running = true
        discover(TYPE_PAIRING)
        discover(TYPE_CONNECT)
    }

    private fun discover(type: String) {
        val mgr = nsd ?: run {
            onError("当前系统不支持服务发现")
            return
        }
        if (listeners.containsKey(type)) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onError("服务发现启动失败($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(info: NsdServiceInfo?) {
                if (info == null) return
                resolve(type, info)
            }

            override fun onServiceLost(info: NsdServiceInfo?) = Unit
        }
        listeners[type] = listener
        runCatching { mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onError("服务发现异常：${it.message}") }
    }

    private fun resolve(type: String, info: NsdServiceInfo) {
        val mgr = nsd ?: return
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                val host = serviceInfo?.host?.hostAddress ?: return
                val port = serviceInfo.port
                if (port > 0) onResult(type, host, port)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mgr.resolveService(info, context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            mgr.resolveService(info, cb)
        }
    }

    fun stop() {
        running = false
        val mgr = nsd ?: return
        listeners.values.forEach { l -> runCatching { mgr.stopServiceDiscovery(l) } }
        listeners.clear()
    }
}
