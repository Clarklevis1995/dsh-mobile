package com.clarklevis.dsh.android.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.tailscale.TailscalePolicy
import com.clarklevis.dsh.shared.tailscale.TailscaleStage
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tsnetgo.Tsnetgo

/** 本地生命周期检查无需外网；登录服务检查需显式传 tailscaleNativeSmoke=true，不执行账号授权。 */
class TailscaleNativeSmokeDeviceTest {
    @Test fun nativeNodesKeepIndependentLocalListenersWithoutInternet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "tailscale-native-offline-${UUID.randomUUID()}")
        val first = Tsnetgo.start("dsh-offline-a", File(root, "a").absolutePath)
        val second = Tsnetgo.start("dsh-offline-b", File(root, "b").absolutePath)
        var secondClosed = false
        fun port(raw: String): Int {
            val proxy = Json.parseToJsonElement(raw) as JsonObject
            assertEquals(JsonPrimitive(true), proxy["ok"])
            assertEquals(JsonPrimitive("127.0.0.1"), proxy["host"])
            return (proxy.getValue("port") as JsonPrimitive).content.toInt()
        }
        fun connect(port: Int) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                assertTrue(socket.isConnected)
            }
        }
        try {
            val firstPort = port(first.startProxy())
            val secondPort = port(second.startProxy())
            assertTrue(firstPort != secondPort)
            connect(firstPort)
            connect(secondPort)
            second.close()
            secondClosed = true
            assertTrue(runCatching { connect(secondPort) }.isFailure)
            assertEquals(firstPort, port(first.startProxy()))
            connect(firstPort)
        } finally {
            first.close()
            if (!secondClosed) second.close()
            root.deleteRecursively()
        }
    }

    @Test fun twoNativeNodesObtainIndependentLinksAndClosingOneKeepsTheOtherAlive() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tailscaleNativeSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "tailscale-native-smoke-${UUID.randomUUID()}")
        val a = TsnetTunnel(context, directory = File(root, "a"))
        val b = TsnetTunnel(context, directory = File(root, "b"))
        try {
            Tsnetgo.setInterfacesJSON(JavaInterfaceSnapshot.json())
            val startedAt = System.nanoTime()
            val first = async { a.begin("", "dsh-smoke-a") }
            val second = async { b.begin("", "dsh-smoke-b") }
            val rawA = first.await()
            val rawB = second.await()
            fun summary(raw: String, logs: String): JsonObject {
                val obj = Json.parseToJsonElement(raw) as JsonObject
                val metadata = obj.filterKeys { it in setOf("stage", "state", "error") }
                val log = logs.replace(Regex("https?://[^\\s\\\"]+"), "<url>")
                return JsonObject(metadata + ("log" to JsonPrimitive(log)))
            }
            File(context.getExternalFilesDir(null), "tailscale-native-smoke.json").writeText(
                JsonObject(mapOf("a" to summary(rawA, a.logsJson()), "b" to summary(rawB, b.logsJson()))).toString()
            )
            val statusA = TailscalePolicy.parseStatus(rawA, true, true)
            val statusB = TailscalePolicy.parseStatus(rawB, true, true)
            println("Tailscale native login links ready in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
            assertNotNull("节点 A 应返回登录链接", statusA.authUrl)
            assertNotNull("节点 B 应返回登录链接", statusB.authUrl)
            assertTrue("节点应拥有不同的登录身份", statusA.authUrl != statusB.authUrl)
            assertTrue("候选节点应能单独关闭", b.close())
            val after = TailscalePolicy.parseStatus(a.status(), false, false)
            assertEquals(TailscaleStage.NEEDS_LOGIN, after.stage)
            assertTrue("关闭候选节点不能改变当前节点的授权链接", statusA.authUrl == after.authUrl)
        } finally {
            val closedA = a.close()
            val closedB = b.close()
            if (closedA && closedB) root.deleteRecursively()
        }
    }
}
