package com.clarklevis.dsh.android.update

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHttpRequestTest {
    @Test
    fun cancellingWhileWaitingForHeadersClosesConnectionWithoutFallback() = cancellationClosesConnection(sendHeaders = false)

    @Test
    fun cancellingWhileReadingBodyClosesConnectionWithoutFallback() = cancellationClosesConnection(sendHeaders = true)

    private fun cancellationClosesConnection(sendHeaders: Boolean) = runBlocking {
        ServerSocket(0).use { server ->
            val connected = CompletableDeferred<Unit>()
            val bodyReadStarted = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Unit>()
            var peer: Socket? = null
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        peer = socket
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { /* 读完 HTTP 请求头。 */ }
                        if (sendHeaders) {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                                flush()
                            }
                        }
                        connected.complete(Unit)
                        check(input.read() == -1)
                        disconnected.complete(Unit)
                    }
                } catch (exception: Exception) {
                    disconnected.completeExceptionally(exception)
                }
            }
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/update.apk").build())
            var fallbackStarted = false
            val download = launch(Dispatchers.Default) {
                withUpdateFallback(
                    primary = {
                        call.withUpdateResponse {
                            bodyReadStarted.complete(Unit)
                            requireNotNull(it.body).bytes()
                        }
                    },
                    fallback = { fallbackStarted = true; ByteArray(0) }
                )
            }
            try {
                withTimeout(5_000) {
                    connected.await()
                    if (sendHeaders) bodyReadStarted.await()
                    download.cancelAndJoin()
                    disconnected.await()
                }
                assertTrue(call.isCanceled())
                assertFalse(fallbackStarted)
            } finally {
                download.cancel()
                call.cancel()
                peer?.close()
                worker.join(1_000)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
