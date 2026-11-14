package com.cue.offlinebrain.data

import java.security.MessageDigest
import java.util.Locale

object WorkerCacheKeys {
    fun weather(city: String): String = "weather:" + city.trim().lowercase(Locale.ROOT).take(80)
    fun headlines(feedUrl: String): String = "headlines:" + digest(feedUrl)
    fun customApi(url: String, jsonPath: String, headers: Map<String, String>): String =
        "custom:" + digest(url.trim() + "\n" + jsonPath.trim() + "\n" + headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" })

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object WorkerCachePolicy {
    const val MAX_AGE_MS = 36L * 60L * 60L * 1000L
    fun isFresh(updatedAt: Long, now: Long): Boolean = updatedAt in (now - MAX_AGE_MS)..now
}
