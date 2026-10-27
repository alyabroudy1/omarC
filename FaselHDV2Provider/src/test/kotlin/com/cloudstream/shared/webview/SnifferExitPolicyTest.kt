package com.cloudstream.shared.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class SnifferExitPolicyTest {

    @Test
    fun `a fresh non-master capture waits for the master`() {
        assertEquals(
            SnifferExitDecision.WAIT_FOR_MASTER,
            SnifferExitPolicy.decide(
                capturedCount = 1,
                requiredCount = 1,
                hasMasterM3u8 = false,
                millisSinceFirstCapture = 500,
                softWaitMs = 2500
            )
        )
    }

    @Test
    fun `the soft wait expires into an exit`() {
        assertEquals(
            SnifferExitDecision.EXIT_NOW,
            SnifferExitPolicy.decide(
                capturedCount = 1,
                requiredCount = 1,
                hasMasterM3u8 = false,
                millisSinceFirstCapture = 2600,
                softWaitMs = 2500
            )
        )
    }

    @Test
    fun `a master exits immediately`() {
        assertEquals(
            SnifferExitDecision.EXIT_NOW,
            SnifferExitPolicy.decide(
                capturedCount = 1,
                requiredCount = 1,
                hasMasterM3u8 = true,
                millisSinceFirstCapture = 100,
                softWaitMs = 2500
            )
        )
    }

    @Test
    fun `the monitor cannot exit before the minimum count`() {
        assertEquals(
            SnifferExitDecision.NOT_READY,
            SnifferExitPolicy.decide(
                capturedCount = 0,
                requiredCount = 1,
                hasMasterM3u8 = false,
                millisSinceFirstCapture = 0,
                softWaitMs = 2500
            )
        )
    }
}
