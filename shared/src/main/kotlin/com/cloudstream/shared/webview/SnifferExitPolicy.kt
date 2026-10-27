package com.cloudstream.shared.webview

/** The single decision point for ending a [VideoSnifferEngine] session. */
enum class SnifferExitDecision {
    /** Not enough candidates have been captured yet. */
    NOT_READY,

    /** A candidate exists, but the master playlist may still be arriving. */
    WAIT_FOR_MASTER,

    /** Deliver the current captures now. */
    EXIT_NOW
}

/**
 * Keeps the "wait briefly for a master playlist" rule in one place.
 *
 * The monitor job used to call `checkExitCondition()` and then immediately force-complete if that
 * call only scheduled the soft wait. That turned the intended 2.5 second grace period into 0.5
 * seconds and returned a single variant before the master request had a chance to arrive.
 */
object SnifferExitPolicy {

    fun decide(
        capturedCount: Int,
        requiredCount: Int,
        hasMasterM3u8: Boolean,
        millisSinceFirstCapture: Long,
        softWaitMs: Long
    ): SnifferExitDecision {
        if (capturedCount < requiredCount) return SnifferExitDecision.NOT_READY
        if (hasMasterM3u8) return SnifferExitDecision.EXIT_NOW
        if (millisSinceFirstCapture >= softWaitMs) return SnifferExitDecision.EXIT_NOW
        return SnifferExitDecision.WAIT_FOR_MASTER
    }
}
