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

/** Membersihkan merek perangkat agar sesuai pola validasi server (maks. 30 karakter). */
fun sanitizeManufacturer(raw: String): String = raw.lowercase()
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
    .take(30)

/** Menyusun payload JSON laporan masalah anonim sesuai kontrak POST /reports. */
fun buildReportBody(
    host: String,
    code: String,
    androidSdk: Int,
    appVersion: String,
    appCode: Long,
    manufacturer: String
): String = JSONObject()
    .put("host", host)
    .put("code", code)
    .put("androidSdk", androidSdk)
    .put("appVersion", appVersion.take(20))
    .put("appCode", appCode)
    .put("manufacturer", sanitizeManufacturer(manufacturer))
    .toString()

/**
 * Antrean laporan masalah berbasis KeyValueStore tanpa ketergantungan Android,
 * sehingga seluruh logika simpan, batas antrean, dan pengiriman ulang bisa diuji di JVM.
 */
class ReportQueue(
    private val storage: KeyValueStore,
    private val maxPending: Int = 20
) {
    fun load(): MutableList<Pair<String, String>> {
        val text = storage.getString(KEY) ?: return mutableListOf()
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

    fun save(items: List<Pair<String, String>>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("host", it.first).put("code", it.second))
        }
        storage.putString(KEY, arr.toString())
    }

    fun enqueue(host: String, code: String) {
        val items = load()
        items.add(host to code)
        save(items.takeLast(maxPending))
    }

    fun clear() {
        save(emptyList())
    }

    /**
     * Mengirim seluruh item dalam antrean memakai fungsi [sender] yang mengembalikan kode HTTP.
     * 2xx = terkirim, 400 = ditolak permanen dan dibuang, lainnya = berhenti & simpan sisa.
     */
    fun flushWith(sender: (host: String, code: String) -> Int) {
        val items = load()
        if (items.isEmpty()) return
        val remaining = mutableListOf<Pair<String, String>>()
        var stop = false
        for (item in items) {
            if (stop) {
                remaining.add(item)
                continue
            }
            val code = sender(item.first, item.second)
            when {
                code in 200..299 -> {}   // terkirim
                code == 400 -> {}        // ditolak server karena tidak valid, dibuang
                else -> {                // gagal sementara (offline, 429, 503), simpan lagi
                    remaining.add(item)
                    stop = true
                }
            }
        }
        save(remaining)
    }

    companion object {
        const val KEY = "pending"
    }
}

object Reporter {
    private const val PREFS = "coneckin_reports"
    private const val MAX_PENDING = 20
    private val mutex = Mutex()

    private fun queue(context: Context) = ReportQueue(
        PrefsStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        MAX_PENDING
    )

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

    suspend fun enqueue(context: Context, host: String, code: String) {
        mutex.withLock { queue(context).enqueue(host, code) }
    }

    suspend fun clearPending(context: Context) {
        mutex.withLock { queue(context).clear() }
    }

    /** Mengirim antrean. Berhenti pada kegagalan pertama dan mencoba lagi nanti. */
    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            queue(context).flushWith { host, code -> post(context, host, code) }
        }
    }

    private fun post(context: Context, host: String, code: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            val body = buildReportBody(
                host = host,
                code = code,
                androidSdk = Build.VERSION.SDK_INT,
                appVersion = appVersionName(context),
                appCode = appVersionCode(context),
                manufacturer = Build.MANUFACTURER
            )

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
