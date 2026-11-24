package coneckin.id.biz.id

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Mengikat proses aplikasi ke jaringan WiFi selama layar manual terbuka. */
class WifiBinder(context: Context) {
    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun bind(onResult: (Boolean) -> Unit) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                main.post { onResult(true) }
            }

            override fun onUnavailable() {
                main.post { onResult(false) }
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb, 5000)
        } catch (e: Exception) {
            onResult(false)
        }
    }

    fun release() {
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ManualLoginScreen(host: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val binder = remember { WifiBinder(context) }
    var ready by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(Unit) {
        binder.bind { ready = it }
        onDispose { binder.release() }
    }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Halaman portal") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Selesai") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (ready) {
                null -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Menghubungkan ke WiFi...", Modifier.padding(16.dp))
                }

                false -> Text(
                    "Tidak bisa menghubungi WiFi. Sambungkan HP ke WiFi lewat pengaturan, lalu coba lagi.",
                    Modifier.padding(16.dp)
                )

                true -> {
                    Text(
                        "Selesaikan login di bawah ini, lalu ketuk Selesai.",
                        Modifier.padding(16.dp)
                    )
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient = object : WebViewClient() {
                                    // Hanya boleh membuka alamat portal
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest
                                    ): Boolean = request.url.host?.lowercase() != host
                                }
                                loadUrl("http://$host/login")
                            }
                        },
                        onRelease = {
                            it.stopLoading()
                            it.destroy()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}