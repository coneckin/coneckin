package coneckin.id.biz.id

/** Pesan untuk pengguna dari kode hasil pengisian formulir portal. */
fun fillErrorText(code: String): String = when (code) {
    "TIMEOUT", "LOAD_FAILED" ->
        "Halaman login tidak bisa dibuka. Pastikan masih tersambung ke WiFi kampus."
    "HOST_MISMATCH" ->
        "Halaman login dialihkan ke alamat lain, jadi login dibatalkan demi keamanan."
    "NO_USER_FIELD", "NO_SUBMIT" ->
        "Bentuk halaman login tidak dikenali. Portal ini mungkin butuh langkah manual."
    else -> "Gagal mengisi form login ($code)."
}

/** Hanya kode yang sudah ada di daftar server yang boleh dilaporkan. */
fun fillReportCode(code: String): String = when (code) {
    "TIMEOUT", "LOAD_FAILED", "HOST_MISMATCH",
    "NO_USER_FIELD", "NO_SUBMIT" -> code

    else -> "OTHER"
}

sealed class LoginResult {
    object AlreadyOnline : LoginResult()
    object Success : LoginResult()
    object NoWifi : LoginResult()
    data class UnknownPortal(val host: String) : LoginResult()
    data class Failed(
        val message: String,
        val portalHost: String? = null,
        val code: String = "OTHER"
    ) : LoginResult()
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
