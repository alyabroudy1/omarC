package com.cloudstream.shared.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the JS constants in `WebViewShared.kt`. Top-level constants, so nothing
 * Android is loaded.
 *
 * These cannot execute the JS, so they assert the properties a reviewer would otherwise have to
 * re-check by eye — above all that the `navigator.plugins` fake is not a real Array, which is the
 * bot tell F11 removed from `NavigationEngine.SPOOFING_JS`.
 */
class WebViewSharedTest {

    @Test
    fun pluginSpoofDefinesNavigatorPlugins() {
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("navigator"))
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("'plugins'"))
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("Object.defineProperty"))
    }

    @Test
    fun pluginSpoofIsArrayLikeNotAnArray() {
        // The retired fake. Array.isArray(navigator.plugins) must stay false.
        assertFalse(PLUGIN_ARRAY_SPOOF_JS.contains("[1,2,3,4,5]"))
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("Object.create(null)"))
        // Array-like contract the probing sites read.
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("fakePlugins.length"))
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("fakePlugins.item"))
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("fakePlugins.namedItem"))
    }

    @Test
    fun pluginSpoofOnlyOverridesAnEmptyList() {
        assertTrue(PLUGIN_ARRAY_SPOOF_JS.contains("navigator.plugins.length === 0"))
    }

    @Test
    fun pluginSpoofIsBalancedAndSelfContained() {
        assertEqualCounts(PLUGIN_ARRAY_SPOOF_JS, '{', '}')
        assertEqualCounts(PLUGIN_ARRAY_SPOOF_JS, '(', ')')
        // The constant is itself a Kotlin raw-string literal, where a bare $ starts a template;
        // keeping it $-free means the JS says what it looks like. (Interpolating it into the
        // engines' own raw strings is safe either way — inserted values are not re-templated.)
        assertFalse(PLUGIN_ARRAY_SPOOF_JS.contains("$"))
    }

    private fun assertEqualCounts(js: String, open: Char, close: Char) {
        assertTrue(
            "unbalanced $open$close",
            js.count { it == open } == js.count { it == close }
        )
    }
}
