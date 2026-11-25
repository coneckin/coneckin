package coneckin.id.biz.id

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pengujian integrasi end-to-end AccountStore dengan SharedPreferences
 * dan CryptoManager (AndroidKeyStore) asli.
 * Data asli pengguna pada coneckin_secure dicadangkan lalu dipulihkan otomatis.
 */
@RunWith(AndroidJUnit4::class)
class AccountStoreInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var backupData: String? = null
    private var backupUnreadable: String? = null

    @Before
    fun backupExistingPrefs() {
        val prefs = context.getSharedPreferences("coneckin_secure", Context.MODE_PRIVATE)
        backupData = prefs.getString(AccountRepository.DATA_KEY, null)
        backupUnreadable = prefs.getString(AccountRepository.UNREADABLE_KEY, null)
        prefs.edit().clear().commit()
    }

    @After
    fun restoreExistingPrefs() {
        val editor = context.getSharedPreferences("coneckin_secure", Context.MODE_PRIVATE)
            .edit()
            .clear()
        backupData?.let { editor.putString(AccountRepository.DATA_KEY, it) }
        backupUnreadable?.let { editor.putString(AccountRepository.UNREADABLE_KEY, it) }
        editor.commit()
    }

    @Test
    fun saveAndLoad_fullCrudRoundTripIsEncryptedOnDevice() {
        val store = AccountStore(context)
        assertEquals(AppData(), store.load())

        // Create
        val created = AppData().withAccount(
            Account("inst-1", "ULBI Uji", "mhs.uji", "rahasia#2026"),
            "ypbpi.hotspot"
        )
        store.save(created)

        // Pastikan tersimpan sebagai ciphertext di SharedPreferences, bukan teks biasa
        val rawStored = context.getSharedPreferences("coneckin_secure", Context.MODE_PRIVATE)
            .getString(AccountRepository.DATA_KEY, null)
        assertNotNull(rawStored)
        assertFalse(rawStored!!.contains("mhs.uji"))
        assertFalse(rawStored.contains("rahasia#2026"))

        // Read
        assertEquals(created, store.load())

        // Update
        val updated = created.withUpdatedAccount(
            id = "inst-1",
            name = "ULBI Diubah",
            username = "mhs.baru",
            password = "sandiBaru!99",
            hosts = listOf("ypbpi.hotspot", "portal.gedungb")
        )!!
        store.save(updated)
        assertEquals(updated, store.load())

        // Delete (cascade)
        val deleted = updated.withoutAccount("inst-1")
        store.save(deleted)
        assertEquals(AppData(), store.load())
    }

    @Test
    fun init_clearsLegacyPanlinkSharedPreferences() {
        val legacy = context.getSharedPreferences("panlink", Context.MODE_PRIVATE)
        legacy.edit().putString("legacy_key", "sisa_lama").commit()
        assertFalse(legacy.all.isEmpty())

        AccountStore(context)

        assertTrue(legacy.all.isEmpty())
    }
}
