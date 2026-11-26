package coneckin.id.biz.id

import android.content.Context
import java.security.MessageDigest

/**
 * Mengelola PIN keamanan aplikasi.
 * PIN disimpan dalam bentuk hash SHA-256 di SharedPreferences.
 * Dipakai untuk melindungi fitur edit akun dan ekspor data.
 */
object AppPin {
    private const val PREFS = "coneckin_settings"
    private const val KEY_PIN_HASH = "pin_hash"

    /** True jika pengguna sudah membuat PIN keamanan. */
    fun hasPin(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN_HASH, null) != null

    /** Menyimpan PIN baru (di-hash sebelum disimpan). */
    fun setPin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PIN_HASH, hash(pin)).apply()
    }

    /** Memeriksa apakah PIN yang dimasukkan cocok. */
    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN_HASH, null) ?: return false
        return stored == hash(pin)
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
