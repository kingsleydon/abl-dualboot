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
            "openabl-misc.bin" to AblKind.OPENABL,
            "misc-only.bin" to AblKind.ROCKNIX,
            "rocknix.bin" to AblKind.ROCKNIX,
            "stock.bin" to AblKind.STOCK,
            "unknown.bin" to AblKind.UNKNOWN,
            "invalid-lzma.bin" to AblKind.ROCKNIX,
        )) assertEquals(name, expected, OpenAbl.inspect(fixture(name), catalog).kind)
    }

    @Test fun matchesAnImagePrefixWithPartitionPadding() {
        val image = fixture("openabl-hash.bin") + ByteArray(4096)
        assertEquals(AblKind.OPENABL, OpenAbl.inspect(image, catalog).kind)
        image[5] = 1
        assertEquals(AblKind.UNKNOWN, OpenAbl.inspect(image, catalog).kind)
    }

    @Test fun boundsMarkerDecompressionAndInput() {
        assertEquals(AblKind.UNKNOWN, OpenAbl.inspect(fixture("oversized-marker.bin"), catalog).kind)
        assertFalse(OpenAbl.inspect(ByteArray(1048576) + fixture("openabl-misc.bin"), catalog).miscCapable)
    }

    @Test fun miscRequiresTwoPositivelyIdentifiedCapableOpenAblSlots() {
        val custom = OpenAbl.inspect(fixture("openabl-misc.bin"), catalog)
        assertTrue(OpenAbl.canUseMisc(listOf(custom, custom)))
        for (name in listOf(
            "rocknix.bin", "stock.bin", "unknown.bin", "openabl-hash.bin",
            "openabl-marker.bin", "misc-only.bin", "oversized-misc.bin",
        )) {
            val other = OpenAbl.inspect(fixture(name), catalog)
            for (slots in listOf(listOf(custom, other), listOf(other, custom), listOf(other, other))) {
                assertFalse(name, OpenAbl.canUseMisc(slots))
            }
        }
        assertFalse(OpenAbl.canUseMisc(listOf(custom)))
        assertFalse(OpenAbl.canUseMisc(emptyList()))
    }

    @Test fun miscCapabilityIsIndependentOfVersionAndReleaseHash() {
        val image = fixture("openabl-misc.bin")
        val released = listOf(OpenAbl.Image(image.size, sha256(image)))
        val inspection = OpenAbl.inspect(image, released)
        assertTrue(OpenAbl.canUseMisc(listOf(inspection, inspection)))
        assertFalse(OpenAbl.inspect(fixture("openabl-marker.bin") + "OABLVARS".toByteArray(), catalog).miscCapable)
        assertFalse(OpenAbl.canUseMisc(listOf(
            OpenAbl.Inspection(AblKind.ROCKNIX, true), OpenAbl.Inspection(AblKind.ROCKNIX, true),
        )))
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
