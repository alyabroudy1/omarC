package com.cloudstream.shared.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Reflection-only guards on the Wave 4a invariants. The classes touch Android, so they are loaded
 * with `initialize = false` and never instantiated.
 */
class EngineFlagsTest {

    private val loader: ClassLoader = EngineFlagsTest::class.java.classLoader!!

    private fun load(name: String): Class<*> = Class.forName(name, false, loader)

    @Test
    fun resultDeliveredIsVolatile() {
        val field = load("com.cloudstream.shared.webview.WebViewSession")
            .getDeclaredField("resultDelivered")
        assertTrue(
            "resultDelivered must be @Volatile: it is written on the session scope and read from " +
                "WebView callbacks and the cancelling caller",
            Modifier.isVolatile(field.modifiers)
        )
    }

    @Test
    fun enginesExtendWebViewSession() {
        listOf(
            "com.cloudstream.shared.webview.CfBypassEngine",
            "com.cloudstream.shared.webview.VideoSnifferEngine",
            "com.cloudstream.shared.network.ChromiumFetcher"
        ).forEach { name ->
            assertEquals(name, "WebViewSession", load(name).superclass?.simpleName)
        }
    }

    @Test
    fun enginesDoNotRedeclareTheDeliveryFlag() {
        listOf(
            "com.cloudstream.shared.webview.CfBypassEngine",
            "com.cloudstream.shared.webview.VideoSnifferEngine",
            "com.cloudstream.shared.network.ChromiumFetcher"
        ).forEach { name ->
            val shadowed = load(name).declaredFields.any { it.name == "resultDelivered" }
            assertEquals("$name must use the base flag, not its own", false, shadowed)
        }
    }
}
