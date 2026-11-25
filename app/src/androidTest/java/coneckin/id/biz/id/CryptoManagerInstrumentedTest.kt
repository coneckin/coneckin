package coneckin.id.biz.id

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Pengujian instrumentasi untuk CryptoManager menggunakan AndroidKeyStore asli
 * (AES-256 GCM) pada perangkat fisik atau emulator Android (API 29+).
 */
@RunWith(AndroidJUnit4::class)
class CryptoManagerInstrumentedTest {

    @Test
    fun encryptAndDecrypt_roundTripsJsonAndSpecialCharacters() {
        val original = """{"user":"mhs.ulbi@kampus","pass":"p@ss\wörd_123!#{}[]
	"}"""
        val encrypted = CryptoManager.encrypt(original)
        val decrypted = CryptoManager.decrypt(encrypted)

        assertEquals(original, decrypted)
    }

    @Test
    fun encryptAndDecrypt_handlesEmptyString() {
        val encrypted = CryptoManager.encrypt("")
        assertTrue(encrypted.isNotEmpty())
        assertEquals("", CryptoManager.decrypt(encrypted))
    }

    @Test
    fun encrypt_usesUniqueRandomIvOnEachCall() {
        val plain = "kredensial-wifi-ulbi"
        val first = CryptoManager.encrypt(plain)
        val second = CryptoManager.encrypt(plain)

        assertNotEquals(first, second)
        assertEquals(plain, CryptoManager.decrypt(first))
        assertEquals(plain, CryptoManager.decrypt(second))
    }

    @Test
    fun encrypt_outputDoesNotExposePlaintext() {
        val secret = "PasswordRahasia2026"
        val encrypted = CryptoManager.encrypt(secret)

        assertFalse(encrypted.contains(secret))
        assertFalse(encrypted.contains("Password"))
        // Minimal 12 byte IV + 16 byte GCM auth tag = 28 byte
        val raw = Base64.decode(encrypted, Base64.NO_WRAP)
        assertTrue(raw.size >= 28 + secret.length)
    }

    @Test
    fun decrypt_failsWhenCiphertextOrGcmTagIsTampered() {
        val encrypted = CryptoManager.encrypt("data-sensitif-akun")
        val raw = Base64.decode(encrypted, Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)

        try {
            CryptoManager.decrypt(tampered)
            fail("Dekripsi seharusnya gagal jika ciphertext atau GCM tag dimodifikasi.")
        } catch (_: GeneralSecurityException) {
            // Diharapkan: AEADBadTagException (turunan GeneralSecurityException)
        }
    }

    @Test
    fun decrypt_failsOnTruncatedOrInvalidPayload() {
        val tooShort = Base64.encodeToString(ByteArray(5), Base64.NO_WRAP)
        try {
            CryptoManager.decrypt(tooShort)
            fail("Dekripsi seharusnya gagal pada payload lebih pendek dari ukuran IV.")
        } catch (_: Exception) {
            // Diharapkan: IllegalArgumentException / GeneralSecurityException
        }
    }

    @Test
    fun masterKey_isGeneratedAndStoredInAndroidKeyStore() {
        CryptoManager.encrypt("cek-kunci")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        assertTrue(keyStore.containsAlias("coneckin_master_key"))
    }

    @Test
    fun deleteLegacyKey_removesPanlinkAliasWithoutAffectingConeckinKey() {
        val before = CryptoManager.encrypt("tetap-terbaca")

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                "panlink_master_key",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias("panlink_master_key"))

        CryptoManager.deleteLegacyKey()
        // Panggilan kedua harus tetap aman (idempoten)
        CryptoManager.deleteLegacyKey()

        keyStore.load(null)
        assertFalse(keyStore.containsAlias("panlink_master_key"))
        assertTrue(keyStore.containsAlias("coneckin_master_key"))
        assertEquals("tetap-terbaca", CryptoManager.decrypt(before))
    }
}
