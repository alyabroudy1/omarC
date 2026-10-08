package com.cloudstream.shared.util

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards the two release-risk diagnostics so neither can be committed enabled.
 *
 * Both constants live in [DebugFlags] rather than in NavigationEngine so a plain JVM test can read
 * them: NavigationEngine touches `android.webkit` and cannot be loaded off-device.
 */
class DebugFlagsTest {

    @Test
    fun dumpsDisabledByDefault() {
        assertFalse("DebugFlags.DUMPS must be false in committed code", DebugFlags.DUMPS)
    }

    @Test
    fun remoteDebuggingDisabledByDefault() {
        assertFalse(
            "DebugFlags.WEBVIEW_REMOTE_DEBUGGING must be false in committed code",
            DebugFlags.WEBVIEW_REMOTE_DEBUGGING
        )
    }
}
