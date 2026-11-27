package coneckin.id.biz.id

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coneckin.id.biz.id.ui.theme.ConeckinTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var autoLogin by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoLogin = intent?.getBooleanExtra(EXTRA_AUTO_LOGIN, false) == true
        setContent {
            ConeckinTheme {
                ConeckinApp(
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

enum class Screen { HOME, ADD, EDIT, SETTINGS, MANUAL }
enum class StatusAction { NONE, PORTAL, WIFI }
enum class StatusTone { NEUTRAL, SUCCESS, ERROR }

private sealed interface PendingAuth {
    data class Edit(val accountId: String) : PendingAuth
    data class Delete(val accountId: String) : PendingAuth
    data object Export : PendingAuth
    data object ChangePin : PendingAuth
}

@Composable
fun ConeckinApp(autoLogin: Boolean, onAutoLoginHandled: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AccountStore(context) }
    val cache = remember { RemoteCache(context) }
    val portalLogin = remember { PortalLogin(context) }
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf(store.load()) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var editingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusTone by remember { mutableStateOf(StatusTone.NEUTRAL) }
    var statusAction by remember { mutableStateOf(StatusAction.NONE) }
    var failedHost by remember { mutableStateOf<String?>(null) }
    var unknownHost by remember { mutableStateOf<String?>(null) }
    var lockEnabled by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var reportEnabled by remember { mutableStateOf(ReportSettings.isEnabled(context)) }
    var settingsMessage by remember { mutableStateOf("") }

    var directory by remember { mutableStateOf(cache.portals()) }
    var remote by remember { mutableStateOf(cache.config()) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf("") }

    val appCode = remember { appVersionCode(context) }
    val appName = remember { appVersionName(context) }
    val r = remote
    val updateAvailable = r?.latestCode?.let { it > appCode } == true
    val unsupported = r != null && r.minSupportedCode > appCode
    val updateText = when {
        unsupported -> "Versi aplikasi ini sudah terlalu lama. Perbarui agar tetap berfungsi."
        updateAvailable -> "Tersedia versi baru ${r?.latestVersion.orEmpty()}."
        else -> null
    }
    val openUpdate = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)))
    }

    fun persist(newData: AppData) {
        store.save(newData)
        data = newData
    }

    // ── Export / Import ──────────────────────────────────────────────
    var pendingImport by remember { mutableStateOf<AppData?>(null) }
    var hasPin by remember { mutableStateOf(AppPin.hasPin(context)) }
    var sessionVerified by rememberSaveable { mutableStateOf(false) }
    var pendingAuth by remember { mutableStateOf<PendingAuth?>(null) }
    var showPinSetup by remember { mutableStateOf(false) }
    var pinSetupDismissible by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val json = AppDataJson.toJson(data)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(
                    context,
                    "${data.accounts.size} akun berhasil diekspor",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal menyimpan file cadangan.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw Exception("Tidak bisa membaca file")
                val imported = AppDataJson.parse(json)
                if (imported.accounts.isEmpty()) {
                    Toast.makeText(context, "File tidak mengandung akun", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    pendingImport = imported
                }
            } catch (e: Exception) {
                Toast.makeText(context, "File cadangan tidak valid atau rusak.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    fun executeAction(action: PendingAuth) {
        when (action) {
            is PendingAuth.Edit -> {
                editingAccountId = action.accountId
                screen = Screen.EDIT
            }

            is PendingAuth.Delete -> {
                persist(data.withoutAccount(action.accountId))
                Toast.makeText(context, "Akun dihapus", Toast.LENGTH_SHORT).show()
            }

            PendingAuth.Export -> {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exportLauncher.launch("coneckin_backup_$dateStr.json")
            }

            PendingAuth.ChangePin -> {
                pinSetupDismissible = true
                showPinSetup = true
            }
        }
    }

    fun runProtected(action: PendingAuth) {
        if (hasPin && !sessionVerified) {
            pendingAuth = action
        } else {
            executeAction(action)
        }
    }

    fun startLogin(current: AppData) {
        if (busy) return
        busy = true
        status = "Memulai..."
        statusTone = StatusTone.NEUTRAL
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
            statusTone = when (result) {
                is LoginResult.Success, LoginResult.AlreadyOnline -> StatusTone.SUCCESS
                is LoginResult.UnknownPortal, is LoginResult.Failed, LoginResult.NoWifi ->
                    StatusTone.ERROR
            }
            if (reportEnabled) {
                Reporter.fromResult(result)?.let { (host, code) ->
                    scope.launch {
                        Reporter.enqueue(context, host, code)
                        Reporter.flush(context)
                    }
                }
            }
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

    fun refreshRemote() {
        if (refreshing || busy) return
        refreshing = true
        scope.launch {
            val portals = withContext(Dispatchers.IO) { Api.fetchPortals() }
            val config = withContext(Dispatchers.IO) { Api.fetchConfig() }
            if (portals != null) {
                directory = portals
                cache.savePortals(portals)
            }
            if (config != null) {
                remote = config
                cache.saveConfig(config)
            }
            refreshNote = if (portals == null && config == null)
                "Tidak bisa menghubungi server. Memakai data yang tersimpan."
            else
                "Data berhasil diperbarui."
            if (ReportSettings.isEnabled(context) && (portals != null || config != null)) {
                Reporter.flush(context)
            }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        if (!autoLogin) refreshRemote()
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
            statusTone = statusTone,
            statusAction = statusAction,
            announcement = r?.message.orEmpty(),
            updateText = updateText,
            onUpdate = { openUpdate() },
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
            onEdit = { id -> runProtected(PendingAuth.Edit(id)) },
            onDelete = { id -> runProtected(PendingAuth.Delete(id)) },
            onAdd = { screen = Screen.ADD },
            onImport = {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            },
            onSettings = {
                settingsMessage = ""
                screen = Screen.SETTINGS
            }
        )

        Screen.ADD -> {
            BackHandler { screen = Screen.HOME }
            AddAccountScreen(
                existingData = data,
                directory = directory,
                onSave = { account, host ->
                    persist(data.withAccount(account, host))
                    if (!hasPin) {
                        pinSetupDismissible = false
                        showPinSetup = true
                    } else {
                        screen = Screen.HOME
                    }
                },
                onBack = { screen = Screen.HOME }
            )
        }

        Screen.EDIT -> {
            BackHandler {
                editingAccountId = null
                screen = Screen.HOME
            }
            val target = data.accounts.firstOrNull { it.id == editingAccountId }
            if (target == null) {
                editingAccountId = null
                screen = Screen.HOME
            } else {
                EditAccountScreen(
                    account = target,
                    initialHosts = data.portals.filter { it.accountId == target.id }.map { it.host },
                    existingData = data,
                    onSave = { name, username, password, hosts ->
                        data.withUpdatedAccount(target.id, name, username, password, hosts)?.let {
                            persist(it)
                        }
                        editingAccountId = null
                        screen = Screen.HOME
                    },
                    onBack = {
                        editingAccountId = null
                        screen = Screen.HOME
                    }
                )
            }
        }

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.HOME }
            SettingsScreen(
                lockEnabled = lockEnabled,
                message = settingsMessage,
                appVersion = appName,
                latestVersion = r?.latestVersion,
                updateAvailable = updateAvailable,
                refreshing = refreshing,
                refreshNote = refreshNote,
                reportEnabled = reportEnabled,
                onToggleReport = { enable ->
                    ReportSettings.setEnabled(context, enable)
                    reportEnabled = enable
                    if (!enable) scope.launch { Reporter.clearPending(context) }
                },
                onTestReport = {
                    scope.launch {
                        Reporter.enqueue(context, "uji.hotspot", "OTHER")
                        Reporter.flush(context)
                    }
                },
                onToggleLock = { setLock(it) },
                hasPin = hasPin,
                onSetupPin = {
                    if (hasPin) {
                        runProtected(PendingAuth.ChangePin)
                    } else {
                        pinSetupDismissible = true
                        showPinSetup = true
                    }
                },
                onRefresh = { refreshRemote() },
                onUpdate = { openUpdate() },
                onWifiSettings = {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                },
                onExport = {
                    if (data.accounts.isEmpty()) {
                        Toast.makeText(context, "Belum ada akun untuk diekspor", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        runProtected(PendingAuth.Export)
                    }
                },
                onImport = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
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

    // Konfirmasi impor akun
    pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Impor akun") },
            text = {
                Text(
                    "File berisi ${imported.accounts.size} akun. " +
                            "Akun yang sudah ada (username dan portal sama) akan dilewati."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val (merged, addedCount) = data.mergeImported(imported)
                    persist(merged)
                    pendingImport = null
                    Toast.makeText(
                        context,
                        "$addedCount akun baru ditambahkan",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (addedCount > 0 && !hasPin) {
                        pinSetupDismissible = false
                        showPinSetup = true
                    }
                }) { Text("Impor") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Batal") }
            }
        )
    }

    // Fungsi setelah verifikasi berhasil
    fun handleAuthSuccess() {
        val action = pendingAuth ?: return
        pendingAuth = null
        sessionVerified = true
        executeAction(action)
    }

    // Dialog verifikasi PIN / sidik jari
    pendingAuth?.let { action ->
        val authTitle = when (action) {
            PendingAuth.Export -> "Verifikasi untuk mengekspor akun"
            is PendingAuth.Delete -> "Verifikasi untuk menghapus akun"
            PendingAuth.ChangePin -> "Verifikasi untuk mengubah PIN"
            is PendingAuth.Edit -> "Verifikasi untuk mengubah akun"
        }
        PinVerifyDialog(
            onSuccess = { handleAuthSuccess() },
            onDismiss = { pendingAuth = null },
            onBiometric = if (AppLock.isDeviceSecure(context)) {
                {
                    AppLock.authenticate(
                        context = context,
                        title = authTitle,
                        onSuccess = { handleAuthSuccess() },
                        onFailure = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            } else null
        )
    }

    // Dialog buat / ubah PIN keamanan
    if (showPinSetup) {
        PinSetupDialog(
            canDismiss = pinSetupDismissible,
            onDismiss = { showPinSetup = false },
            onDone = { pin ->
                val isUpdate = hasPin
                AppPin.setPin(context, pin)
                hasPin = true
                sessionVerified = true
                showPinSetup = false
                if (!pinSetupDismissible) screen = Screen.HOME
                Toast.makeText(
                    context,
                    if (isUpdate) "PIN keamanan diperbarui" else "PIN keamanan berhasil dibuat",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun InfoBanner(
    text: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    data: AppData,
    busy: Boolean,
    status: String,
    statusTone: StatusTone,
    statusAction: StatusAction,
    announcement: String,
    updateText: String?,
    onUpdate: () -> Unit,
    onStatusAction: () -> Unit,
    onLogin: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    val active = data.activeAccount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coneckin", fontWeight = FontWeight.SemiBold) },
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
            if (updateText != null) {
                item { InfoBanner(updateText, "Unduh pembaruan", onUpdate) }
            }
            if (announcement.isNotBlank()) {
                item { InfoBanner(announcement) }
            }

            if (active == null) {
                item { EmptyState(onAdd = onAdd, onImport = onImport) }
            } else {
                item { LoginSection(active, busy, onLogin) }
            }

            if (busy || status.isNotEmpty()) {
                item { StatusPanel(busy, status, statusTone, statusAction, onStatusAction) }
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
                            "Akun tersimpan",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onAdd, enabled = !busy) { Text("Tambah") }
                    }
                }
                items(data.accounts, key = { it.id }) { account ->
                    AccountItem(
                        account = account,
                        selected = account.id == active?.id,
                        enabled = !busy,
                        onSelect = { onSelect(account.id) },
                        onEdit = { onEdit(account.id) },
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
    tone: StatusTone,
    action: StatusAction,
    onAction: () -> Unit
) {
    val bgColor = when {
        busy -> MaterialTheme.colorScheme.surfaceVariant
        tone == StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        tone == StatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        busy -> MaterialTheme.colorScheme.onSurfaceVariant
        tone == StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        tone == StatusTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = contentColor)
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
private fun EmptyState(onAdd: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_tile_wifi),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Belum ada akun", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Simpan akun WiFi kampus sekali, lalu login kapan saja dengan satu ketukan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.height(48.dp)
        ) { Text("Tambah akun") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onImport) { Text("Impor dari cadangan") }
    }
}

@Composable
private fun AccountItem(
    account: Account,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
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
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
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
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Default.Edit, contentDescription = "Ubah akun")
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
    reportEnabled: Boolean,
    onToggleReport: (Boolean) -> Unit,
    onTestReport: () -> Unit,
    lockEnabled: Boolean,
    hasPin: Boolean,
    message: String,
    appVersion: String,
    latestVersion: String?,
    updateAvailable: Boolean,
    refreshing: Boolean,
    refreshNote: String,
    onToggleLock: (Boolean) -> Unit,
    onSetupPin: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onWifiSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit
) {
    var showSample by remember { mutableStateOf(false) }
    var testSent by remember { mutableStateOf(false) }
    val isDevBuild = appVersion.endsWith("-dev")

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
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
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
                            "Kunci sebelum login",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Minta sidik jari atau kunci layar HP sebelum masuk WiFi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = lockEnabled, onCheckedChange = onToggleLock)
                }
            }

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
                            "PIN keamanan",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Melindungi ubah, hapus, dan ekspor akun.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onSetupPin) {
                        Text(if (hasPin) "Ubah PIN" else "Buat PIN")
                    }
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
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Bantu perbaiki aplikasi",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Kirim catatan otomatis jika login gagal, tanpa data pribadi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = reportEnabled, onCheckedChange = onToggleReport)
                    }
                    TextButton(onClick = { showSample = true }) {
                        Text("Lihat contoh data")
                    }
                    if (reportEnabled && isDevBuild) {
                        TextButton(onClick = {
                            testSent = true
                            onTestReport()
                        }) { Text("Kirim laporan uji (debug)") }
                        if (testSent) {
                            Text(
                                "Laporan uji terkirim.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedCard(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pembaruan aplikasi", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Versi saat ini: ${appVersion.ifEmpty { "-" }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Versi terbaru: ${latestVersion ?: "Belum dicek"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (refreshNote.isNotEmpty()) {
                        Text(
                            refreshNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        TextButton(onClick = onRefresh, enabled = !refreshing) {
                            Text(if (refreshing) "Memeriksa..." else "Cek pembaruan")
                        }
                        if (updateAvailable) {
                            TextButton(onClick = onUpdate) { Text("Unduh") }
                        }
                    }
                }
            }

            OutlinedCard(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pindah perangkat", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Simpan akun ke file cadangan untuk dipakai di HP lain. Jaga file ini karena berisi password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        TextButton(onClick = onExport) { Text("Ekspor") }
                        TextButton(onClick = onImport) { Text("Impor") }
                    }
                }
            }

            OutlinedCard(
                onClick = onWifiSettings,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pengaturan WiFi", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pilih jaringan WiFi di pengaturan HP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "Username dan password disimpan aman hanya di HP ini, tidak pernah dikirim ke mana pun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSample) {
        AlertDialog(
            onDismissRequest = { showSample = false },
            title = { Text("Contoh data yang dikirim") },
            text = {
                Column {
                    Text(
                        "host: ypbpi.hotspot\n" +
                                "code: NO_USER_FIELD\n" +
                                "androidSdk: 34\n" +
                                "appVersion: 1.0.12\n" +
                                "appCode: 12\n" +
                                "manufacturer: samsung"
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Username, password, dan nama WiFi tidak ikut dikirim. Laporan hanya berisi jenis kendala dan tipe HP, lalu dihapus otomatis setelah 90 hari.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSample = false }) { Text("Tutup") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    existingData: AppData,
    directory: List<DirectoryPortal>,
    onSave: (Account, String) -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape(14.dp)

    // Jika hanya ada satu kampus, langsung terisi otomatis
    LaunchedEffect(directory) {
        if (host.isEmpty() && directory.size == 1) {
            host = directory[0].host
            val defaultName = directory[0].name
            val nameTaken = existingData.accounts.any {
                it.name.trim().equals(defaultName.trim(), ignoreCase = true)
            }
            if (name.isBlank() && !nameTaken) name = defaultName
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
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
                value = host,
                onValueChange = { host = it },
                label = { Text("Alamat login WiFi") },
                supportingText = {
                    Text("Terisi otomatis untuk WiFi kampus, bisa diubah manual.")
                },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama akun") },
                supportingText = { Text("Contoh: WiFi Kampus") },
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
                trailingIcon = {
                    PasswordToggleButton(
                        visible = showPassword,
                        onToggle = { showPassword = !showPassword }
                    )
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    when (val check = checkAccountInput(name, username, password, host)) {
                        InputCheck.MissingFields ->
                            error = "Nama, username, dan password wajib diisi."

                        InputCheck.InvalidHost ->
                            error = "Isi alamat login WiFi yang valid."

                        is InputCheck.Valid -> {
                            val conflict = existingData.findAccountConflict(
                                name = check.name,
                                username = check.username,
                                password = check.password,
                                hosts = listOf(check.host)
                            )
                            if (conflict != null) {
                                error = conflict.toErrorMessage()
                            } else {
                                onSave(
                                    Account(
                                        id = UUID.randomUUID().toString(),
                                        name = check.name,
                                        username = check.username,
                                        password = check.password
                                    ),
                                    check.host
                                )
                            }
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAccountScreen(
    account: Account,
    initialHosts: List<String>,
    existingData: AppData,
    onSave: (name: String, username: String, password: String, hosts: List<String>) -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable(account.id) { mutableStateOf(account.name) }
    var username by rememberSaveable(account.id) { mutableStateOf(account.username) }
    var password by rememberSaveable(account.id) { mutableStateOf(account.password) }
    var hostsText by rememberSaveable(account.id) {
        mutableStateOf(initialHosts.joinToString(", "))
    }
    var showPassword by rememberSaveable(account.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape(14.dp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ubah akun") },
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
                value = hostsText,
                onValueChange = { hostsText = it },
                label = { Text("Alamat login WiFi") },
                supportingText = {
                    Text("Pisahkan dengan koma jika dipakai di lebih dari satu jaringan.")
                },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
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
                trailingIcon = {
                    PasswordToggleButton(
                        visible = showPassword,
                        onToggle = { showPassword = !showPassword }
                    )
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    when (val check = checkEditAccountInput(name, username, password, hostsText)) {
                        EditInputCheck.MissingFields ->
                            error = "Nama, username, dan password wajib diisi."

                        EditInputCheck.InvalidHost ->
                            error = "Isi minimal satu alamat portal yang valid."

                        is EditInputCheck.Valid -> {
                            val conflict = existingData.findAccountConflict(
                                name = check.name,
                                username = check.username,
                                password = check.password,
                                hosts = check.hosts,
                                excludeAccountId = account.id
                            )
                            if (conflict != null) {
                                error = conflict.toErrorMessage()
                            } else {
                                onSave(
                                    check.name,
                                    check.username,
                                    check.password,
                                    check.hosts
                                )
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) { Text("Simpan perubahan") }

            Spacer(Modifier.height(16.dp))
        }
    }
}


@Composable
fun PinSetupDialog(
    canDismiss: Boolean = false,
    onDismiss: () -> Unit = {},
    onDone: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val fieldShape = RoundedCornerShape(14.dp)

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("PIN Keamanan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Gunakan PIN 4–6 digit untuk melindungi perubahan, penghapusan, dan ekspor akun."
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newValue ->
                        val digits = newValue.filter { it.isDigit() }
                        if (digits.length <= 6) { pin = digits; error = "" }
                    },
                    label = { Text("PIN (4–6 digit)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { newValue ->
                        val digits = newValue.filter { it.isDigit() }
                        if (digits.length <= 6) { confirmPin = digits; error = "" }
                    },
                    label = { Text("Konfirmasi PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> error = "PIN minimal 4 digit"
                    pin != confirmPin -> error = "PIN tidak cocok"
                    else -> onDone(pin)
                }
            }) { Text("Simpan") }
        },
        dismissButton = {
            if (canDismiss) {
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        }
    )
}

@Composable
fun PinVerifyDialog(
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onBiometric: (() -> Unit)?
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var lockSeconds by remember { mutableStateOf(AppPin.remainingLockSeconds(context)) }
    val fieldShape = RoundedCornerShape(14.dp)

    LaunchedEffect(lockSeconds) {
        if (lockSeconds > 0) {
            delay(1000)
            lockSeconds = AppPin.remainingLockSeconds(context)
            if (lockSeconds == 0) error = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verifikasi Keamanan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Masukkan PIN untuk melanjutkan.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newValue ->
                        val digits = newValue.filter { it.isDigit() }
                        if (digits.length <= 6) { pin = digits; error = "" }
                    },
                    label = { Text("PIN") },
                    enabled = lockSeconds == 0,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                if (lockSeconds > 0) {
                    Text(
                        "Terlalu banyak percobaan salah. Coba lagi dalam $lockSeconds detik.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (onBiometric != null) {
                    TextButton(onClick = onBiometric) {
                        Text("Gunakan sidik jari")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = lockSeconds == 0,
                onClick = {
                    when (val result = AppPin.verifyPin(context, pin)) {
                        PinCheck.Success -> onSuccess()
                        PinCheck.WrongPin -> {
                            error = "PIN salah"
                            pin = ""
                        }
                        is PinCheck.Locked -> {
                            lockSeconds = result.remainingSeconds
                            pin = ""
                            error = ""
                        }
                    }
                }
            ) { Text("Verifikasi") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}


@Composable
private fun PasswordToggleButton(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            painter = painterResource(
                if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            ),
            contentDescription = if (visible) "Sembunyikan password" else "Tampilkan password"
        )
    }
}

private fun AccountConflict.toErrorMessage(): String = when (this) {
    AccountConflict.DuplicateName -> "Nama akun sudah digunakan, pilih nama lain."
    AccountConflict.DuplicateContent -> "Akun dengan isi yang sama sudah ada."
}
