package com.cloudstream.shared.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session contract of [WebViewSession] itself: serialisation, per-session flag reset, and the
 * lifetime of the session scope. No WebView, no Activity — the fake subclass below only uses the
 * base's coroutine machinery, which is why these run as plain JVM tests against the android stub
 * jar (`WebViewSession`'s own initialisers touch no android class; `WebViewFactory` is reached only
 * from `createWebView`, which nothing here calls).
 *
 * `Dispatchers.Main` is the one Android dependency in the base ([WebViewSession] builds its session
 * scope on it), so every test installs a [StandardTestDispatcher] as Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewSessionTest {

    /** Exposes the base's protected session API; overrides nothing that touches Android. */
    private class FakeSession : WebViewSession({ null }) {
        var cleanupCount = 0

        suspend fun <T> session(block: suspend () -> T): T = withSession(block)

        fun launchIn(block: suspend CoroutineScope.() -> Unit): Job = launchInSession(block)

        var delivered: Boolean
            get() = resultDelivered
            set(value) { resultDelivered = value }

        override fun cleanup() {
            cleanupCount++
            super.cleanup()
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun concurrentSessionsSerialise() = runTest {
        val engine = FakeSession()
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        launch {
            engine.session {
                order += "start1"
                gate.await()
                order += "end1"
            }
        }
        launch {
            engine.session {
                order += "start2"
                order += "end2"
            }
        }

        runCurrent()
        assertEquals("second session must not start while the first holds the mutex",
            listOf("start1"), order)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start1", "end1", "start2", "end2"), order)
        assertEquals(2, engine.cleanupCount)
    }

    @Test
    fun cleanupCancelsSessionScope() = runTest {
        val engine = FakeSession()
        var job: Job? = null

        engine.session { job = engine.launchIn { awaitCancellation() } }

        advanceUntilIdle()
        assertTrue("session scope must be cancelled once the session ends", job!!.isCancelled)
    }

    @Test
    fun launchInSessionAfterCleanupIsNoOp() = runTest {
        val engine = FakeSession()
        engine.session { }

        var ran = false
        val job = engine.launchIn { ran = true }
        advanceUntilIdle()

        assertFalse("body must not run on a cancelled session scope", ran)
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun resultDeliveredResetsPerSession() = runTest {
        val engine = FakeSession()
        engine.session { engine.delivered = true }
        assertTrue(engine.delivered)

        var seenAtStart = true
        engine.session { seenAtStart = engine.delivered }
        assertFalse("withSession must reset the delivery flag", seenAtStart)
    }

    @Test
    fun cancelledCallerStillRunsCleanup() = runTest {
        val engine = FakeSession()
        val gate = CompletableDeferred<Unit>()

        val caller = launch { engine.session { gate.await() } }
        runCurrent()
        assertEquals(0, engine.cleanupCount)

        caller.cancelAndJoin()
        assertEquals("cleanup must run when the caller is cancelled mid-session",
            1, engine.cleanupCount)

        // Mutex released on the cancellation path, so the next session can still run.
        var reentered = false
        engine.session { reentered = true }
        assertTrue(reentered)
        assertEquals(2, engine.cleanupCount)
    }
}
