package com.panlink.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

sealed class LoginResult {
    object AlreadyOnline : LoginResult()
    object Success : LoginResult()
    object NoWifi : LoginResult()
    data class UnknownPortal(val host: String) : LoginResult()
    data class Failed(val message: String, val portalHost: String? = null) : LoginResult()
}

fun LoginResult.toText(): String = when (this) {
    LoginResult.AlreadyOnline -> "Sudah tersambung ke internet."
    LoginResult.Success -> "Tersambung. Internet sudah aktif."
    LoginResult.NoWifi ->
        "HP belum tersambung ke WiFi. Sambungkan dulu lewat pengaturan WiFi."
    is LoginResult.UnknownPortal ->
        "Portal \"${this.host}\" belum terdaftar."
    is LoginResult.Failed -> this.message
}

private data class Probe(val code: Int, val location: String?)

class PortalLogin(private val context: Context) {

    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var webView: WebView? = null

    suspend fun run(data: AppData, onStatus: (String) -> Unit): LoginResult {
        try {
            onStatus("Mencari jaringan WiFi...")
            val network = requestWifi() ?: return LoginResult.NoWifi
            cm.bindProcessToNetwork(network)

            onStatus("Memeriksa koneksi internet...")
            val first = withContext(Dispatchers.IO) { probe(network) }
                ?: return LoginResult.Failed(
                    "Tidak bisa menghubungi jaringan. Periksa sinyal WiFi lalu coba lagi."
                )
            Log.d(TAG, "probe awal: kode=${first.code}")
            if (first.code == 204) return LoginResult.AlreadyOnline

            // Tentukan portal dari alamat redirect
            val redirect = first.location?.let {
                runCatching { URL(URL(PROBE_URL), it) }.getOrNull()
            }
            val redirectHost = redirect?.host?.lowercase()
            Log.d(TAG, "host redirect: $redirectHost")

            val active = data.activeAccount()
            val candidates = if (redirectHost != null) {
                data.portals.filter { it.host == redirectHost }
            } else {
                data.portals
            }
            if (candidates.isEmpty()) {
                return if (redirectHost != null) LoginResult.UnknownPortal(redirectHost)
                else LoginResult.Failed("Belum ada portal tersimpan.")
            }
            // Utamakan akun yang dipilih pengguna. Jika portal ini bukan miliknya,
            // pakai akun yang memiliki portal tersebut.
            val portal = candidates.firstOrNull { it.accountId == active?.id }
                ?: candidates.first()
            val account = data.accounts.firstOrNull { it.id == portal.accountId }
                ?: return LoginResult.Failed("Akun untuk portal ini tidak ditemukan.")
            val host = portal.host

            val loginUrl =
                if (redirect != null && redirect.protocol == "http") redirect.toString()
                else "http://$host/login"

            onStatus("Membuka halaman login...")
            val fill = fillAndSubmit(loginUrl, host, account)
            Log.d(TAG, "hasil isi form: $fill")

            if (fill == "NO_PASSWORD_FIELD") {
                // Mungkin sudah login dan halaman dialihkan
                val again = withContext(Dispatchers.IO) { probe(network) }
                if (again?.code == 204) return LoginResult.Success
                return LoginResult.Failed(
                    "Kolom login tidak ditemukan. Portal ini mungkin butuh langkah manual.",
                    host
                )
            }
            if (fill != "CLICKED" && fill != "REQUESTED") {
                return LoginResult.Failed(fillError(fill), host)
            }

            onStatus("Memproses login...")
            repeat(10) {
                delay(2000)
                val p = withContext(Dispatchers.IO) { probe(network) }
                Log.d(TAG, "cek ulang: kode=${p?.code}")
                if (p?.code == 204) return LoginResult.Success
            }
            return LoginResult.Failed(
                "Login belum berhasil. Periksa username dan password, atau buka halaman portal.",
                host
            )
        } finally {
            webView?.let { runCatching { it.stopLoading(); it.destroy() } }
            webView = null
            runCatching { cm.bindProcessToNetwork(null) }
            wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            wifiCallback = null
        }
    }

    private suspend fun requestWifi(): Network? = suspendCancellableCoroutine { cont ->
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) cont.resume(null)
            }
        }
        wifiCallback = callback
        try {
            cm.requestNetwork(request, callback, 5000)
        } catch (e: Exception) {
            Log.d(TAG, "requestNetwork gagal: ${e.javaClass.simpleName}")
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun probe(network: Network): Probe? {
        var conn: HttpURLConnection? = null
        return try {
            conn = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            Probe(conn.responseCode, conn.getHeaderField("Location"))
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fillAndSubmit(url: String, host: String, account: Account): String {
        val wv = WebView(context)
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        val loaded = CompletableDeferred<Boolean>()
        wv.webViewClient = object : WebViewClient() {
            // Blokir perpindahan ke alamat selain portal
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host?.lowercase() != host

            override fun onPageFinished(view: WebView, url: String?) {
                if (!loaded.isCompleted) loaded.complete(true)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !loaded.isCompleted) loaded.complete(false)
            }
        }

        wv.loadUrl(url)
        val ok = withTimeoutOrNull(20_000L) { loaded.await() } ?: return "TIMEOUT"
        if (!ok) return "LOAD_FAILED"

        // Isi kredensial hanya jika halaman benar-benar milik portal
        val current = Uri.parse(wv.url ?: "").host?.lowercase()
        if (current != host) return "HOST_MISMATCH"

        val script = "($FILL_FN)(" +
                JSONObject.quote(account.username) + "," +
                JSONObject.quote(account.password) + ")"
        return wv.eval(script).trim('"')
    }

    private suspend fun WebView.eval(js: String): String =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result ?: "")
            }
        }

    private fun fillError(code: String): String = when (code) {
        "TIMEOUT", "LOAD_FAILED" ->
            "Halaman login tidak bisa dibuka. Pastikan masih tersambung ke WiFi kampus."
        "HOST_MISMATCH" ->
            "Halaman login dialihkan ke alamat lain, jadi login dibatalkan demi keamanan."
        "NO_USER_FIELD", "NO_SUBMIT" ->
            "Bentuk halaman login tidak dikenali. Portal ini mungkin butuh langkah manual."
        else -> "Gagal mengisi form login ($code)."
    }

    companion object {
        private const val TAG = "PanLink"
        private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"

        // Mengisi kolom username dan password, lalu menekan tombol Login.
        // Skrip portal sendiri (mis. hash CHAP) yang menangani pengiriman.
        private const val FILL_FN = """function(u,p){
  var pw=document.querySelector('input[type=password]');
  if(!pw) return 'NO_PASSWORD_FIELD';
  var form=pw.form||document;
  var inputs=Array.prototype.slice.call(form.querySelectorAll('input'));
  var user=form.querySelector('input[name=username]')
    ||form.querySelector('input[name=user]')
    ||form.querySelector('input[name=login]')
    ||inputs.filter(function(i){
        var t=(i.type||'text').toLowerCase();
        return t==='text'||t==='email'||t==='tel'||t==='number';
      })[0];
  if(!user) return 'NO_USER_FIELD';
  var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
  function setv(el,v){
    setter.call(el,v);
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  }
  setv(user,u);
  setv(pw,p);
  var btn=form.querySelector('input[type=submit],button[type=submit]')
    ||form.querySelector('button');
  if(btn){btn.click();return 'CLICKED';}
  if(form.requestSubmit){form.requestSubmit();return 'REQUESTED';}
  return 'NO_SUBMIT';
}"""
    }
}