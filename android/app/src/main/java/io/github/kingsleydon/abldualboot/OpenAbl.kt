package io.github.kingsleydon.abldualboot

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Release prefix hashes, then the compressed marker used by OpenABL-Android-Installer. */
object OpenAbl {
    const val ERROR = "OpenABL is not supported yet - nothing written"
    private const val MAX_IMAGE = 1048576
    private const val MAX_OUTPUT = 4 shl 20
    private val marker = Regex("OpenABL\u0000[0-9]+\\.[0-9]+\u0000")

    data class Image(val size: Int, val sha: String)
    data class Inspection(val kind: AblKind, val miscCapable: Boolean)

    fun canUseMisc(slots: List<Inspection>) = slots.size == 2 && slots.all { it.miscCapable }

    fun catalog(text: String): List<Image> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val fields = line.split(' ')
            require(fields.size == 4) { "Invalid OpenABL image catalog" }
            Image(fields[3].toInt(), fields[2])
        }.toList().also { require(it.isNotEmpty()) { "Empty OpenABL image catalog" } }

    fun released(image: ByteArray, catalog: List<Image>): Boolean {
        val hashes = catalog.map { it.size }.distinct().filter { it <= image.size }
            .associateWith { sha256(image.copyOfRange(0, it)) }
        return catalog.any { hashes[it.size] == it.sha }
    }

    fun inspect(image: ByteArray, catalog: List<Image>): Inspection {
        val released = released(image, catalog)
        val marker = !released && hasMarker(image)
        val text = image.toString(Charsets.ISO_8859_1)
        val kind = when {
            released || marker -> AblKind.OPENABL
            "qtestsign" in text -> AblKind.ROCKNIX
            "QUALCOMM" in text -> AblKind.STOCK
            else -> AblKind.UNKNOWN
        }
        return Inspection(kind, marker)
    }

    fun hasMarker(image: ByteArray): Boolean {
        val size = minOf(image.size, MAX_IMAGE)
        for (at in 0 until minOf(65536, size - 12)) {
            if (image[at] != 0x5d.toByte() || image[at + 1] != 0.toByte()) continue
            val dictionary = (1..4).fold(0L) { n, i -> n or ((image[at + i].toLong() and 255) shl ((i - 1) * 8)) }
            if (dictionary == 0L || dictionary and (dictionary - 1) != 0L) continue
            try {
                val output = ByteArrayOutputStream()
                LZMAInputStream(ByteArrayInputStream(image, at, size - at), 64 * 1024).use { input ->
                    val buffer = ByteArray(65536)
                    while (output.size() < MAX_OUTPUT) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                if (marker.containsMatchIn(output.toString(Charsets.ISO_8859_1.name()))) return true
            } catch (_: IOException) {
                // A plausible LZMA header can also occur in an unrelated image.
            }
        }
        return false
    }
}
