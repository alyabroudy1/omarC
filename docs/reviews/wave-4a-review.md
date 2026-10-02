# Wave 4a review: `WebViewSession` base

Independent ("third eye") review of the uncommitted working tree on top of `02d55477` (Wave 3).
Reviewed against `docs/shared-refactor-waves.md` "## Wave 4" (`:402-468`: the `WebViewSession` row
`:414`, the "order inside the wave" note `:407-408`, the non-touch clause `:426-428`, the fingerprint
invariant paragraph `:430-432`, acceptance criterion 5 `:442`, the "split into at least three commits"
rule `:464-466`) and `docs/shared-architecture-review-third-eye.md` "WebView engines" (`:163`).
Read-only; no gradle run. Every claim cites a file:line that was read or a `git`/`grep` that was run.
`docs/shared-refactor-progress.md`, `docs/wave-4b-design.md` and `log3.txt` were ignored as instructed.

Change set reviewed (`git status --short`, `git diff --stat`): 5 modified files under `shared/`
(`ChromiumFetcher.kt`, `CfBypassEngine.kt`, `NavigationEngine.kt`, `VideoSnifferEngine.kt`,
`WebViewShared.kt`; +136 / -325 excluding `log3.txt`) plus 4 untracked: `.gitattributes`,
`shared/.../webview/WebViewSession.kt`, and two tests under
`FaselHDV2Provider/src/test/kotlin/com/cloudstream/shared/webview/`. `ProviderHttpService.kt`,
`DomainManager`, `RequestQueue` and every extractor are untouched.

## Verdict

**Not ready as-is; ready after one small fix (P1, two one-line edits).** The structure is right and
minimal, the fingerprint invariant is now structural for all three engines, criterion 5 is met, and the
cookie/HTML/teardown merges are faithful. But moving the deliverer coroutines from orphan scopes into a
scope that `cleanup()` cancels introduces one real regression: if the caller is cancelled during the
window between `resultDelivered = true` and `deferred.complete(...)` (a `suspend` cookie read sits in
that window in both dialog engines), the deliverer is cancelled before it tears the WebView down and the
existing `if (!resultDelivered)` guard in the caller's `finally` skips teardown too, so the fullscreen
dialog is left on screen. The fix is to guard that `finally` on `deferred.isCompleted` instead.

## Findings

| # | Sev | Where | Issue | Minimal fix |
|---|---|---|---|---|
| F1 | **P1** | `CfBypassEngine.kt:354-364`, `VideoSnifferEngine.kt:1053-1066`, with deliverers at `CfBypassEngine.kt:284-292` and `VideoSnifferEngine.kt:909-921`, `:310-314`, `:1160-1172` | **Dialog/WebView leak on cancel during delivery (regression).** Deliverers set `resultDelivered = true` (`Cf:284`, `Sniffer:909`) and then *suspend* in `extractCookiesWithOrigin`/`extractCookies` (`Cf:287`, `Sniffer:915`) before `cleanupWebView`/`teardown` and `deferred.complete` (`Cf:291-292`, `Sniffer:920-921`). If the caller is cancelled in that window: the caller's `finally` sees `resultDelivered == true` and skips teardown (`Cf:357`, `Sniffer:1056`); `withSession`'s `finally` then runs `cleanup()` (`WebViewSession.kt:77-79`, `:193-195`) which cancels the scope; the suspended deliverer resumes with `CancellationException`, which its `catch (e: Exception)` swallows (`Cf:293-295`, `Sniffer:923-925`), so neither `cleanupWebView` nor `deferred.complete` ever run. Before this wave the deliverer lived in `CoroutineScope(Dispatchers.Main)` and finished regardless (old `Cf:213`, `Sniffer:888` in the diff). Consequence: stuck fullscreen dialog, undestroyed WebView. | In both `finally` blocks change the guard from `if (!resultDelivered)` to `if (!deferred.isCompleted)` (keep the body, including `resultDelivered = true`). Normal path: deferred complete, guard false, unchanged. Old cancel path: unchanged. New race: deferred incomplete, teardown runs. Two lines. |
| F2 | P2 | `ChromiumFetcher.kt:140`, `:148`, `WebViewSession.kt:73`, `ChromiumFetcher.kt:253-256` | **Straggler on the cached WebView can poison the next fetch.** `delivered` was a per-fetch local; it is now the instance flag that `withSession` resets to `false` on the caller's thread (`WebViewSession.kt:73`) before `withContext(Main)` reassigns the client (`ChromiumFetcher.kt:119`). A late `onPageFinished` from the previous page (JS/meta navigation on the still-live cached WebView, reused inside the 30 s window `:253-256`) that runs on Main in that gap passes `if (resultDelivered) return` (`:140`), launches in the *new* scope, and sets `resultDelivered = true` (`:148`) against the old, already-completed `deferred`; the new fetch's real `onPageFinished` then returns at `:140` and the fetch times out. Narrow, self-recovering (timeout), but new. | In `fetch`'s `finally` (`:221-226`) detach the client: `webViewRef?.webViewClient = WebViewClient()` (a straggler then hits a neutral client). Or `stopLoading()` unconditionally there. One line. |
| F3 | P2 | `VideoSnifferEngine.kt:275-300`, `:304`, `SnifferExtractor.kt:162-170` | **Serialisation can hold the mutex for hours.** `SnifferExtractor` runs sessions with `timeout = SNIFFER_PLAYER_TIMEOUT_MS` (3 h, `:167`). When `firstCaptureJob` finds video playing but nothing sniffable (`:284-286`) it leaves the session running until the 3 h timeout hands over (`:323-353`). With the new mutex any other `runSession` on the same engine instance (one instance per `ProviderHttpService`, `ProviderHttpService.kt:974`; parallel server extraction goes through it, `SnifferExtractor.kt:127,162`, `VKVideoEmbed.kt:70,74`) now waits silently for that long instead of opening an overlapping dialog. Intended by criterion 5 (`waves.md:442`) and better than trampling, but it is a visible behaviour change and there is no log line, so `log3.txt` cannot show a queued caller. | Not a blocker. Add one log line in `withSession` when `sessionMutex.tryLock()` fails before falling back to `withLock` ("waiting for in-flight session"), so the manual verification at `waves.md:458-461` can see it. Bounding the wait belongs to 4b/4c. |
| F4 | P3 | `WebViewSession.kt:92-93` | `createWebView(activity, userAgentOverride)`: no subclass passes the override (`Cf:87`, `Sniffer:411`, `Chromium:262` all call `createWebView(activity)`). Used by zero subclasses; speculative for NavigationEngine's move. | Drop the parameter until a subclass needs it: `createWebView(activity) = WebViewFactory.create(activity)`. |
| F5 | P3 | `ChromiumFetcher.kt:286-291` | Private `extractCookies(url)` (CookieManager only) overloads the inherited `extractCookies(webView, url)`. Behaviour preserved (see table), but two same-named cookie paths in one class is the kind of thing the base was meant to remove. | Rename to `cookieJarFor(url)` or note in a comment why Chromium deliberately skips `document.cookie`. Cosmetic. |
| F6 | P3 | `.gitattributes:1`, `git ls-files --eol` | `* text=auto eol=lf` with 46 tracked CRLF files (10 outside `node_modules`: `Cimalight/.../CloudflareSolver.kt`, 4 × `Replaymatch/`, 3 × `Shahid4u/`, `gradlew.bat`, `test_extractors/token_test.html`; 36 under a tracked `node_modules/`). `git status` is clean today, so "no churn" is true *now*; but the next edit to any of those files will renormalise it and produce a whole-file diff inside an unrelated commit. `gradlew.bat` is correctly pinned CRLF (`:2`). | Acceptable, on condition: run `git add --renormalize .` and commit the 46-file EOL change as its own commit ("normalise EOL") before or right after 4a, so no feature diff absorbs it. |
| F7 | P3 | `WebViewSharedTest.kt:44-45` | Comment says a `$` in the constant "would be a template" at the interpolation sites. Interpolated *values* are not re-templated; a `$` matters only inside the constant's own raw-string definition (`WebViewShared.kt:98`). The assertion is still useful, the stated reason is wrong. | Fix the comment. |
| F8 | P3 | `VideoSnifferEngine.kt:335-350` (context, unchanged) | Player-mode dismiss listener destroys the WebView inline instead of via `cleanupWebView`; a second teardown path outside the base. Pre-existing, not part of the diff. | Carry to 4b: route through `cleanupWebView`. |

## Concurrency analysis (question 1)

**(a) Serialisation and delivery-before-cleanup.** `withSession` is `sessionMutex.withLock { ...; try { block() } finally { cleanup() } }` (`WebViewSession.kt:72-80`); the lock is held across `deferred.await()` inside `block`, so two `runSession`/`fetch` calls on one instance queue (criterion 5 met). All three engines wrap their entire body, including the await, in `withSession` (`Cf:54-55`, `Sniffer:219-220`, `Chromium:100`). `cleanup()` is only called from that `finally` (grep: no other call site), which runs after `block()` returns, i.e. after `deferred.await()` returned or threw. Every deliverer completes the deferred and then does only non-suspending work (`Log.i` at `Sniffer:922`, `:1172`), so on the normal path the deferred is complete before the scope is cancelled. The exception is the *cancelled caller* path, F1 above.

**(b) Callbacks after cleanup.** `launchInSession` is `sessionScope.launch(block)` (`:83-84`). On a cancelled `SupervisorJob` the child is created already cancelled and its body never runs; `launch` does not throw. So late `onPageFinished`/`captureLink`/`showSkipOverlay` launches are silent no-ops until the next `withSession` installs a fresh scope. Non-coroutine callbacks: the redirect dialog's `Handler.postDelayed` (`Sniffer:672-677`) touches only its own `AlertDialog`, safe. `evaluateJavascript` callbacks in `timeoutJob`/`firstCaptureJob` (`Sniffer:282-299`, `:319-357`) call `teardown`/`deferred.complete` on *closure-captured* per-session locals, so a straggler after teardown hits a destroyed view inside `cleanupWebView`'s try/catch (`WebViewSession.kt:170-179`) and a completed deferred (idempotent). Residual hazard, pre-existing: `checkExitCondition` (`Sniffer:1123`) reads the *instance* `deferred`/`activeWebView`/`activeDialog`, so a straggler that lands after the next session has started acts on the new session's state. The mutex narrows this window; it does not close it.

**(c) ChromiumFetcher cache.** The cached WebView is never destroyed by `cleanup()` (base only cancels the scope, `:193-195`); it is destroyed only on cache expiry (`Chromium:260`) or `release()` (`:295-298`). Its pending callbacks go to the last assigned client whose `deferred` is a completed local, so completing it is a no-op; the only leak across sessions is the shared `resultDelivered` flag, F2.

**(d) `resultDelivered` off the session thread.** Reads from `onPageFinished`/`onReceivedError` (`Chromium:140`, `:178`), from `captureLink`'s caller path (`Sniffer:1124`) and from JS callbacks (`Sniffer:283`, `:320`) are visibility-only; every check-then-act pair is backed by `deferred.complete`'s idempotence, exactly as before the wave. `@Volatile` is sufficient and is what the spec asks for (`waves.md:442`).

## Behaviour preservation (question 2)

| Engine | Path | Before (diff `-`) | After | Same? |
|---|---|---|---|---|
| CfBypass | HTML read | `getHtmlFromWebView`: Handler.post, `JSONTokener`, `cont.resume` unguarded | `extractHtml` `WebViewSession.kt:101-117`, adds `if (cont.isActive)` | Yes (guard only prevents a resume-after-cancel; harmless) |
| CfBypass | Cookies | CM first, then `document.cookie`, `merged.putAll(cm); putAll(js)` | `WebViewSession.kt:127-158`: same order, same JS string, same merge order, same fallbacks (`cmMap` on JS parse error, `emptyMap()` on outer error), same log fields | Yes. The dropped `isNullOrBlank` pre-check is absorbed by `parseCookieString(String?)` (`WebViewShared.kt:16-17`) |
| CfBypass | Teardown | dismiss → post(stopLoading, about:blank, clearHistory, removeAllViews, removeView, destroy) | `cleanupWebView` `:165-185`, identical sequence | Yes |
| CfBypass | Scopes | 3 × `CoroutineScope(Main).launch` (timeout, onPageFinished, dwell re-check) | 3 × `launchInSession` (`:70`, `:210`, `:233`) | Same body; now cancelled at session end. Only difference is F1 |
| CfBypass | Dialog cancel | `cleanup(webView, null)` | `cleanupWebView(webView, null)` `:425` | Yes |
| Sniffer | HTML / cookies / teardown sequence | as CfBypass (the two copies were textually identical apart from log tags) | base methods | Yes |
| Sniffer | `cleanup(wv, d)` → `teardown` | cancel domPoll, firstCapture; try{dismiss; post destroy; clear refs} | `:1870-1876`: cancel domPoll, firstCapture; `cleanupWebView`; clear refs (outside the try, but `cleanupWebView` cannot throw) | Yes |
| Sniffer | 13 launches | `CoroutineScope(Main).launch` | `launchInSession` at `:275, :304, :373, :829, :870, :887, :961, :1111, :1145, :1160, :1411, :1457, :1728` (13, grep-verified; no `CoroutineScope(` left) | Same bodies; `return@launch` → `return@launchInSession` (`:277`, `:279`) is a label rename, same targets |
| Sniffer | Player hand-over `PlayingInWebView` (`:323-353`) | orphan `domPollJob`/`videoMonitorJob`/`captureLink` UI updates kept running under the live player | scope cancelled by `cleanup()` right after hand-over; late `captureLink` UI text updates and DOM polls stop | **Changed, benign**: nothing the player relies on runs in the scope (the dismiss listener `:335-350` is a plain listener; `SnifferExtractor.kt:473-475` polls `dialog.isShowing`). Verify on device once (DRM page) |
| Sniffer | Spoof block | inline array-like fake (`-` lines at old `:731-752`) | `$PLUGIN_ARRAY_SPOOF_JS` `:731` | Yes, byte-identical statements (see F11 below) |
| Chromium | Mutex | `fetchMutex.withLock { withContext(Main) {...} }` | `withSession { withContext(Main) {...} }` `:100-101` | Same shape |
| Chromium | `delivered` local → `resultDelivered` | per-fetch local | instance flag, reset per session | Equivalent under the mutex except F2 |
| Chromium | Expired-cache teardown (`:260`) | inline stopLoading/about:blank/destroy on Main | `cleanupWebView(existing)`: posted, plus clearHistory/removeAllViews/removeView | Destroy is now one Handler hop later, plus detach. Harmless |
| Chromium | `release()` (`:295-298`) | posted stopLoading/about:blank/destroy | `cleanupWebView(cachedWebView)` | Yes |
| Chromium | Cookies | CookieManager only | private `extractCookies(url)` kept (`:286`) | Yes (F5 is naming) |
| Chromium | HTML | `extractHtml` without failure log | base, logs on unwrap failure | Yes |
| NavigationEngine | `SPOOFING_JS` `:4227-4234` | `navigator.plugins` → real `Array [1,2,3,4,5]`, always | `$PLUGIN_ARRAY_SPOOF_JS`: array-like, only when the real list is empty | **Intentionally changed** (F11, sanctioned in progress table row "wave-3 F11"). Still valid JS: `private val` raw string, `trimIndent()` only strips indentation, the inserted block is a self-contained `try{}catch(e){}` statement between two statements; constant uses single quotes only and contains no `$` (test `WebViewSharedTest:45`) |

**F11 check (question 4).** `PLUGIN_ARRAY_SPOOF_JS` (`WebViewShared.kt:98-115`) is line-for-line the block deleted from the sniffer (diff `-` lines: `Object.create(null)`, three PDF-viewer entries, `length`, `item`, `namedItem`, `refresh`, `defineProperty`), wrapped in the same `if (!navigator.plugins || navigator.plugins.length === 0)` guard, so the sniffer still installs the fake only when the real list is empty (`:731` is inside the sniffer's raw-string JS, as `$DISABLE_DEVTOOL_BYPASS_JS` at `:749` already was). Interpolation happens at Kotlin compile time in both consumers, so quoting is unaffected.

## Wave 1 invariant (question 3)

`grep -nE "WebView\(|WebViewFactory|Mozilla|Chrome/|Android WebView|userAgentString|X-Requested-With"` over the three engines: the only `WebView(` constructor is the known popup sink `VideoSnifferEngine.kt:995` (`onCreateWindow` transport target that blocks every navigation, `:996-1004`; never loads a page, so no fingerprint is emitted — unchanged, still worth a factory call in 4b for uniformity). All three creation sites go through `createWebView` (`Cf:87`, `Sniffer:411`, `Chromium:262`) → `WebViewFactory.create` (`WebViewSession.kt:92-93`). No direct `WebViewFactory.create` remains in the subclasses; no new UA/brand/header literal (the `userAgentString` hits at `Cf:104`, `Sniffer:436` are log reads). `WebViewShared.kt` gained one JS constant and nothing header-related. The invariant is now structural for these three engines in the sense of `waves.md:430-432`; `NavigationEngine` (`:1161` per the third-eye review) is out of scope until its move.

## Spec compliance

| Item | Status |
|---|---|
| `waves.md:414` row: base owns creation via factory, session scope, mutex, volatile flag, HTML+cookie extraction, cleanup | Met (`WebViewSession.kt:46, :54-55, :62, :92-93, :101-158, :165-195`). "The intercept hook" is **not** in the base; none of the three engines shares one today (Cf has none; Sniffer's and Chromium's `shouldInterceptRequest` are unrelated), so leaving it out is the right minimal call — record it as deliberately deferred rather than forgotten |
| `waves.md:414` subclasses "lose their own `parseCookieString`, `cleanup`, spoof JS, and non-volatile flags" | Met: both `extractCookies`/`getHtmlFromWebView`/`cleanup` copies deleted; sniffer spoof block replaced; `resultDelivered` declared once, volatile, and not shadowed (`EngineFlagsTest:40-50`) |
| Criterion 5 (`:442`) | Met: mutex-serialised sessions (`:72`), `@Volatile` flag (`:54`) |
| Order inside the wave (`:407-408`) and "split into ≥3 commits" (`:464-466`) | Met: this is the `WebViewSession` commit alone |
| Non-touch (`:426-428`) | Met: `DomainManager`, `RequestQueue.allowedDomains`, `ProviderHttpService` not in the diff |
| Owner rules: R1/R2 unchanged, R3 untouched, no extractor deleted, all WebViews via factory | Met (see invariant section; `git diff --stat` lists no extractor or DomainManager file) |
| Base minimal, nothing single-subclass | Nearly: `userAgentOverride` is zero-subclass (F4); `cleanupWebView`'s `dialog` parameter is used by 2 of 3, fine |

## Tests (question 6)

**The 7 new tests.** `EngineFlagsTest.resultDeliveredIsVolatile` (`:18-27`) checks half of criterion 5 directly, meaningful. `enginesDoNotRedeclareTheDeliveryFlag` (`:40-50`) guards the exact regression a later subclass edit would introduce, meaningful. `enginesExtendWebViewSession` (`:29-38`) is structural but cheap and would catch someone "temporarily" un-extending. `WebViewSharedTest` (`:17-46`) is string-inspection only; `pluginSpoofIsArrayLikeNotAnArray` and `pluginSpoofOnlyOverridesAnEmptyList` pin the two properties F11 depends on, `pluginSpoofDefinesNavigatorPlugins` is close to tautological, and the `$` test's comment is wrong (F7). The total of 90 `@Test` methods was confirmed by grep (`FaselHDV2Provider/src/test`); the run itself was **not** verified here (no gradle).

**Gap.** Nothing exercises `withSession` itself: serialisation, scope cancellation on `cleanup()`, `launchInSession` after cleanup, or the F1 race. The obstacle is `Dispatchers.Main` hard-coded at `WebViewSession.kt:62, :74`; the test classpath has `kotlinx-coroutines-android` but not `kotlinx-coroutines-test` (`FaselHDV2Provider/build.gradle.kts:17-21`), so `Dispatchers.Main` would resolve to the Android factory and hit the `android.jar` stub `Looper`.

**Minimal seam (pick one, ranked).**
1. `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")` and `Dispatchers.setMain(StandardTestDispatcher())` in `@Before`. Zero production change; the fake subclass `object : WebViewSession({ null }) { suspend fun <T> run(b: suspend () -> T) = withSession(b); fun launch(b) = launchInSession(b) }` never touches a WebView, so the class loads under the stub jar as `EngineFlagsTest` already proves.
2. If a build dep is unwelcome: a second constructor parameter `mainDispatcher: CoroutineDispatcher = Dispatchers.Main` on `WebViewSession`, used at `:62` and `:74`. One line of production change.

**Tests to add, ranked (new `WebViewSessionTest`).**
1. `concurrentSessionsSerialise`: launch two `run {}` on one instance; session 1 suspends on a gate; assert session 2's body has not started; open the gate; assert order `[start1, end1, start2, end2]`. This is the untested half of criterion 5.
2. `cleanupCancelsSessionScope`: inside a session, `launchInSession { awaitCancellation() }` and keep the `Job`; after `run` returns, `assertTrue(job.isCancelled)`.
3. `launchInSessionAfterCleanupIsNoOp`: after a session, `launchInSession { ran = true }`, `runCurrent()`, assert `!ran`, returned job `isCancelled`, no exception (pins the "silent no-op" contract at `:82`).
4. `resultDeliveredResetsPerSession`: set the flag true inside session 1, assert false at the start of session 2 (pins `:73`).
5. `cancelledCallerStillRunsCleanup`: override `cleanup()` to count (calling `super`), cancel the outer job while the block suspends, assert count == 1 and mutex reacquirable.
6. F1 itself needs a WebView and stays an on-device check: cancel the caller while the CF dialog is up and confirm the dialog closes (see `waves.md:458-461`).

## Carry into 4b / 4c

- F8: route the player-mode dismiss teardown (`VideoSnifferEngine.kt:335-350`) and the popup sink (`:995`) through `cleanupWebView`/`createWebView`.
- The intercept hook was left out of the base on purpose; add it only when `NavigationEngine` moves and a second engine needs the same hook.
- `checkExitCondition`'s instance-field coupling (`Sniffer:1123-1172`) is the remaining straggler hazard; when 4b touches the sniffer, make the deliverer capture its session's `deferred`/`dialog` by closure like the other paths do.
- Bounded wait or contention log for the 3 h sniffer sessions (F3).
- One-off EOL normalisation commit (F6). Also: `node_modules/` is tracked (36 CRLF files under it); out of scope, but someone should decide whether it belongs in the repo.
- `CfBypassEngine.runSession` and `VideoSnifferEngine.runSession` still take an unused `userAgent` parameter "kept for signature stability until Wave 4" (`Cf:37,48`, `Sniffer:198,212`); 4b can now drop it.

## Commit verdict

**Not ready** until F1 is applied (two guard edits: `CfBypassEngine.kt:357` and `VideoSnifferEngine.kt:1056`, `if (!resultDelivered)` → `if (!deferred.isCompleted)`). With F1 in, **ready**; F2 (one line in `ChromiumFetcher.fetch`'s `finally`) is strongly recommended in the same commit since it is the other regression the shared flag introduced, and F6's renormalise commit should land separately. F3-F5, F7 are optional polish; none blocks.
