package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

private class FakePinStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }
}

class AppPinTest {

    @Test
    fun setPin_storesSaltedPbkdf2HashAndVerifiesCorrectPin() {
        val store = FakePinStore()
        val manager = PinManager(store)
        assertFalse(manager.hasPin())

        manager.setPin("123456")
        assertTrue(manager.hasPin())

        val stored = store.map[PinManager.KEY_PIN_HASH]!!
        assertTrue(stored.startsWith("pbkdf2:100000:"))
        assertFalse(stored.contains("123456"))
        assertEquals(PinCheck.Success, manager.verifyPin("123456"))
        assertEquals(PinCheck.WrongPin, manager.verifyPin("654321"))
    }

    @Test
    fun setPin_usesUniqueRandomSaltEachTime() {
        val store1 = FakePinStore()
        val store2 = FakePinStore()
        PinManager(store1).setPin("4321")
        PinManager(store2).setPin("4321")

        assertNotEquals(
            store1.map[PinManager.KEY_PIN_HASH],
            store2.map[PinManager.KEY_PIN_HASH]
        )
    }

    @Test
    fun verifyPin_migratesLegacyUnsaltedSha256OnSuccess() {
        val store = FakePinStore()
        val legacyHex = MessageDigest.getInstance("SHA-256")
            .digest("2580".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        store.putString(PinManager.KEY_PIN_HASH, legacyHex)

        val manager = PinManager(store)
        assertEquals(PinCheck.WrongPin, manager.verifyPin("0000"))
        assertEquals(legacyHex, store.map[PinManager.KEY_PIN_HASH])

        assertEquals(PinCheck.Success, manager.verifyPin("2580"))
        val upgraded = store.map[PinManager.KEY_PIN_HASH]!!
        assertTrue(upgraded.startsWith("pbkdf2:100000:"))
        assertEquals(PinCheck.Success, manager.verifyPin("2580"))
    }

    @Test
    fun verifyPin_locksOutAfterFiveWrongAttemptsAndUnlocksAfterCooldown() {
        var now = 1_000_000L
        val store = FakePinStore()
        val manager = PinManager(store) { now }
        manager.setPin("1357")

        repeat(4) {
            assertEquals(PinCheck.WrongPin, manager.verifyPin("9999"))
            assertEquals(0, manager.remainingLockSeconds())
        }

        // Percobaan ke-5 memicu penguncian 30 detik.
        assertEquals(PinCheck.Locked(30), manager.verifyPin("9999"))
        assertEquals(30, manager.remainingLockSeconds())

        // Selama terkunci, PIN yang benar pun tetap ditolak sampai jeda selesai.
        now += 10_000L
        val midCheck = manager.verifyPin("1357")
        assertTrue(midCheck is PinCheck.Locked && midCheck.remainingSeconds == 20)

        // Setelah 30 detik berlalu, PIN benar diterima dan status bersih kembali.
        now += 20_000L
        assertEquals(0, manager.remainingLockSeconds())
        assertEquals(PinCheck.Success, manager.verifyPin("1357"))
    }

    @Test
    fun verifyPin_resetsFailCountAfterSuccessfulVerification() {
        val store = FakePinStore()
        val manager = PinManager(store)
        manager.setPin("2468")

        repeat(4) { assertEquals(PinCheck.WrongPin, manager.verifyPin("0000")) }
        assertEquals(PinCheck.Success, manager.verifyPin("2468"))

        // Harus butuh 5 kali salah baru lagi untuk terkunci.
        repeat(4) { assertEquals(PinCheck.WrongPin, manager.verifyPin("0000")) }
        assertEquals(PinCheck.Locked(30), manager.verifyPin("0000"))
    }

    @Test
    fun clearPin_removesPinAndClearsLockoutState() {
        val store = FakePinStore()
        val manager = PinManager(store) { 1_000_000L }
        manager.setPin("1234")
        repeat(5) { manager.verifyPin("9999") }
        assertEquals(30, manager.remainingLockSeconds())

        manager.clearPin()
        assertFalse(manager.hasPin())
        assertEquals(0, manager.remainingLockSeconds())
        assertEquals(PinCheck.WrongPin, manager.verifyPin("1234"))
    }
}
