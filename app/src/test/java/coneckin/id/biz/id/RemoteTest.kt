package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTest {

    @Test
    fun parsePortals_readsValidEntries() {
        val json = """{"portals":[{"name":"ULBI","host":"YPBPI.hotspot"}]}"""
        assertEquals(listOf(DirectoryPortal("ULBI", "ypbpi.hotspot")), parsePortals(json))
    }

    @Test
    fun parsePortals_skipsInvalidHosts() {
        val json = """{"portals":[
            {"name":"Buruk","host":"bad host"},
            {"name":"Kosong","host":""},
            {"name":"Baik","host":"ok.hotspot"}]}"""
        assertEquals(listOf(DirectoryPortal("Baik", "ok.hotspot")), parsePortals(json))
    }

    @Test
    fun parsePortals_usesHostWhenNameBlankAndLimitsNameLength() {
        val long = "x".repeat(100)
        val json = """{"portals":[
            {"name":"  ","host":"a.hotspot"},
            {"name":"$long","host":"b.hotspot"}]}"""
        val result = parsePortals(json)
        assertEquals("a.hotspot", result[0].name)
        assertEquals(60, result[1].name.length)
    }

    @Test
    fun parseConfig_readsAllFields() {
        val json = """{"latestVersion":"1.0.7","latestCode":7,
            "minSupportedCode":3,"message":"  Halo  "}"""
        val c = parseConfig(json)
        assertEquals("1.0.7", c.latestVersion)
        assertEquals(7L, c.latestCode)
        assertEquals(3L, c.minSupportedCode)
        assertEquals("Halo", c.message)
    }

    @Test
    fun parseConfig_handlesNullsAndDefaults() {
        val c = parseConfig("""{"latestVersion":null,"latestCode":null,"message":null}""")
        assertNull(c.latestVersion)
        assertNull(c.latestCode)
        assertEquals(0L, c.minSupportedCode)
        assertEquals("", c.message)
    }

    @Test
    fun parseConfig_truncatesLongValues() {
        val json = """{"latestVersion":"${"9".repeat(50)}","message":"${"m".repeat(900)}"}"""
        val c = parseConfig(json)
        assertEquals(20, c.latestVersion!!.length)
        assertEquals(500, c.message.length)
    }
}
