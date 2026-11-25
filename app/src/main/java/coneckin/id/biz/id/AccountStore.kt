package coneckin.id.biz.id

import android.content.Context
import android.content.SharedPreferences

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("coneckin_secure", Context.MODE_PRIVATE)

    init {
        // Bersihkan sisa versi lama (sebelum aplikasi diganti nama menjadi Coneckin):
        // data teks biasa, data terenkripsi dengan kunci lama, dan kunci lamanya.
        for (legacy in LEGACY_PREFS) {
            context.getSharedPreferences(legacy, Context.MODE_PRIVATE).edit().clear().apply()
        }
        CryptoManager.deleteLegacyKey()
    }

    private companion object {
        val LEGACY_PREFS = listOf(
            "panlink", "panlink_secure", "panlink_settings", "panlink_reports", "panlink_cache"
        )
    }

    private val repository = AccountRepository(PrefsStore(prefs), CryptoManager)

    fun load(): AppData = repository.load()

    fun save(data: AppData) = repository.save(data)
}

internal class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
