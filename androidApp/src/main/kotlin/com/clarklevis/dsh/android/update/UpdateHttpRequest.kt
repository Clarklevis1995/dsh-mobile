package com.clarklevis.dsh.android.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/** 协程取消时同时关闭 socket，让等待响应头和阻塞读取响应体都能立即结束。 */
internal suspend fun <T> Call.withUpdateResponse(read: suspend (Response) -> T): T = coroutineScope {
    val call = this@withUpdateResponse
    val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            try {
                call.execute().use { read(it) }
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                throw exception
            }
        }
    } finally {
        cancellation.cancel()
    }
}
