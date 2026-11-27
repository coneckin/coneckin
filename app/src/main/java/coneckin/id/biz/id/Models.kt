package coneckin.id.biz.id

data class Account(
    val id: String,
    val name: String,
    val username: String,
    val password: String
)

data class Portal(
    val host: String,
    val accountId: String
)

data class AppData(
    val accounts: List<Account> = emptyList(),
    val portals: List<Portal> = emptyList(),
    val activeAccountId: String? = null
) {
    /** Akun yang sedang dipilih. Jika belum ada pilihan, pakai akun pertama. */
    fun activeAccount(): Account? =
        accounts.firstOrNull { it.id == activeAccountId } ?: accounts.firstOrNull()
}

/**
 * Membersihkan alamat portal yang diketik pengguna.
 * "http://YPBPI.hotspot/login" -> "ypbpi.hotspot"
 * Mengembalikan null jika bentuknya tidak valid.
 */
fun normalizeHost(input: String): String? {
    var h = input.trim().lowercase()
    h = h.removePrefix("http://").removePrefix("https://")
    h = h.substringBefore("/").substringBefore("?")
    val valid = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")
    return if (valid.matches(h)) h else null
}

/** Hasil pemeriksaan isian layar Tambah/Ubah akun. */
sealed class InputCheck {
    data class Valid(
        val name: String,
        val username: String,
        val password: String,
        val host: String
    ) : InputCheck()

    object MissingFields : InputCheck()
    object InvalidHost : InputCheck()
}

fun checkAccountInput(
    name: String,
    username: String,
    password: String,
    hostInput: String
): InputCheck {
    if (name.isBlank() || username.isBlank() || password.isEmpty()) {
        return InputCheck.MissingFields
    }
    val host = normalizeHost(hostInput) ?: return InputCheck.InvalidHost
    return InputCheck.Valid(name.trim(), username.trim(), password, host)
}

/** Create: menambah akun beserta satu portalnya, lalu menjadikannya akun aktif. */
fun AppData.withAccount(account: Account, host: String): AppData = AppData(
    accounts = accounts + account,
    portals = portals + Portal(host, account.id),
    activeAccountId = account.id
)

/** Delete: menghapus akun beserta semua portalnya (cascade). */
fun AppData.withoutAccount(id: String): AppData = AppData(
    accounts = accounts.filter { it.id != id },
    portals = portals.filter { it.accountId != id },
    activeAccountId = if (activeAccountId == id) null else activeAccountId
)

/**
 * Update: mengganti nama, username, password, dan seluruh daftar portal sebuah akun
 * tanpa menghapusnya. ID dan penanda akun aktif tidak berubah.
 * Mengembalikan null bila akun tidak ada atau tidak satu pun host yang valid.
 */
fun AppData.withUpdatedAccount(
    id: String,
    name: String,
    username: String,
    password: String,
    hosts: List<String>
): AppData? {
    if (accounts.none { it.id == id }) return null
    val cleanHosts = hosts.mapNotNull { normalizeHost(it) }.distinct()
    if (cleanHosts.isEmpty()) return null
    return copy(
        accounts = accounts.map {
            if (it.id == id) it.copy(name = name, username = username, password = password) else it
        },
        portals = portals.filter { it.accountId != id } + cleanHosts.map { Portal(it, id) }
    )
}

/**
 * Memecah daftar alamat portal yang dipisahkan koma atau baris baru.
 * Mengembalikan null bila kosong atau ada entri yang tidak valid.
 */
fun parseHostList(input: String): List<String>? {
    val parts = input.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val normalized = parts.map { normalizeHost(it) ?: return null }.distinct()
    return normalized
}

/** Hasil pemeriksaan isian layar Ubah akun (mendukung satu atau beberapa portal). */
sealed class EditInputCheck {
    data class Valid(
        val name: String,
        val username: String,
        val password: String,
        val hosts: List<String>
    ) : EditInputCheck()

    object MissingFields : EditInputCheck()
    object InvalidHost : EditInputCheck()
}

fun checkEditAccountInput(
    name: String,
    username: String,
    password: String,
    hostsInput: String
): EditInputCheck {
    if (name.isBlank() || username.isBlank() || password.isEmpty()) {
        return EditInputCheck.MissingFields
    }
    val hosts = parseHostList(hostsInput) ?: return EditInputCheck.InvalidHost
    return EditInputCheck.Valid(name.trim(), username.trim(), password, hosts)
}

/** Hasil pemeriksaan duplikasi akun berdasarkan nama maupun isi kredensial. */
sealed class AccountConflict {
    data object DuplicateName : AccountConflict()
    data object DuplicateContent : AccountConflict()
}

/**
 * Memeriksa apakah akun baru atau hasil ubahan bentrok dengan akun yang sudah ada.
 * Bentrok terjadi bila nama akun sama (abaikan huruf besar/kecil) atau bila isi
 * kredensial dan daftar portalnya sama persis dengan akun lain.
 */
fun AppData.findAccountConflict(
    name: String,
    username: String,
    password: String,
    hosts: List<String>,
    excludeAccountId: String? = null
): AccountConflict? {
    val cleanName = name.trim().lowercase()
    val cleanUsername = username.trim()
    val cleanHosts = hosts.mapNotNull { normalizeHost(it) }.toSet()

    for (acc in accounts) {
        if (acc.id == excludeAccountId) continue
        if (acc.name.trim().lowercase() == cleanName) {
            return AccountConflict.DuplicateName
        }
        val accHosts = portals.filter { it.accountId == acc.id }
            .mapNotNull { normalizeHost(it.host) }
            .toSet()
        if (acc.username.trim() == cleanUsername &&
            acc.password == password &&
            accHosts == cleanHosts
        ) {
            return AccountConflict.DuplicateContent
        }
    }
    return null
}

/**
 * Menggabungkan data yang diimpor ke data yang sudah ada.
 * Akun yang diimpor mendapat ID baru supaya tidak bentrok.
 * Akun yang namanya duplikat, isinya sama, atau username-nya sudah ada di portal
 * yang sama akan dilewati.
 */
fun AppData.mergeImported(imported: AppData): Pair<AppData, Int> {
    var current = this
    var addedCount = 0

    for (acc in imported.accounts) {
        val accPortals = imported.portals.filter { it.accountId == acc.id }
        val hostList = accPortals.map { it.host }

        if (current.findAccountConflict(acc.name, acc.username, acc.password, hostList) != null) {
            continue
        }

        val existingPairs = current.accounts.flatMap { existing ->
            current.portals.filter { it.accountId == existing.id }
                .map { existing.username to it.host }
        }.toSet()
        val allExist = accPortals.isNotEmpty() &&
                accPortals.all { p -> (acc.username to p.host) in existingPairs }
        if (allExist) continue

        val newId = java.util.UUID.randomUUID().toString()
        val newAcc = acc.copy(id = newId)
        val newPortals = accPortals.map { it.copy(accountId = newId) }
        current = current.copy(
            accounts = current.accounts + newAcc,
            portals = current.portals + newPortals
        )
        addedCount++
    }

    return current to addedCount
}
