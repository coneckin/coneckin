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