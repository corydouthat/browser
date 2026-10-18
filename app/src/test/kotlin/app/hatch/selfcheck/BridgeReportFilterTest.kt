package app.hatch.selfcheck

import app.hatch.selfcheck.BridgeReportFilter.Verdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BridgeReportFilterTest {
    private val base = "http://127.0.0.1:4321/t/abc/"
    private val asked = Any()
    private val other = Any()

    @Test
    fun `only the asked session, under the server base, within the cap`() {
        assertEquals(Verdict.ACCEPT, BridgeReportFilter.accept(asked, asked, base + "index.html?persona=x&env=device", base, 1000))
        assertEquals(Verdict.ACCEPT, BridgeReportFilter.accept(asked, asked, base, base, null), "no size known (null report) is not a size problem")
        assertEquals(Verdict.NOT_REQUESTED, BridgeReportFilter.accept(null, asked, base, base, 1))
        assertEquals(Verdict.WRONG_SESSION, BridgeReportFilter.accept(asked, other, base, base, 1))
        assertEquals(Verdict.WRONG_SESSION, BridgeReportFilter.accept(asked, null, base, base, 1))
        assertEquals(Verdict.WRONG_URL, BridgeReportFilter.accept(asked, asked, "https://evil.example/" + base, base, 1))
        assertEquals(Verdict.WRONG_URL, BridgeReportFilter.accept(asked, asked, "http://127.0.0.1:4321/t/zzz/index.html", base, 1))
        assertEquals(Verdict.WRONG_URL, BridgeReportFilter.accept(asked, asked, null, base, 1))
        assertEquals(Verdict.WRONG_URL, BridgeReportFilter.accept(asked, asked, base, null, 1), "server stopped")
        assertEquals(Verdict.TOO_LARGE, BridgeReportFilter.accept(asked, asked, base, base, LocalHttpServer.MAX_BODY_BYTES + 1))
        assertEquals(Verdict.ACCEPT, BridgeReportFilter.accept(asked, asked, base, base, LocalHttpServer.MAX_BODY_BYTES))
        assertEquals(Verdict.TOO_LARGE, BridgeReportFilter.accept(asked, asked, base, base, 11, maxBytes = 10))
    }
}
