package io.github.kingsleydon.abldualboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAblTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    private val catalog = OpenAbl.catalog(fixture("fixture-images.txt").toString(Charsets.US_ASCII))

    @Test fun classifiesAllKindsBeforeCheckingSigningCertificates() {
        for ((name, expected) in listOf(
            "openabl-hash.bin" to AblKind.OPENABL,
            "openabl-marker.bin" to AblKind.OPENABL,
            "rocknix.bin" to AblKind.ROCKNIX,
            "stock.bin" to AblKind.STOCK,
            "unknown.bin" to AblKind.UNKNOWN,
            "invalid-lzma.bin" to AblKind.ROCKNIX,
        )) assertEquals(name, expected, OpenAbl.kind(fixture(name), catalog))
    }

    @Test fun matchesAnImagePrefixWithPartitionPadding() {
        val image = fixture("openabl-hash.bin") + ByteArray(4096)
        assertEquals(AblKind.OPENABL, OpenAbl.kind(image, catalog))
        image[5] = 1
        assertEquals(AblKind.UNKNOWN, OpenAbl.kind(image, catalog))
    }

    @Test fun boundsMarkerDecompressionAndInput() {
        assertFalse(OpenAbl.hasMarker(fixture("oversized-marker.bin")))
        assertFalse(OpenAbl.hasMarker(ByteArray(1048576) + fixture("openabl-marker.bin")))
    }

    @Test fun releaseCatalogCoversEveryReleasedVersionAndChip() {
        val text = fixture("openabl-images.txt").toString(Charsets.US_ASCII)
        val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(' ') }
        assertEquals(28, OpenAbl.catalog(text).size)
        for (version in listOf("1.0", "1.1", "1.2")) {
            assertEquals(9, rows.filter { it[0] == version }.map { it[1] }.distinct().size)
        }
    }

    @Test fun refusesOpenAblOnEitherSlotWithoutOfferingOtaRestore() {
        for (other in AblKind.entries) {
            for (status in listOf(AblStatus("SM8550", AblKind.OPENABL, other), AblStatus("SM8550", other, AblKind.OPENABL))) {
                assertTrue(status.openabl)
                assertFalse(status.installed)
                assertFalse(status.replacedByUpdate)
                assertEquals(OpenAbl.ERROR, status.blockingError)
            }
        }
        assertTrue(AblStatus("SM8550", AblKind.ROCKNIX, AblKind.ROCKNIX).installed)
        assertTrue(AblStatus("SM8550", AblKind.ROCKNIX, AblKind.STOCK).replacedByUpdate)
        assertNull(AblStatus("SM8550", AblKind.STOCK, AblKind.STOCK).blockingError)
    }
}
