package com.panlink.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.panlink.app.ui.theme.PanLinkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanLinkTheme {
                PanLinkApp()
            }
        }
    }
}

enum class Screen { HOME, ADD }

@Composable
fun PanLinkApp() {
    val context = LocalContext.current
    val store = remember { AccountStore(context) }
    val portalLogin = remember { PortalLogin(context) }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(store.load()) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    when (screen) {
        Screen.HOME -> HomeScreen(
            data = data,
            busy = busy,
            status = status,
            onLogin = {
                if (!busy) {
                    busy = true
                    status = "Memulai..."
                    scope.launch {
                        val result = try {
                            portalLogin.run(data) { status = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LoginResult.Failed("Terjadi kesalahan: ${e.javaClass.simpleName}")
                        }
                        status = result.toText()
                        busy = false
                    }
                }
            },
            onAdd = { screen = Screen.ADD },
            onDelete = { id ->
                val newData = AppData(
                    accounts = data.accounts.filter { it.id != id },
                    portals = data.portals.filter { it.accountId != id }
                )
                store.save(newData)
                data = newData
            }
        )

        Screen.ADD -> {
            BackHandler { screen = Screen.HOME }
            AddAccountScreen(
                existingHosts = data.portals.map { it.host }.toSet(),
                onSave = { account, host ->
                    val newData = AppData(
                        accounts = data.accounts + account,
                        portals = data.portals + Portal(host, account.id)
                    )
                    store.save(newData)
                    data = newData
                    screen = Screen.HOME
                },
                onBack = { screen = Screen.HOME }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    data: AppData,
    busy: Boolean,
    status: String,
    onLogin: () -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PanLink") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onLogin,
                    enabled = data.accounts.isNotEmpty() && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text(
                        if (busy) "MEMPROSES..." else "LOGIN WIFI",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            if (status.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(status, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            item {
                Text(
                    text = if (data.accounts.isEmpty())
                        "Belum ada akun. Ketuk Tambah Akun untuk memulai."
                    else
                        "Akun tersimpan",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            items(data.accounts, key = { it.id }) { account ->
                val hosts = data.portals.filter { it.accountId == account.id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text("Username: ${account.username}")
                        hosts.forEach { Text("Portal: ${it.host}") }
                        TextButton(onClick = { pendingDelete = account }, enabled = !busy) {
                            Text("Hapus")
                        }
                    }
                }
            }

            item {
                Button(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Tambah Akun")
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Buka pengaturan WiFi")
                }
                Spacer(Modifier.height(16.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    existingHosts: Set<String>,
    onSave: (Account, String) -> Unit,
    onBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("ypbpi.hotspot") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tambah Akun") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Batal") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama akun") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
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

                        cleanHost in existingHosts ->
                            error = "Portal ini sudah terdaftar di akun lain."

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
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simpan") }
        }
    }
}