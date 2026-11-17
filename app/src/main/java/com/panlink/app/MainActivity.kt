package com.panlink.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.panlink.app.ui.theme.PanLinkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var autoLogin by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoLogin = intent?.getBooleanExtra(EXTRA_AUTO_LOGIN, false) == true
        setContent {
            PanLinkTheme {
                PanLinkApp(
                    autoLogin = autoLogin,
                    onAutoLoginHandled = { autoLogin = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_LOGIN, false)) autoLogin = true
    }

    companion object {
        const val EXTRA_AUTO_LOGIN = "auto_login"
    }
}

enum class Screen { HOME, ADD, SETTINGS, MANUAL }
enum class StatusAction { NONE, PORTAL, WIFI }

@Composable
fun PanLinkApp(autoLogin: Boolean, onAutoLoginHandled: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AccountStore(context) }
    val portalLogin = remember { PortalLogin(context) }
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf(store.load()) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusAction by remember { mutableStateOf(StatusAction.NONE) }
    var failedHost by remember { mutableStateOf<String?>(null) }
    var unknownHost by remember { mutableStateOf<String?>(null) }
    var lockEnabled by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var settingsMessage by remember { mutableStateOf("") }

    fun persist(newData: AppData) {
        store.save(newData)
        data = newData
    }

    fun startLogin(current: AppData) {
        if (busy) return
        busy = true
        status = "Memulai..."
        statusAction = StatusAction.NONE
        failedHost = null
        scope.launch {
            val result = try {
                portalLogin.run(current) { status = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoginResult.Failed("Terjadi kesalahan: ${e.javaClass.simpleName}")
            }
            status = result.toText()
            when (result) {
                is LoginResult.UnknownPortal -> {
                    val safe = normalizeHost(result.host)
                    if (safe != null) unknownHost = safe
                    else status = "Alamat portal yang terdeteksi tidak valid."
                }

                is LoginResult.Failed -> {
                    if (result.portalHost != null) {
                        failedHost = result.portalHost
                        statusAction = StatusAction.PORTAL
                    }
                }

                LoginResult.NoWifi -> statusAction = StatusAction.WIFI
                else -> {}
            }
            busy = false
        }
    }

    fun requestLogin() {
        if (busy) return
        if (data.accounts.isEmpty()) {
            status = "Belum ada akun. Tambahkan akun dulu."
            return
        }
        if (lockEnabled && AppLock.isDeviceSecure(context)) {
            AppLock.authenticate(
                context = context,
                title = "Verifikasi untuk login WiFi",
                onSuccess = { startLogin(data) },
                onFailure = { status = it }
            )
        } else {
            startLogin(data)
        }
    }

    fun setLock(enable: Boolean) {
        if (enable) {
            if (!AppLock.isDeviceSecure(context)) {
                settingsMessage = "Atur PIN, pola, atau sidik jari di pengaturan HP dulu."
            } else {
                AppLock.setEnabled(context, true)
                lockEnabled = true
                settingsMessage = ""
            }
        } else {
            val disable = {
                AppLock.setEnabled(context, false)
                lockEnabled = false
                settingsMessage = ""
            }
            if (AppLock.isDeviceSecure(context)) {
                AppLock.authenticate(
                    context = context,
                    title = "Verifikasi untuk mematikan fitur ini",
                    onSuccess = { disable() },
                    onFailure = { settingsMessage = "Verifikasi gagal. Pengaturan tidak diubah." }
                )
            } else {
                disable()
            }
        }
    }

    LaunchedEffect(autoLogin) {
        if (autoLogin) {
            onAutoLoginHandled()
            requestLogin()
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            data = data,
            busy = busy,
            status = status,
            statusAction = statusAction,
            onStatusAction = {
                when (statusAction) {
                    StatusAction.PORTAL -> failedHost?.let {
                        manualHost = it
                        screen = Screen.MANUAL
                    }

                    StatusAction.WIFI ->
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))

                    StatusAction.NONE -> {}
                }
            },
            onLogin = { requestLogin() },
            onSelect = { id -> persist(data.copy(activeAccountId = id)) },
            onDelete = { id ->
                persist(
                    AppData(
                        accounts = data.accounts.filter { it.id != id },
                        portals = data.portals.filter { it.accountId != id },
                        activeAccountId = if (data.activeAccountId == id) null
                        else data.activeAccountId
                    )
                )
            },
            onAdd = { screen = Screen.ADD },
            onSettings = {
                settingsMessage = ""
                screen = Screen.SETTINGS
            }
        )

        Screen.ADD -> {
            BackHandler { screen = Screen.HOME }
            AddAccountScreen(
                onSave = { account, host ->
                    persist(
                        AppData(
                            accounts = data.accounts + account,
                            portals = data.portals + Portal(host, account.id),
                            activeAccountId = account.id
                        )
                    )
                    screen = Screen.HOME
                },
                onBack = { screen = Screen.HOME }
            )
        }

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.HOME }
            SettingsScreen(
                lockEnabled = lockEnabled,
                message = settingsMessage,
                onToggleLock = { setLock(it) },
                onWifiSettings = {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                },
                onBack = { screen = Screen.HOME }
            )
        }

        Screen.MANUAL -> ManualLoginScreen(
            host = manualHost,
            onClose = { screen = Screen.HOME }
        )
    }

    // Konfirmasi portal baru
    unknownHost?.let { host ->
        AlertDialog(
            onDismissRequest = { unknownHost = null },
            title = { Text("Portal baru terdeteksi") },
            text = {
                Column {
                    Text(
                        "Jaringan ini memakai halaman login \"$host\". " +
                                "Username dan password akan dikirim ke alamat tersebut. " +
                                "Lanjutkan hanya jika Anda mempercayai jaringan ini."
                    )
                    Spacer(Modifier.height(8.dp))
                    data.accounts.forEach { account ->
                        TextButton(onClick = {
                            val newData = data.copy(
                                portals = data.portals + Portal(host, account.id),
                                activeAccountId = account.id
                            )
                            persist(newData)
                            unknownHost = null
                            startLogin(newData)
                        }) { Text("Pakai ${account.name}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { unknownHost = null }) { Text("Batal") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    data: AppData,
    busy: Boolean,
    status: String,
    statusAction: StatusAction,
    onStatusAction: () -> Unit,
    onLogin: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    val active = data.activeAccount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PanLink", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (active == null) {
                item { EmptyState(onAdd) }
            } else {
                item { LoginSection(active, busy, onLogin) }
            }

            if (busy || status.isNotEmpty()) {
                item { StatusPanel(busy, status, statusAction, onStatusAction) }
            }

            if (data.accounts.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Akun",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onAdd, enabled = !busy) { Text("Tambah") }
                    }
                }
                items(data.accounts, key = { it.id }) { account ->
                    AccountItem(
                        account = account,
                        hosts = data.portals.filter { it.accountId == account.id }.map { it.host },
                        selected = account.id == active?.id,
                        enabled = !busy,
                        onSelect = { onSelect(account.id) },
                        onDelete = { pendingDelete = account }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus akun?") },
            text = { Text("Akun \"${account.name}\" dan portalnya akan dihapus dari HP ini.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(account.id)
                    pendingDelete = null
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun LoginSection(active: Account, busy: Boolean, onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onLogin,
            enabled = !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            Text(
                if (busy) "Memproses..." else "Login WiFi",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Masuk sebagai ${active.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusPanel(
    busy: Boolean,
    status: String,
    action: StatusAction,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (!busy) {
                when (action) {
                    StatusAction.PORTAL ->
                        TextButton(onClick = onAction) { Text("Buka halaman portal") }

                    StatusAction.WIFI ->
                        TextButton(onClick = onAction) { Text("Buka pengaturan WiFi") }

                    StatusAction.NONE -> {}
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Belum ada akun", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Simpan username dan password WiFi kampus sekali, lalu login dengan satu ketukan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, shape = RoundedCornerShape(14.dp)) { Text("Tambah akun") }
    }
}

@Composable
private fun AccountItem(
    account: Account,
    hosts: List<String>,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(
        onClick = onSelect,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    account.username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    hosts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, contentDescription = "Hapus akun")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    lockEnabled: Boolean,
    message: String,
    onToggleLock: (Boolean) -> Unit,
    onWifiSettings: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedCard(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Verifikasi sebelum login",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Minta PIN, pola, atau sidik jari sebelum login WiFi dijalankan. " +
                                    "Mematikan fitur ini juga memerlukan verifikasi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = lockEnabled, onCheckedChange = onToggleLock)
                }
            }

            if (message.isNotEmpty()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            OutlinedCard(
                onClick = onWifiSettings,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pengaturan WiFi HP", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Buka pengaturan Android untuk memilih jaringan WiFi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "Username dan password disimpan terenkripsi hanya di HP ini dan tidak dikirim " +
                        "ke server mana pun, selain ke halaman login portal yang terdaftar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onSave: (Account, String) -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("ypbpi.hotspot") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape(14.dp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tambah akun") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama akun") },
                supportingText = { Text("Nama bebas untuk membedakan akun") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword)
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { showPassword = !showPassword }) {
                Text(if (showPassword) "Sembunyikan password" else "Tampilkan password")
            }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Alamat portal") },
                supportingText = { Text("Alamat halaman login WiFi, tanpa http://") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    val cleanHost = normalizeHost(host)
                    when {
                        name.isBlank() || username.isBlank() || password.isEmpty() ->
                            error = "Nama, username, dan password wajib diisi."

                        cleanHost == null ->
                            error = "Alamat portal tidak valid."

                        else -> onSave(
                            Account(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                username = username.trim(),
                                password = password
                            ),
                            cleanHost
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) { Text("Simpan") }

            Spacer(Modifier.height(16.dp))
        }
    }
}