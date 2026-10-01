package net.simioni.rd2mac.update

/**
 * File transfers in flight, both directions — mirror of the Mac's `TransferActivity`.
 * Installing an update kills the app, so it waits until nothing has moved for
 * [QUIET_PERIOD_MS]: long enough to span the gaps between the files of one batch,
 * which go one request at a time.
 */
class TransferActivity(private val now: () -> Long = System::currentTimeMillis) {
    private var inFlight = 0
    private var lastEnd: Long? = null

    @Synchronized fun begin() {
        inFlight++
    }

    @Synchronized fun end() {
        inFlight = maxOf(0, inFlight - 1)
        lastEnd = now()
    }

    /** Runs [work] as one transfer. */
    inline fun <T> track(work: () -> T): T {
        begin()
        try {
            return work()
        } finally {
            end()
        }
    }

    /** Nothing in flight, and the last transfer ended at least [QUIET_PERIOD_MS] ago. */
    val isQuiet: Boolean
        @Synchronized get() = inFlight == 0 && (lastEnd?.let { now() - it >= QUIET_PERIOD_MS } ?: true)

    companion object {
        const val QUIET_PERIOD_MS = 30_000L
        val shared = TransferActivity()
    }
}
