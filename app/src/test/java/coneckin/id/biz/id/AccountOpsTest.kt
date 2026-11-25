package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountOpsTest {
    private val a = Account("1", "A", "ua", "pa")
    private val b = Account("2", "B", "ub", "pb")
    private val base = AppData(
        accounts = listOf(a, b),
        portals = listOf(Portal("ypbpi.hotspot", "1"), Portal("portal.contoh", "2")),
        activeAccountId = "1"
    )

    // ---------- checkAccountInput ----------

    @Test
    fun checkAccountInput_validTrimsAndNormalizes() {
        val r = checkAccountInput("  Akun A ", " user ", " pw ", "HTTP://YPBPI.hotspot/login")
        assertEquals(InputCheck.Valid("Akun A", "user", " pw ", "ypbpi.hotspot"), r)
    }

    @Test
    fun checkAccountInput_missingFields() {
        assertEquals(InputCheck.MissingFields, checkAccountInput("", "u", "p", "a.b"))
        assertEquals(InputCheck.MissingFields, checkAccountInput("n", "   ", "p", "a.b"))
        assertEquals(InputCheck.MissingFields, checkAccountInput("n", "u", "", "a.b"))
    }

    @Test
    fun checkAccountInput_passwordOfSpacesIsAllowed() {
        assertTrue(checkAccountInput("n", "u", "   ", "a.b") is InputCheck.Valid)
    }

    @Test
    fun checkAccountInput_invalidHost() {
        assertEquals(InputCheck.InvalidHost, checkAccountInput("n", "u", "p", "bad host"))
        assertEquals(InputCheck.InvalidHost, checkAccountInput("n", "u", "p", ""))
    }

    @Test
    fun checkAccountInput_missingFieldsWinOverInvalidHost() {
        assertEquals(InputCheck.MissingFields, checkAccountInput("", "u", "p", "bad host"))
    }

    // ---------- Create ----------

    @Test
    fun withAccount_addsAccountAndPortalAndSelectsIt() {
        val c = Account("3", "C", "uc", "pc")
        val r = base.withAccount(c, "kampus.c")
        assertEquals(listOf(a, b, c), r.accounts)
        assertTrue(Portal("kampus.c", "3") in r.portals)
        assertEquals(3, r.portals.size)
        assertEquals("3", r.activeAccountId)
    }

    @Test
    fun withAccount_onEmptyData() {
        val r = AppData().withAccount(a, "x.y")
        assertEquals(listOf(a), r.accounts)
        assertEquals(listOf(Portal("x.y", "1")), r.portals)
        assertEquals(a, r.activeAccount())
    }

    // ---------- Delete ----------

    @Test
    fun withoutAccount_cascadesPortals() {
        val r = base.withoutAccount("2")
        assertEquals(listOf(a), r.accounts)
        assertEquals(listOf(Portal("ypbpi.hotspot", "1")), r.portals)
    }

    @Test
    fun withoutAccount_resetsActiveOnlyWhenDeletingActive() {
        assertNull(base.withoutAccount("1").activeAccountId)
        assertEquals("1", base.withoutAccount("2").activeAccountId)
    }

    @Test
    fun withoutAccount_unknownIdChangesNothing() {
        assertEquals(base, base.withoutAccount("tidak-ada"))
    }

    // ---------- Update ----------

    @Test
    fun withUpdatedAccount_changesFieldsAndKeepsIdAndActive() {
        val r = base.withUpdatedAccount("1", "A baru", "ua2", "pa2", listOf("ypbpi.hotspot"))!!
        assertEquals(Account("1", "A baru", "ua2", "pa2"), r.accounts.first { it.id == "1" })
        assertEquals(b, r.accounts.first { it.id == "2" })
        assertEquals("1", r.activeAccountId)
        assertEquals(2, r.accounts.size)
    }

    @Test
    fun withUpdatedAccount_replacesOnlyThisAccountsPortals() {
        val r = base.withUpdatedAccount("1", "A", "ua", "pa", listOf("baru.satu", "baru.dua"))!!
        val mine = r.portals.filter { it.accountId == "1" }.map { it.host }
        assertEquals(listOf("baru.satu", "baru.dua"), mine)
        assertTrue(Portal("portal.contoh", "2") in r.portals)
        assertEquals(3, r.portals.size)
    }

    @Test
    fun withUpdatedAccount_normalizesAndDeduplicatesHosts() {
        val r = base.withUpdatedAccount(
            "1", "A", "ua", "pa",
            listOf("HTTP://Baru.Satu/login", "baru.satu", "bad host")
        )!!
        assertEquals(listOf("baru.satu"), r.portals.filter { it.accountId == "1" }.map { it.host })
    }

    @Test
    fun withUpdatedAccount_unknownIdReturnsNull() {
        assertNull(base.withUpdatedAccount("x", "A", "u", "p", listOf("a.b")))
    }

    @Test
    fun withUpdatedAccount_noValidHostReturnsNull() {
        assertNull(base.withUpdatedAccount("1", "A", "u", "p", emptyList()))
        assertNull(base.withUpdatedAccount("1", "A", "u", "p", listOf("bad host")))
    }

    @Test
    fun withUpdatedAccount_doesNotMutateOriginal() {
        base.withUpdatedAccount("1", "X", "x", "x", listOf("x.y"))
        assertEquals("A", base.accounts.first { it.id == "1" }.name)
    }
}
