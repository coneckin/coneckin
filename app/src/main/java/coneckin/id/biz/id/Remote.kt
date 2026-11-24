package coneckin.id.biz.id

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val API_BASE = "https://panlink.alwaysdata.net"

// Tautan unduh ditulis tetap di aplikasi, tidak diambil dari server.
const val APK_URL =
    "https://github.com/coneckin/coneckin/releases/latest/download/coneckin.apk"

data class DirectoryPortal(val name: String, val host: String)

data class RemoteConfig(
    val latestVersion: String?,
    val latestCode: Long?,
    val minSupportedCode: Long,
    val message: String
)

// Cadangan jika HP belum pernah terhubung ke server dan cache masih kosong.
val DEFAULT_DIRECTORY = listOf(DirectoryPortal("ULBI", "ypbpi.hotspot"))

fun parsePortals(text: String): List<DirectoryPortal> {
    val arr = JSONObject(text).getJSONArray("portals")
    val result = mutableListOf<DirectoryPortal>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val host = normalizeHost(o.optString("host", "")) ?: continue
        val name = o.optString("name", "").trim().take(60).ifEmpty { host }
        result.add(DirectoryPortal(name, host))
    }
    return result
}

fun parseConfig(text: String): RemoteConfig {
    val o = JSONObject(text)
    return RemoteConfig(
        latestVersion = if (o.isNull("latestVersion")) null
        else o.optString("latestVersion").take(20),
        latestCode = if (o.isNull("latestCode")) null else o.optLong("latestCode"),
        minSupportedCode = o.optLong("minSupportedCode", 0L),
        message = if (o.isNull("message")) ""
        else o.optString("message").trim().take(500)
    )
}

object Api {
    private fun get(path: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(API_BASE + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.length <= 200_000) text else null
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    fun fetchPortals(): List<DirectoryPortal>? {
        val text = get("/portals") ?: return null
        return try {
            parsePortals(text).ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun fetchConfig(): RemoteConfig? {
        val text = get("/config") ?: return null
        return try {
            parseConfig(text)
        } catch (e: Exception) {
            null
        }
    }
}

class RemoteCache(context: Context) {
    private val prefs = context.getSharedPreferences("coneckin_cache", Context.MODE_PRIVATE)

    fun portals(): List<DirectoryPortal> {
        val text = prefs.getString("portals", null) ?: return DEFAULT_DIRECTORY
        return try {
            parsePortals(text).ifEmpty { DEFAULT_DIRECTORY }
        } catch (e: Exception) {
            DEFAULT_DIRECTORY
        }
    }

    fun savePortals(list: List<DirectoryPortal>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("host", it.host)) }
        prefs.edit().putString("portals", JSONObject().put("portals", arr).toString()).apply()
    }

    fun config(): RemoteConfig? {
        val text = prefs.getString("config", null) ?: return null
        return try {
            parseConfig(text)
        } catch (e: Exception) {
            null
        }
    }

    fun saveConfig(c: RemoteConfig) {
        val o = JSONObject()
            .put("latestVersion", c.latestVersion ?: JSONObject.NULL)
            .put("latestCode", c.latestCode ?: JSONObject.NULL)
            .put("minSupportedCode", c.minSupportedCode)
            .put("message", c.message)
        prefs.edit().putString("config", o.toString()).apply()
    }
}

@Suppress("DEPRECATION")
fun appVersionCode(context: Context): Long = try {
    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
} catch (e: Exception) {
    0L
}

@Suppress("DEPRECATION")
fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}