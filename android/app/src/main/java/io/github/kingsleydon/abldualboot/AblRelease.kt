package io.github.kingsleydon.abldualboot

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The official ROCKNIX ABL release, downloaded only when the user installs or restores the bootloader.
 * The archive must match the SHA-256 pinned in abl.properties at build time, and the extracted file must
 * match the per-file checksum ROCKNIX ships inside the archive.
 */
object AblRelease {
    val version: String get() = BuildConfig.ABL_VERSION
    val url: String get() = "https://github.com/ROCKNIX/abl/releases/download/$version/rocknix-abl-$version.tar.gz"

    class VerificationException(message: String) : Exception(message)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun download(): ByteArray = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 60_000
        inputStream.use { it.readBytes() }
    }

    /** Returns the verified ABL ELF for [soc] (e.g. SM8550) from a downloaded release archive. */
    fun extract(archive: ByteArray, soc: String, archiveSha256: String = BuildConfig.ABL_TARBALL_SHA256): ByteArray {
        if (sha256(archive) != archiveSha256) throw VerificationException("Downloaded bootloader archive does not match the pinned checksum")
        val files = untar(GZIPInputStream(ByteArrayInputStream(archive)).readBytes())
        val name = "abl_signed-$soc.elf"
        val elf = files.entries.firstOrNull { it.key.substringAfterLast('/') == name }?.value
            ?: throw VerificationException("No ROCKNIX ABL for $soc in release $version")
        val expected = files.entries.firstOrNull { it.key.substringAfterLast('/') == "$name.sha256" }?.value
            ?.toString(Charsets.US_ASCII)?.trim()?.substringBefore(' ')
            ?: throw VerificationException("Release $version has no checksum for $name")
        if (sha256(elf) != expected) throw VerificationException("$name does not match its checksum")
        return elf
    }

    /** Minimal ustar reader: regular files only, which is all the ROCKNIX release contains. */
    internal fun untar(tar: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var offset = 0
        while (offset + 512 <= tar.size) {
            val header = tar.copyOfRange(offset, offset + 512)
            if (header.all { it == 0.toByte() }) break
            fun field(start: Int, length: Int) = String(header, start, length, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            val prefix = field(345, 155)
            val name = field(0, 100).let { if (prefix.isNotEmpty()) "$prefix/$it" else it }
            val size = field(124, 12).trim().ifEmpty { "0" }.toLong(8).toInt()
            val type = header[156].toInt().toChar()
            offset += 512
            if (type == '0' || type == '\u0000') files[name] = tar.copyOfRange(offset, offset + size)
            offset += (size + 511) / 512 * 512
        }
        return files
    }
}
