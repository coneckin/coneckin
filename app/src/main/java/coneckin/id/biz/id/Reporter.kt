package coneckin.id.biz.id

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ReportSettings {
    private const val PREFS = "coneckin_settings"
    private const val KEY = "send_reports"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}

object Reporter {
    private const val PREFS = "coneckin_reports"
    private const val KEY = "pending"
    private const val MAX_PENDING = 20
    private val mutex = Mutex()

    /**
     * Mengubah hasil login menjadi (host, kode) yang boleh dilaporkan.
     * Mengembalikan null jika hasilnya tidak perlu dilaporkan.
     */
    fun fromResult(result: LoginResult): Pair<String, String>? = when (result) {
        is LoginResult.UnknownPortal ->
            normalizeHost(result.host)?.let { it to "UNKNOWN_PORTAL" }

        is LoginResult.Failed ->
            result.portalHost?.let { it to result.code }

        else -> null
    }

    private fun load(context: Context): MutableList<Pair<String, String>> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(text)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                o.getString("host") to o.getString("code")
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(context: Context, items: List<Pair<String, String>>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("host", it.first).put("code", it.second))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    suspend fun enqueue(context: Context, host: String, code: String) {
        mutex.withLock {
            val items = load(context)
            items.add(host to code)
            save(context, items.takeLast(MAX_PENDING))
        }
    }

    suspend fun clearPending(context: Context) {
        mutex.withLock { save(context, emptyList()) }
    }

    /** Mengirim antrean. Berhenti pada kegagalan pertama dan mencoba lagi nanti. */
    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = load(context)
            if (items.isEmpty()) return@withLock
            val remaining = mutableListOf<Pair<String, String>>()
            var stop = false
            for (item in items) {
                if (stop) {
                    remaining.add(item)
                    continue
                }
                val code = post(context, item.first, item.second)
                when {
                    code in 200..299 -> {}   // terkirim
                    code == 400 -> {}        // ditolak server karena tidak valid, dibuang
                    else -> {                // gagal sementara (offline, 429, 503), simpan lagi
                        remaining.add(item)
                        stop = true
                    }
                }
            }
            save(context, remaining)
        }
    }

    private fun post(context: Context, host: String, code: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            val maker = Build.MANUFACTURER.lowercase()
                .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .take(30)
            val body = JSONObject()
                .put("host", host)
                .put("code", code)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("appVersion", appVersionName(context).take(20))
                .put("appCode", appVersionCode(context))
                .put("manufacturer", maker)
                .toString()

            conn = URL("$API_BASE/reports").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) {
            -1
        } finally {
            conn?.disconnect()
        }
    }
}