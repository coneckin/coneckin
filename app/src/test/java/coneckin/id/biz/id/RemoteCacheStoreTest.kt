package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class InMemoryCacheStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

class RemoteCacheStoreTest {
    private val storage = InMemoryCacheStore()
    private val cache = RemoteCacheStore(storage)

    @Test
    fun portals_returnsDefaultWhenEmpty() {
        assertEquals(DEFAULT_DIRECTORY, cache.portals())
    }

    @Test
    fun savePortals_thenReadRoundTrips() {
        val list = listOf(
            DirectoryPortal("ULBI", "ypbpi.hotspot"),
            DirectoryPortal("Kampus B", "wifi.kampusb.ac.id")
        )
        cache.savePortals(list)
        assertEquals(list, cache.portals())
    }

    @Test
    fun portals_fallsBackToDefaultOnCorruptOrEmptyJson() {
        storage.map[RemoteCacheStore.KEY_PORTALS] = "{rusak"
        assertEquals(DEFAULT_DIRECTORY, cache.portals())

        storage.map[RemoteCacheStore.KEY_PORTALS] = """{"portals":[]}"""
        assertEquals(DEFAULT_DIRECTORY, cache.portals())
    }

    @Test
    fun config_returnsNullWhenEmpty() {
        assertNull(cache.config())
    }

    @Test
    fun saveConfig_roundTripsFullAndNullFields() {
        val full = RemoteConfig("1.0.14", 14L, 10L, "Pemeliharaan malam ini")
        cache.saveConfig(full)
        assertEquals(full, cache.config())

        val minimal = RemoteConfig(null, null, 0L, "")
        cache.saveConfig(minimal)
        assertEquals(minimal, cache.config())
    }

    @Test
    fun config_returnsNullOnCorruptJson() {
        storage.map[RemoteCacheStore.KEY_CONFIG] = "bukan-json"
        assertNull(cache.config())
    }
}
