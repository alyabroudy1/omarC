package com.cloudstream.shared.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5: before a Cloudflare re-solve expires the store, an existing clearance earns one direct
 * retry. The presence of a cookie *named* `cf_clearance` is the whole signal.
 */
class CfRetryDecisionTest {

    @Test
    fun retriesWhenClearancePresent() {
        assertTrue(shouldRetryBeforeSolve("cf_clearance=abc; other=1"))
    }

    @Test
    fun noRetryWithoutClearance() {
        assertFalse(shouldRetryBeforeSolve("other=1"))
        assertFalse(shouldRetryBeforeSolve(null))
        assertFalse(shouldRetryBeforeSolve(""))
    }

    @Test
    fun nameMustMatchExactly() {
        assertFalse(shouldRetryBeforeSolve("not_cf_clearance=1"))
    }
}
