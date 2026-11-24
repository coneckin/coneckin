package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReporterTest {

    @Test
    fun unknownPortal_isReportedWithNormalizedHost() {
        val r = Reporter.fromResult(LoginResult.UnknownPortal("YPBPI.hotspot"))
        assertEquals("ypbpi.hotspot" to "UNKNOWN_PORTAL", r)
    }

    @Test
    fun unknownPortal_withInvalidHostIsNotReported() {
        assertNull(Reporter.fromResult(LoginResult.UnknownPortal("bad host")))
    }

    @Test
    fun failed_isReportedOnlyWhenPortalHostKnown() {
        val withHost = LoginResult.Failed("gagal", "ypbpi.hotspot", "NO_USER_FIELD")
        assertEquals("ypbpi.hotspot" to "NO_USER_FIELD", Reporter.fromResult(withHost))
        assertNull(Reporter.fromResult(LoginResult.Failed("gagal")))
    }

    @Test
    fun successfulOutcomes_areNeverReported() {
        assertNull(Reporter.fromResult(LoginResult.Success))
        assertNull(Reporter.fromResult(LoginResult.AlreadyOnline))
        assertNull(Reporter.fromResult(LoginResult.NoWifi))
    }

    @Test
    fun reportedPair_neverContainsFreeText() {
        // Pesan kesalahan (bisa berisi teks bebas) tidak boleh ikut terkirim.
        val r = Reporter.fromResult(
            LoginResult.Failed("password salah: rahasia123", "ypbpi.hotspot", "OTHER")
        )
        assertEquals("ypbpi.hotspot" to "OTHER", r)
    }
}
