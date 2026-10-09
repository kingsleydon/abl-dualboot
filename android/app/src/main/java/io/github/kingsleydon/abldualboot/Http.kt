package io.github.kingsleydon.abldualboot

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GET request with this app's User-Agent and sensible timeouts. */
fun httpGet(url: String, accept: String? = null): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    accept?.let { setRequestProperty("Accept", it) }
    setRequestProperty("User-Agent", "abl-dualboot/${BuildConfig.VERSION_NAME}")
    connectTimeout = 15_000
    readTimeout = 60_000
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
