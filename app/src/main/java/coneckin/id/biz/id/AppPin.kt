package coneckin.id.biz.id

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Hasil verifikasi PIN keamanan. */
sealed class PinCheck {
    data object Success : PinCheck()
    data object WrongPin : PinCheck()
    data class Locked(val remainingSeconds: Int) : PinCheck()
}

/**
 * Pengelola PIN keamanan berbasis KeyValueStore (dapat diuji langsung di JVM).
 * Memakai PBKDF2WithHmacSHA256 dengan salt acak 16-byte per perangkat serta
 * jeda penguncian otomatis setelah 5 percobaan salah berturut-turut.
 */
class PinManager(
    private val storage: KeyValueStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    fun hasPin(): Boolean = storage.getString(KEY_PIN_HASH) != null

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = pbkdf2(pin, salt, ITERATIONS)
        val encoded = buildString {
            append(PREFIX)
            append(':')
            append(ITERATIONS)
            append(':')
            append(Base64.getEncoder().encodeToString(salt))
            append(':')
            append(Base64.getEncoder().encodeToString(derived))
        }
        storage.putString(KEY_PIN_HASH, encoded)
        clearAttempts()
    }

    fun remainingLockSeconds(): Int {
        val lockUntil = storage.getString(KEY_LOCK_UNTIL)?.toLongOrNull() ?: return 0
        val diff = lockUntil - nowMs()
        if (diff <= 0L) return 0
        return ((diff + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    fun verifyPin(pin: String): PinCheck {
        val lockedFor = remainingLockSeconds()
        if (lockedFor > 0) return PinCheck.Locked(lockedFor)

        val stored = storage.getString(KEY_PIN_HASH) ?: return PinCheck.WrongPin
        val matched = if (stored.startsWith("$PREFIX:")) {
            verifyPbkdf2(pin, stored)
        } else {
            // Kompatibilitas dengan hash SHA-256 lama tanpa salt: verifikasi lalu upgrade.
            val legacyMatch = MessageDigest.isEqual(
                legacySha256Hex(pin).toByteArray(Charsets.UTF_8),
                stored.toByteArray(Charsets.UTF_8)
            )
            if (legacyMatch) setPin(pin)
            legacyMatch
        }

        if (matched) {
            clearAttempts()
            return PinCheck.Success
        }

        val fails = (storage.getString(KEY_FAIL_COUNT)?.toIntOrNull() ?: 0) + 1
        return if (fails >= MAX_ATTEMPTS) {
            storage.putString(KEY_FAIL_COUNT, "0")
            storage.putString(KEY_LOCK_UNTIL, (nowMs() + LOCKOUT_MS).toString())
            PinCheck.Locked((LOCKOUT_MS / 1000L).toInt())
        } else {
            storage.putString(KEY_FAIL_COUNT, fails.toString())
            PinCheck.WrongPin
        }
    }

    private fun clearAttempts() {
        storage.putString(KEY_FAIL_COUNT, "0")
        storage.putString(KEY_LOCK_UNTIL, "0")
    }

    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = pbkdf2(pin, salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacySha256Hex(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_FAIL_COUNT = "pin_fail_count"
        const val KEY_LOCK_UNTIL = "pin_lock_until"
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
        private const val PREFIX = "pbkdf2"
        private const val ITERATIONS = 100_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}

/** Pembungkus Android untuk [PinManager] dengan SharedPreferences lokal. */
object AppPin {
    private const val PREFS = "coneckin_settings"

    private fun manager(context: Context): PinManager =
        PinManager(PrefsStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    fun hasPin(context: Context): Boolean = manager(context).hasPin()

    fun setPin(context: Context, pin: String) = manager(context).setPin(pin)

    fun verifyPin(context: Context, pin: String): PinCheck = manager(context).verifyPin(pin)

    fun remainingLockSeconds(context: Context): Int = manager(context).remainingLockSeconds()
}
