package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {

    @Test
    fun normalizeHost_stripsSchemePathAndCase() {
        assertEquals("ypbpi.hotspot", normalizeHost("http://YPBPI.hotspot/login"))
        assertEquals("ypbpi.hotspot", normalizeHost("https://ypbpi.hotspot/login?x=1"))
        assertEquals("ypbpi.hotspot", normalizeHost("  ypbpi.hotspot  "))
        assertEquals("ypbpi.hotspot", normalizeHost("ypbpi.hotspot?x=1"))
    }

    @Test
    fun normalizeHost_acceptsSingleCharacterAndIpv4() {
        assertEquals("a", normalizeHost("a"))
        assertEquals("192.168.1.1", normalizeHost("http://192.168.1.1/login"))
    }

    @Test
    fun normalizeHost_rejectsInvalidInput() {
        assertNull(normalizeHost(""))
        assertNull(normalizeHost("   "))
        assertNull(normalizeHost("bad host"))
        assertNull(normalizeHost("-bad.com"))
        assertNull(normalizeHost("bad.com-"))
    }

    @Test
    fun normalizeHost_rejectsCredentialTrickAndPort() {
        // "user@evil.com" tidak boleh lolos sebagai host.
        assertNull(normalizeHost("http://ypbpi.hotspot@evil.com/login"))
        assertNull(normalizeHost("ypbpi.hotspot:8080"))
    }

    @Test
    fun activeAccount_usesSelectedOrFallsBackToFirst() {
        val a = Account("1", "A", "u1", "p1")
        val b = Account("2", "B", "u2", "p2")
        assertNull(AppData().activeAccount())
        assertEquals(a, AppData(listOf(a, b)).activeAccount())
        assertEquals(b, AppData(listOf(a, b), activeAccountId = "2").activeAccount())
        assertEquals(a, AppData(listOf(a, b), activeAccountId = "tidak-ada").activeAccount())
    }
}
