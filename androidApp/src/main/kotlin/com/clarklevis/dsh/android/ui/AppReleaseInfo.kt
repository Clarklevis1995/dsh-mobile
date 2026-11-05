package com.clarklevis.dsh.android.ui

import android.content.Context
import java.util.Locale
import com.clarklevis.dsh.android.localization.appText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AppReleaseInfo(val version: String, val minimumGatewayVersion: String, val notes: String) {
    companion object {
        fun load(context: Context): AppReleaseInfo? = runCatching {
            val metadata = context.assets.open("release-info.json").bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(metadata).jsonObject
            val notesName = if (Locale.getDefault().language == "zh") "release-notes.md" else "release-notes.en.md"
            val notes = context.assets.open(notesName).bufferedReader().use { it.readText() }
            AppReleaseInfo(json.getValue("version").jsonPrimitive.content,
                json.getValue("minimumGatewayVersion").jsonPrimitive.content, notes)
        }.getOrNull()
    }
}

internal fun dshHostVersion(dshVersion: String?, legacyVersion: String?): String =
    listOfNotNull(dshVersion, legacyVersion).firstOrNull { Regex("v?\\d+\\.\\d+.*").matches(it) } ?: appText("未提供")

internal fun resolveReleaseUrl(repository: String, version: String?): String {
    val root = "https://github.com/Clarklevis1995/$repository"
    val fallback = if (repository == "dsh-mobile") "$root/releases" else root
    if (version.isNullOrBlank()) return fallback
    val tag = "v${version.removePrefix("v")}"
    val candidate = "$root/releases/tag/${android.net.Uri.encode(tag)}"
    return runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(okhttp3.Request.Builder().url(candidate).head().build()).execute().use {
            if (it.isSuccessful) candidate else fallback
        }
    }.getOrDefault(fallback)
}
