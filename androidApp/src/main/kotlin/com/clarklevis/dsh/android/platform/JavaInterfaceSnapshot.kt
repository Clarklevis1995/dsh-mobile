package com.clarklevis.dsh.android.platform

import java.net.NetworkInterface

/**
 * 由 app 侧枚举网络接口，交给 Go 侧注册成 netmon 的接口来源。
 *
 * 为什么必须这样：Android 从 API 30 起禁止 app UID 对 NETLINK_ROUTE 套接字调用 bind()，
 * Go 标准库的 net.Interfaces() 在 app 进程里必然失败（route ip+net: netlinkrib: permission denied），
 * tsnet 因此起不来。官方 Android 客户端与社区实现都从 Java/Bionic 允许的路径喂这份数据；
 * 上游给「没有 Java 可用」场景的准备（feature/androidbin）恰好把 android+cgo 排除在门外。
 *
 * flags 里的 broadcast 是近似值：Java 没有 isBroadcast，按 up 且非点对点、非回环给出。
 */
internal object JavaInterfaceSnapshot {

    /** 空列表也返回 "[]"，由 Go 侧拒绝并报错，不在这里静默兜住。 */
    fun json(): String = "[" + entries().joinToString(",") { it.json } + "]"

    fun count(): Int = entries().size

    private data class Entry(val json: String)

    private fun entries(): List<Entry> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { nic ->
            val flags = buildList {
                if (nic.isUp) add("up")
                if (nic.isLoopback) add("loopback")
                if (nic.isPointToPoint) add("pointtopoint")
                if (nic.supportsMulticast()) add("multicast")
                if (nic.isUp && !nic.isPointToPoint && !nic.isLoopback) add("broadcast")
            }
            val addrs = nic.interfaceAddresses.mapNotNull { endpoint ->
                // IPv6 的 hostAddress 带 %wlan0 这类 zone 后缀，Go 侧按前缀长度自己处理。
                endpoint.address?.hostAddress?.substringBefore('%')?.let { host ->
                    "\"$host/${endpoint.networkPrefixLength}\""
                }
            }
            Entry(
                "{\"name\":\"${nic.name}\",\"index\":${nic.index},\"mtu\":${nic.mtu}," +
                    "\"flags\":[${flags.joinToString(",") { "\"$it\"" }}]," +
                    "\"addrs\":[${addrs.joinToString(",")}]}"
            )
        }
}
