package coneckin.id.biz.id

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    fun load(): AppData {
        val encrypted = prefs.getString("data", null) ?: return AppData()
        return try {
            parse(CryptoManager.decrypt(encrypted))
        } catch (e: Exception) {
            AppData()
        }
    }

    fun save(data: AppData) {
        prefs.edit().putString("data", CryptoManager.encrypt(toJson(data))).apply()
    }

    private fun toJson(data: AppData): String {
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

    private fun parse(json: String): AppData {
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