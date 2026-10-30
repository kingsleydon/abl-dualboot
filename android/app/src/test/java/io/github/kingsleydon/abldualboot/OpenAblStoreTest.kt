package io.github.kingsleydon.abldualboot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAblStoreTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    private val rows = fixture("openabl-stores.txt").toString(Charsets.US_ASCII).lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(' ') }
    private val blob = fixture("openabl-stores.bin")
    private val stores = rows.filter { it[0] == "frame" }.associate { row ->
        row[1] to blob.copyOfRange(row[2].toInt(), row[2].toInt() + row[3].toInt())
    }
    private fun store(name: String) = requireNotNull(stores[name])

    @Test fun sharedGoldenEditsMatchUpstreamBytesAndStatus() {
        for (row in rows.filter { it[0] == "edit" }) {
            val before = store(row[1])
            val backup = before.copyOf()
            val after = OpenAblStore.edit(before, row[2], row[3].takeUnless { it == "-" } ?: "", row[4].toBooleanStrict())
            assertArrayEquals(row.toString(), store(row[5]), after)
            assertArrayEquals("input must not change", backup, before)
            assertTrue(OpenAblStore.valid(after))
            assertEquals(row[6] + " " + row[7], OpenAblStore.status(after))
        }
    }

    @Test fun readsDefaultsAndEverySourceLikeSettingsFromVars() {
        for (row in rows.filter { it[0] == "status" }) {
            assertEquals(row.toString(), row[2] + " " + row[3], OpenAblStore.status(store(row[1])))
        }
    }

    @Test fun refusesEveryInvalidLayoutWithoutRepairingIt() {
        for (row in rows.filter { it[0] == "invalid" }) {
            val before = store(row[1])
            val backup = before.copyOf()
            assertFalse(row[1], OpenAblStore.valid(before))
            assertThrows(row[1], IllegalArgumentException::class.java) { OpenAblStore.edit(before, "android") }
            assertArrayEquals(backup, before)
        }
    }

    @Test fun refusesFullStoreWithoutMutatingInput() {
        val before = store("full")
        val backup = before.copyOf()
        assertTrue(OpenAblStore.valid(before))
        assertThrows(IllegalArgumentException::class.java) { OpenAblStore.edit(before, "android") }
        assertArrayEquals(backup, before)
    }

    @Test fun enablesSwitchingOnlyAfterTheGateAndStillPreventsOtaRestore() {
        val supported = AblStatus("SM8550", AblKind.OPENABL, AblKind.OPENABL, miscSupported = true)
        assertTrue(supported.installed)
        assertNull(supported.blockingError)
        assertFalse(supported.replacedByUpdate)
        val refused = supported.copy(miscSupported = false)
        assertFalse(refused.installed)
        assertEquals(OpenAbl.ERROR, refused.blockingError)
        val catalog = OpenAbl.catalog(fixture("fixture-images.txt").toString(Charsets.US_ASCII))
        assertTrue(OpenAbl.released(fixture("openabl-hash.bin") + ByteArray(4096), catalog))
        assertFalse(OpenAbl.released(fixture("openabl-marker.bin"), catalog))
    }
}
