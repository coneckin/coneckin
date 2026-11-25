package coneckin.id.biz.id

import org.json.JSONArray
import org.json.JSONObject

/** Enkripsi dan dekripsi teks. Diimplementasikan CryptoManager (Android Keystore). */
interface SecretCodec {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/** Penyimpanan kunci-nilai teks. Diimplementasikan SharedPreferences di Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Konversi AppData dari dan ke JSON. */
internal object AppDataJson {
    fun toJson(data: AppData): String {
        val accounts = JSONArray()
        data.accounts.forEach {
            accounts.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("username", it.username)
                    .put("password", it.password)
            )
        }
        val portals = JSONArray()
        data.portals.forEach {
            portals.put(JSONObject().put("host", it.host).put("accountId", it.accountId))
        }
        return JSONObject()
            .put("accounts", accounts)
            .put("portals", portals)
            .put("activeAccountId", data.activeAccountId ?: "")
            .toString()
    }

    fun parse(json: String): AppData {
        val root = JSONObject(json)
        val accArr = root.getJSONArray("accounts")
        val portArr = root.getJSONArray("portals")
        val accounts = List(accArr.length()) { i ->
            val o = accArr.getJSONObject(i)
            Account(
                id = o.getString("id"),
                name = o.getString("name"),
                username = o.getString("username"),
                password = o.getString("password")
            )
        }
        val portals = List(portArr.length()) { i ->
            val o = portArr.getJSONObject(i)
            Portal(host = o.getString("host"), accountId = o.getString("accountId"))
        }
        val active = root.optString("activeAccountId", "").ifEmpty { null }
        return AppData(accounts, portals, active)
    }
}

/**
 * Menyimpan dan memuat AppData terenkripsi. Tidak bergantung pada Android,
 * sehingga bisa diuji langsung di JVM dengan penyimpanan dan enkripsi tiruan.
 */
class AccountRepository(
    private val storage: KeyValueStore,
    private val codec: SecretCodec
) {
    /** True bila data tersimpan ada tetapi gagal dibaca pada load() terakhir. */
    var lastLoadFailed: Boolean = false
        private set

    fun load(): AppData {
        lastLoadFailed = false
        val encrypted = storage.getString(DATA_KEY) ?: return AppData()
        return try {
            AppDataJson.parse(codec.decrypt(encrypted))
        } catch (e: Exception) {
            lastLoadFailed = true
            AppData()
        }
    }

    fun save(data: AppData) {
        if (lastLoadFailed) {
            // Data lama tidak terbaca (mis. kunci Keystore berubah). Simpan salinannya
            // dulu agar tidak hilang diam-diam saat ditimpa data baru.
            storage.getString(DATA_KEY)?.let { storage.putString(UNREADABLE_KEY, it) }
            lastLoadFailed = false
        }
        storage.putString(DATA_KEY, codec.encrypt(AppDataJson.toJson(data)))
    }

    companion object {
        const val DATA_KEY = "data"
        const val UNREADABLE_KEY = "data_unreadable"
    }
}
