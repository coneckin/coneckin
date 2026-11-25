package coneckin.id.biz.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginMappingTest {
    private val serverCodes = setOf(
        "NO_WIFI", "UNKNOWN_PORTAL", "LOAD_FAILED", "TIMEOUT", "HOST_MISMATCH",
        "NO_USER_FIELD", "NO_SUBMIT", "NO_PASSWORD_FIELD", "LOGIN_TIMEOUT", "OTHER"
    )

    @Test
    fun fillReportCode_passesThroughKnownCodes() {
        for (c in listOf("TIMEOUT", "LOAD_FAILED", "HOST_MISMATCH", "NO_USER_FIELD", "NO_SUBMIT")) {
            assertEquals(c, fillReportCode(c))
        }
    }

    @Test
    fun fillReportCode_unknownBecomesOther() {
        assertEquals("OTHER", fillReportCode("SESUATU"))
        assertEquals("OTHER", fillReportCode(""))
        assertEquals("OTHER", fillReportCode("CLICKED"))
    }

    @Test
    fun fillReportCode_alwaysReturnsCodeAcceptedByServer() {
        for (c in listOf("TIMEOUT", "NO_SUBMIT", "?", "", "HOST_MISMATCH", "x")) {
            assertTrue(fillReportCode(c) in serverCodes)
        }
    }

    @Test
    fun fillErrorText_mapsEachGroup() {
        assertTrue(fillErrorText("TIMEOUT").startsWith("Halaman login tidak bisa dibuka"))
        assertEquals(fillErrorText("TIMEOUT"), fillErrorText("LOAD_FAILED"))
        assertTrue(fillErrorText("HOST_MISMATCH").contains("dibatalkan"))
        assertTrue(fillErrorText("NO_USER_FIELD").startsWith("Bentuk halaman login"))
        assertEquals(fillErrorText("NO_USER_FIELD"), fillErrorText("NO_SUBMIT"))
    }

    @Test
    fun fillErrorText_unknownCodeIsIncludedInMessage() {
        assertEquals("Gagal mengisi form login (ABC).", fillErrorText("ABC"))
    }

    @Test
    fun loginResultToText_coversAllResults() {
        assertEquals("Sudah tersambung ke internet.", LoginResult.AlreadyOnline.toText())
        assertEquals("Tersambung. Internet sudah aktif.", LoginResult.Success.toText())
        assertTrue(LoginResult.NoWifi.toText().contains("belum tersambung ke WiFi"))
        assertEquals("Portal \"a.com\" belum terdaftar.", LoginResult.UnknownPortal("a.com").toText())
        assertEquals("gagal", LoginResult.Failed("gagal").toText())
    }
}
