package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

/** Enkripsi tiruan: awalan "enc:" + teks dibalik. Bisa dibuat gagal untuk meniru kunci hilang. */
private class FakeCodec : SecretCodec {
    var broken = false
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(encoded: String): String {
        if (broken || !encoded.startsWith("enc:")) throw IllegalStateException("kunci tidak valid")
        return encoded.removePrefix("enc:").reversed()
    }
}

class AccountRepositoryTest {
    private val store = FakeStore()
    private val codec = FakeCodec()
    private val repo = AccountRepository(store, codec)

    private val sample = AppData(
        accounts = listOf(
            Account("id-1", "Akun \"A\"", "user.a", "p@ss\\wörd,{}[]\n"),
            Account("id-2", "B", "user.b", "pb")
        ),
        portals = listOf(Portal("ypbpi.hotspot", "id-1"), Portal("portal.contoh", "id-2")),
        activeAccountId = "id-2"
    )

    @Test
    fun load_withNothingStoredReturnsEmptyWithoutFailure() {
        assertEquals(AppData(), repo.load())
        assertFalse(repo.lastLoadFailed)
    }

    @Test
    fun saveThenLoad_roundTripsAllFields() {
        repo.save(sample)
        assertEquals(sample, repo.load())
        assertFalse(repo.lastLoadFailed)
    }

    @Test
    fun save_storesCiphertextNotPlainJson() {
        repo.save(sample)
        val raw = store.map[AccountRepository.DATA_KEY]!!
        assertTrue(raw.startsWith("enc:"))
        assertFalse(raw.contains("user.a"))
    }

    @Test
    fun roundTrip_noActiveAccountStaysNull() {
        repo.save(sample.copy(activeAccountId = null))
        assertNull(repo.load().activeAccountId)
    }

    @Test
    fun roundTrip_emptyData() {
        repo.save(AppData())
        assertEquals(AppData(), repo.load())
    }

    @Test
    fun load_undecryptableDataReturnsEmptyAndFlagsFailure() {
        repo.save(sample)
        codec.broken = true
        assertEquals(AppData(), repo.load())
        assertTrue(repo.lastLoadFailed)
    }

    @Test
    fun load_corruptJsonReturnsEmptyAndFlagsFailure() {
        store.map[AccountRepository.DATA_KEY] = "enc:" + "bukan json".reversed()
        assertEquals(AppData(), repo.load())
        assertTrue(repo.lastLoadFailed)
    }

    @Test
    fun load_jsonMissingRequiredFieldIsTreatedAsFailure() {
        store.map[AccountRepository.DATA_KEY] = "enc:" + """{"accounts":[{"id":"1"}],"portals":[]}""".reversed()
        assertEquals(AppData(), repo.load())
        assertTrue(repo.lastLoadFailed)
    }

    @Test
    fun save_afterFailedLoadKeepsUnreadableBlobBeforeOverwriting() {
        repo.save(sample)
        val original = store.map[AccountRepository.DATA_KEY]!!
        codec.broken = true
        repo.load()
        codec.broken = false

        val fresh = AppData(listOf(Account("n", "Baru", "u", "p")), emptyList(), null)
        repo.save(fresh)

        assertEquals(original, store.map[AccountRepository.UNREADABLE_KEY])
        assertNotEquals(original, store.map[AccountRepository.DATA_KEY])
        assertEquals(fresh, repo.load())
        assertFalse(repo.lastLoadFailed)
    }

    @Test
    fun save_withoutPriorFailureCreatesNoBackup() {
        repo.save(sample)
        repo.save(sample.copy(activeAccountId = "id-1"))
        assertNull(store.map[AccountRepository.UNREADABLE_KEY])
    }

    @Test
    fun save_backupHappensOnlyOncePerFailure() {
        repo.save(sample)
        val original = store.map[AccountRepository.DATA_KEY]!!
        codec.broken = true
        repo.load()
        codec.broken = false
        repo.save(AppData())
        repo.save(sample)
        assertEquals(original, store.map[AccountRepository.UNREADABLE_KEY])
    }

    @Test
    fun appDataJson_parseThenToJsonIsStable() {
        val json = AppDataJson.toJson(sample)
        assertEquals(sample, AppDataJson.parse(json))
        assertEquals(json, AppDataJson.toJson(AppDataJson.parse(json)))
    }
}
