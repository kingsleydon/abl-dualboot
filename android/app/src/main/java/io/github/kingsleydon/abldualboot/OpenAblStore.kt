package io.github.kingsleydon.abldualboot

import java.util.zip.CRC32

/** OpenABL SettingsStore/Settings at 2494196. Never creates or repairs a store. */
object OpenAblStore {
    private data class Record(val name: List<Byte>, val start: Int, val data: Int, val end: Int)
    private val sources = listOf("auto", "internal", "sd", "usb")

    private fun u8(store: ByteArray, at: Int) = store[at].toInt() and 255
    private fun u16(store: ByteArray, at: Int) = u8(store, at) or (u8(store, at + 1) shl 8)
    private fun crc(store: ByteArray) = CRC32().apply { update(store, 16, 4080) }.value

    private fun records(store: ByteArray, sealed: Boolean = true): List<Record> {
        require(store.size == 4096 && store.copyOfRange(0, 8).contentEquals("OABLVARS".toByteArray(Charsets.US_ASCII)) &&
            u16(store, 8) == 1 && u16(store, 10) == 4096) { OpenAbl.ERROR }
        val checksum = (0..3).fold(0L) { n, i -> n or (u8(store, 12 + i).toLong() shl (8 * i)) }
        require(!sealed || checksum == crc(store)) { OpenAbl.ERROR }
        val result = ArrayList<Record>()
        val names = HashSet<List<Byte>>()
        var at = 16
        while (at < 4096 && u8(store, at) != 0) {
            val length = u8(store, at)
            val data = at + 3 + length
            require(length <= 64 && data <= 4096) { OpenAbl.ERROR }
            val end = data + u16(store, data - 2)
            val name = store.copyOfRange(at + 1, at + 1 + length).toList()
            require(end <= 4096 && names.add(name)) { OpenAbl.ERROR }
            result.add(Record(name, at, data, end))
            at = end
        }
        return result
    }

    fun valid(store: ByteArray): Boolean = try { records(store); true } catch (_: IllegalArgumentException) { false }

    private fun get(store: ByteArray, records: List<Record>, name: String): ByteArray {
        val record = records.find { it.name == name.toByteArray(Charsets.US_ASCII).toList() } ?: return byteArrayOf()
        return store.copyOfRange(record.data, record.end)
    }

    private fun byte(store: ByteArray, records: List<Record>, name: String, maximum: Int): Int {
        val data = get(store, records, name)
        return if (data.size == 1 && u8(data, 0) <= maximum) u8(data, 0) else 0
    }

    fun status(store: ByteArray): String {
        val records = records(store)
        val mode = byte(store, records, "BootMode", 1)
        val source = byte(store, records, "BootSource", 3)
        return "${if (mode == 1) "android" else "linux"} ${sources[source]}"
    }

    private fun set(store: ByteArray, name: String, value: Int) {
        val records = records(store, sealed = false)
        val key = name.toByteArray(Charsets.US_ASCII)
        val old = records.find { it.name == key.toList() }
        if (old == null && value == 0) return // StoreHolds skips a missing default.
        if (old != null && value != 0 && old.end - old.data == 1) {
            store[old.data] = value.toByte()
            return
        }
        val last = records.lastOrNull()?.end ?: 16
        val end = last - (old?.let { it.end - it.start } ?: 0)
        require(value == 0 || end + 3 + key.size + 1 <= 4096) { "OpenABL settings store is full - nothing written" }
        if (old != null) store.copyInto(store, old.start, old.end, last)
        store.fill(0, end)
        if (value != 0) {
            store[end] = key.size.toByte()
            key.copyInto(store, end + 1)
            store[end + 1 + key.size] = 1
            store[end + 3 + key.size] = value.toByte()
        }
    }

    fun edit(before: ByteArray, target: String, location: String = "", internal: Boolean = false): ByteArray {
        val records = records(before)
        require(target == "android" || target == "linux") { "target must be android or linux" }
        require(location.isEmpty() || location in listOf("internal", "sd", "usb")) { "location must be sd, internal or usb" }
        var source = byte(before, records, "BootSource", 3)
        if (target == "linux") {
            if (location.isNotEmpty()) source = sources.indexOf(location)
            else if (source == 1 && !internal) source = 2
        }
        val store = before.copyOf()
        set(store, "BootMode", if (target == "android") 1 else 0)
        set(store, "BootSource", source)
        val checksum = crc(store)
        for (i in 0..3) store[12 + i] = (checksum ushr (8 * i)).toByte()
        records(store)
        return store
    }
}
