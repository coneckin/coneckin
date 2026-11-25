package coneckin.id.biz.id

sealed class PortalPick {
    data class Found(val account: Account, val portal: Portal) : PortalPick()
    data class Unknown(val host: String) : PortalPick()
    data class Failed(val message: String) : PortalPick()
}

/**
 * Memilih portal dan akun untuk login. Bila host redirect diketahui, hanya portal
 * dengan host itu yang dipertimbangkan. Akun yang dipilih pengguna diutamakan;
 * bila portal itu bukan miliknya, dipakai akun pemilik portal.
 */
fun pickPortal(data: AppData, redirectHost: String?): PortalPick {
    val active = data.activeAccount()
    val candidates = if (redirectHost != null) {
        data.portals.filter { it.host == redirectHost }
    } else {
        data.portals
    }
    if (candidates.isEmpty()) {
        return if (redirectHost != null) PortalPick.Unknown(redirectHost)
        else PortalPick.Failed("Belum ada portal tersimpan.")
    }
    val portal = candidates.firstOrNull { it.accountId == active?.id } ?: candidates.first()
    val account = data.accounts.firstOrNull { it.id == portal.accountId }
        ?: return PortalPick.Failed("Akun untuk portal ini tidak ditemukan.")
    return PortalPick.Found(account, portal)
}
