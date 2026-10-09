package io.github.kingsleydon.abldualboot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class AblReleaseTest {
    private val elf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3)

    private fun header(name: String, size: Int): ByteArray = ByteArray(512).also { h ->
        name.toByteArray().copyInto(h, 0)
        "%011o".format(size).toByteArray().copyInto(h, 124)
        h[156] = '0'.code.toByte()
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        files.forEach { (name, data) ->
            tar.write(header(name, data.size))
            tar.write(data)
            tar.write(ByteArray((512 - data.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    private fun release(elfSha: String = sha256(elf)) = archive(
        mapOf(
            "rocknix-abl-v1.2/abl_signed-SM8550.elf" to elf,
            "rocknix-abl-v1.2/abl_signed-SM8550.elf.sha256" to "$elfSha  abl_signed-SM8550.elf\n".toByteArray(),
        ),
    )

    @Test fun extractsVerifiedElf() {
        val a = release()
        assertArrayEquals(elf, AblRelease.extract(a, "SM8550", sha256(a)))
    }

    @Test fun rejectsArchiveWithWrongPinnedChecksum() {
        assertThrows(AblRelease.VerificationException::class.java) { AblRelease.extract(release(), "SM8550", "0".repeat(64)) }
    }

    @Test fun rejectsElfWithWrongChecksum() {
        val a = release(elfSha = "f".repeat(64))
        assertThrows(AblRelease.VerificationException::class.java) { AblRelease.extract(a, "SM8550", sha256(a)) }
    }

    @Test fun rejectsUnknownSoc() {
        val a = release()
        assertThrows(AblRelease.VerificationException::class.java) { AblRelease.extract(a, "SM9999", sha256(a)) }
    }
}
