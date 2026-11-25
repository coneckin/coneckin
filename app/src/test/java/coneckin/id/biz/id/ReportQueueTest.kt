package coneckin.id.biz.id

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryReportStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

class ReportQueueTest {
    private val storage = InMemoryReportStore()
    private val queue = ReportQueue(storage, maxPending = 3)

    @Test
    fun load_emptyAndCorruptJsonReturnEmptyList() {
        assertTrue(queue.load().isEmpty())
        storage.map[ReportQueue.KEY] = "rusak"
        assertTrue(queue.load().isEmpty())
    }

    @Test
    fun enqueue_keepsOnlyLastMaxPendingItems() {
        queue.enqueue("a.hotspot", "TIMEOUT")
        queue.enqueue("b.hotspot", "NO_SUBMIT")
        queue.enqueue("c.hotspot", "OTHER")
        queue.enqueue("d.hotspot", "HOST_MISMATCH")

        assertEquals(
            listOf(
                "b.hotspot" to "NO_SUBMIT",
                "c.hotspot" to "OTHER",
                "d.hotspot" to "HOST_MISMATCH"
            ),
            queue.load()
        )
    }

    @Test
    fun clear_emptiesQueue() {
        queue.enqueue("a.hotspot", "TIMEOUT")
        queue.clear()
        assertTrue(queue.load().isEmpty())
    }

    @Test
    fun flushWith_emptyQueueDoesNotInvokeSender() {
        var calls = 0
        queue.flushWith { _, _ -> calls++; 201 }
        assertEquals(0, calls)
    }

    @Test
    fun flushWith_removesSent2xxAndInvalid400Items() {
        queue.enqueue("a.hotspot", "TIMEOUT")
        queue.enqueue("b.hotspot", "OTHER")
        val sent = mutableListOf<Pair<String, String>>()
        queue.flushWith { host, code ->
            sent.add(host to code)
            if (host == "a.hotspot") 201 else 400
        }
        assertEquals(listOf("a.hotspot" to "TIMEOUT", "b.hotspot" to "OTHER"), sent)
        assertTrue(queue.load().isEmpty())
    }

    @Test
    fun flushWith_stopsOnFirstTemporaryErrorAndKeepsRemaining() {
        queue.enqueue("a.hotspot", "TIMEOUT")
        queue.enqueue("b.hotspot", "LOAD_FAILED")
        queue.enqueue("c.hotspot", "NO_SUBMIT")

        val attempted = mutableListOf<String>()
        queue.flushWith { host, _ ->
            attempted.add(host)
            if (host == "a.hotspot") 201 else 503
        }

        assertEquals(listOf("a.hotspot", "b.hotspot"), attempted)
        assertEquals(
            listOf("b.hotspot" to "LOAD_FAILED", "c.hotspot" to "NO_SUBMIT"),
            queue.load()
        )
    }

    @Test
    fun flushWith_handlesNetworkErrorMinusOne() {
        queue.enqueue("a.hotspot", "TIMEOUT")
        queue.flushWith { _, _ -> -1 }
        assertEquals(listOf("a.hotspot" to "TIMEOUT"), queue.load())
    }

    @Test
    fun sanitizeManufacturer_lowercasesFiltersAndTruncates() {
        assertEquals("samsung", sanitizeManufacturer("Samsung"))
        assertEquals("xiaomi_redmi-note 12", sanitizeManufacturer("Xiaomi_Redmi-Note 12!@#"))
        assertEquals(30, sanitizeManufacturer("a".repeat(50)).length)
    }

    @Test
    fun buildReportBody_formatsJsonAndAppliesLimits() {
        val json = buildReportBody(
            host = "ypbpi.hotspot",
            code = "NO_USER_FIELD",
            androidSdk = 34,
            appVersion = "1.0.14-" + "x".repeat(30),
            appCode = 14L,
            manufacturer = "Google/Pixel!"
        )
        val o = JSONObject(json)
        assertEquals("ypbpi.hotspot", o.getString("host"))
        assertEquals("NO_USER_FIELD", o.getString("code"))
        assertEquals(34, o.getInt("androidSdk"))
        assertEquals(20, o.getString("appVersion").length)
        assertEquals(14L, o.getLong("appCode"))
        assertEquals("googlepixel", o.getString("manufacturer"))
    }
}
