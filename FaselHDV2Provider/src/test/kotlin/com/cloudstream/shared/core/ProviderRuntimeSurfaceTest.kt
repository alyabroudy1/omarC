package com.cloudstream.shared.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProviderRuntime` is the whole HTTP surface a provider may see, and it replaced a 992-line god
 * object. The point of the split is lost the moment members start creeping back, so the size of
 * the interface is itself an assertion.
 *
 * Kotlin emits one synthetic `<name>$default` bridge per member with default arguments. On this
 * build those land on the interface itself rather than in `ProviderRuntime$DefaultImpls`, so they
 * are filtered out: what is being counted is the declared surface — the 7 members, whose `domain`
 * shows up as `getDomain`.
 */
class ProviderRuntimeSurfaceTest {

    @Test
    fun hasAtMostEightMethods() {
        val declared = ProviderRuntime::class.java.declaredMethods
            .filterNot { it.name.endsWith("\$default") }
        assertTrue(
            "ProviderRuntime grew to ${declared.size} methods: " +
                declared.map { it.name }.sorted().joinToString(),
            declared.size <= 8
        )
    }
}
