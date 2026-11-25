package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalSelectionTest {
    private val a = Account("1", "A", "ua", "pa")
    private val b = Account("2", "B", "ub", "pb")

    private fun data(active: String?, vararg portals: Portal) =
        AppData(listOf(a, b), portals.toList(), active)

    @Test
    fun unknownRedirectHostIsReported() {
        val r = pickPortal(data("1", Portal("a.com", "1")), "lain.com")
        assertEquals(PortalPick.Unknown("lain.com"), r)
    }

    @Test
    fun noPortalsAndNoRedirectFails() {
        val r = pickPortal(AppData(listOf(a), emptyList(), "1"), null)
        assertEquals(PortalPick.Failed("Belum ada portal tersimpan."), r)
    }

    @Test
    fun matchingHostUsesItsAccount() {
        val r = pickPortal(data(null, Portal("a.com", "2")), "a.com")
        assertEquals(PortalPick.Found(b, Portal("a.com", "2")), r)
    }

    @Test
    fun activeAccountIsPreferredWhenSeveralOwnTheHost() {
        val d = data("2", Portal("a.com", "1"), Portal("a.com", "2"))
        assertEquals(PortalPick.Found(b, Portal("a.com", "2")), pickPortal(d, "a.com"))
    }

    @Test
    fun fallsBackToFirstCandidateWhenActiveDoesNotOwnHost() {
        val d = data("1", Portal("a.com", "2"), Portal("b.com", "1"))
        assertEquals(PortalPick.Found(b, Portal("a.com", "2")), pickPortal(d, "a.com"))
    }

    @Test
    fun withoutRedirectHostUsesActiveAccountsFirstPortal() {
        val d = data("2", Portal("a.com", "1"), Portal("b.com", "2"))
        assertEquals(PortalPick.Found(b, Portal("b.com", "2")), pickPortal(d, null))
    }

    @Test
    fun withoutRedirectHostAndNoActiveUsesFirstAccountThenFirstPortal() {
        val d = data(null, Portal("a.com", "1"), Portal("b.com", "2"))
        assertEquals(PortalPick.Found(a, Portal("a.com", "1")), pickPortal(d, null))
    }

    @Test
    fun portalWhoseAccountWasDeletedFails() {
        val d = AppData(listOf(a), listOf(Portal("a.com", "hilang")), "1")
        val r = pickPortal(d, "a.com")
        assertEquals(PortalPick.Failed("Akun untuk portal ini tidak ditemukan."), r)
    }

    @Test
    fun hostMatchIsExactNotSubstring() {
        val d = data("1", Portal("a.com", "1"))
        assertTrue(pickPortal(d, "x.a.com") is PortalPick.Unknown)
    }
}
