package net.simioni.rd2mac.media

/**
 * Pure parsing of the `?skip=` query on `GET /screenshot/latest` and
 * `GET /media/latest` — how far back from the newest item the Mac wants (skip=0 is
 * the newest, skip=1 the second-newest, …). Backs multi-item pulls: the Mac asks
 * for skip=0,1,2,… to fetch the N most recent. A missing / garbage / negative value
 * is 0, so an old Mac that never sends `skip` keeps getting the newest item.
 */
object MediaQuery {
    fun skip(raw: String?): Int = raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}
