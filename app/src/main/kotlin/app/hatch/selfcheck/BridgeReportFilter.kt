package app.hatch.selfcheck

/**
 * Pure acceptance rule for `{type:"selfcheck-report"}` messages arriving on the per-tab "hatch-tab" ports
 * (SEC-2): only the session the controller asked, only a page served by the running self-check server, and
 * only a report that fits the server's own body cap. Everything else is dropped before touching the disk.
 */
object BridgeReportFilter {
    enum class Verdict { ACCEPT, NOT_REQUESTED, WRONG_SESSION, WRONG_URL, TOO_LARGE }

    fun accept(requested: Any?, sender: Any?, url: String?, baseUrl: String?, reportBytes: Long?, maxBytes: Long = LocalHttpServer.MAX_BODY_BYTES): Verdict {
        if (requested == null) return Verdict.NOT_REQUESTED
        if (sender == null || sender !== requested) return Verdict.WRONG_SESSION
        if (baseUrl.isNullOrEmpty() || url == null || !url.startsWith(baseUrl)) return Verdict.WRONG_URL
        if (reportBytes != null && reportBytes > maxBytes) return Verdict.TOO_LARGE
        return Verdict.ACCEPT
    }
}
