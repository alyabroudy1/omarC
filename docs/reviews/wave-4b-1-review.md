# Wave 4b-1 review: `FetchOutcome` + `RequestQueue.Host`

Reviewed: working tree on top of `1ea7e6bc`, files
`shared/.../core/FetchOutcome.kt` (new), `shared/.../queue/RequestQueue.kt`,
`shared/.../service/ProviderHttpService.kt`, `ArabSeedProviderV4/.../ArabseedV4.kt`,
`FaselHDV2Provider/src/test/.../core/FetchOutcomeTest.kt`, `.../queue/RequestQueueTest.kt`.
Wave 4a files ignored. Gradle not run. Line numbers are working-tree unless prefixed `HEAD:`.

## Verdict

Sound refactor, one real regression. The sealed type, `classify`, the `Host` interface and the
queue re-plumbing match the spec and preserve leader/follower semantics. The `HttpError` split
was carried correctly into `getDocument`/`getDocumentNoFallback` but **not** into
`getText`/`postText`/`post`/`postDebug` consumers, which now return `null` for every non-2xx/3xx
response where they used to return the body. Second, the implementer's premise that the four
literals were matched by a live gate is wrong: at HEAD that gate was unreachable, so the new
`needsCfSolve()` gate is a behaviour change (it now fires), not a translation. It is a good
change, but it must be recorded as one.

## Findings

| # | Severity | Where | Issue | Minimal fix |
|---|---|---|---|---|
| F1 | **High (blocker)** | `ProviderHttpService.kt:141` (`getText`), `:278` (`post`), `:284` (`postText`); `ArabseedV4.kt:127`, `:179` (`postDebug` consumers) | HEAD returned `result.html` for any non-CF response (`RequestResult.success` was built for every non-blocked code at `HEAD:726-731`), so a 404/500 body reached the caller. Now `(result as? Success)?.html` yields `null` for `HttpError`. 35 provider files call these accessors; AJAX endpoints that answer 4xx with a usable body silently break. The document paths were fixed (`:419`, `:521` fall back to `HttpError.body`); the text paths were not. Spec section 3 does not list these as decision sites, so they should not change. | Add to `FetchOutcome.kt`: `fun FetchOutcome.bodyOrNull(): String? = when (this) { is Success -> html; is HttpError -> body; else -> null }` and use it at `:141`, `:278`, `:284`, `:419`, `:521`, `ArabseedV4.kt:127`, `:179`. |
| F2 | Medium (document, not a code fix) | `ProviderHttpService.kt:431` vs `HEAD:418-427` | HEAD's fallback gate was dead: `hasCfMarkers` needed `result.html != null`, but `RequestResult.cloudflareBlocked` set `html = null` (`HEAD:RequestQueue.kt:323-329`) and a `success` with code 403 had, by construction, no markers (`isBlocked(403, html)` false at `HEAD:726`). So `isDirectCfBlock` was always false and the four literals were compared by unreachable code. `needsCfSolve()` is live: it fires when a **follower**'s own direct fetch is CF-blocked after the leader succeeded (`RequestQueue.kt:241-256`, `:215-227`). New effect: that follower now launches a queued, breaker-gated WebView solve (`:431-470`) instead of returning `null`. This is what the code comment always claimed, and it is an improvement, but it is not behaviour-neutral. | State it in the commit message and the progress record. No code change. Optional: add the RequestQueue test in gap 1 so the stamping that prevents a loop here is pinned. |
| F3 | Low | `FetchOutcome.kt:29`, `:50` | `CF_CODES` duplicates `CloudflareDetector.CF_RESPONSE_CODES`. `CloudflareDetector.isCloudflareResponse(code)` is public (`CloudflareDetector.kt:60-62`); the spec's stated reason for reusing `isBlocked` was "so the marker list stays in one place". | `val isCfServerBlock = CloudflareDetector.isCloudflareResponse(code) && serverHeader?.contains(...) == true`; delete `CF_CODES`. |
| F4 | Low (accepted divergence) | `ProviderHttpService.kt:491-493` | HEAD threw `CloudflareBlockedSearchException` for a `success` whose body contained `"403 Forbidden"` even at 200. New restricts the body match to `HttpError`. Matches spec section 3 table exactly; noted so it is a known HEAD-to-WT delta. The residual match is on response content, not an error message, so criterion 4 is not violated. | None. |
| F5 | Low | `ProviderHttpService.kt:739`, `:787` | `CloudflareBlocked(403, ...)` fabricates the code; the real blocking code is not available inside `solveCloudflareThenRequest`. No decision reads `code`, so harmless; a one-line comment would stop a future reader trusting it. | Comment, or leave. |
| F6 | Info | `ProviderHttpService.kt:58` | `RequestQueue(this)` still passes `this` before construction completes. Benign: `RequestQueue`'s constructor only stores the reference; every `host.*` call is a `suspend` made after construction. The spec's "cycle" (lambdas closing over a half-built `this` and a public `currentDomain` referenced from inside the initialiser) is gone. | None. |
| F7 | Low | `ProviderHttpService.kt:867` | `handleMetaRefreshRedirect` result: `success && html != null` became `is Success`; a meta-refresh target that answers 404 now returns `null` (caller falls through to the original meta-refresh page) instead of the 404 body. Either result is junk; accepted. | None (or fold into F1's helper if you want strict parity). |
| F8 | Info | `ProviderHttpService.kt:257` | `getRaw` warning now also fires on a CF code with `Server: cloudflare` and no markers. Log only; spec section 3 lists it. | None. |

## Gate equivalence table

| Old condition (HEAD) | New predicate | Equivalent? | Inputs that differ |
|---|---|---|---|
| `getDocument` fallback `HEAD:418-427`: `responseCode == 403 && html has CF markers && !isQueueLevelFailure` | `result.needsCfSolve()` = `is CloudflareBlocked && !solveAttempted` (`:431`) | **No.** Old was unreachable (F2). | Any `CloudflareBlocked(solveAttempted = false)` reaching `getDocument`: only a follower whose own fetch was blocked (403/503/429 + markers, or CF code + `Server: cloudflare`). A CF 503 leader: old gate ignored (`== 403`) but also dead; new: the queue solves it at `RequestQueue.kt:101`, never reaching this gate unstamped. A 403 arriving as `Transport`: impossible (Transport has no code); both false. |
| `RequestQueue` leader `HEAD:76-162`: `success` / `isCloudflareBlocked` / else | `is Success` / `is CloudflareBlocked && !solveAttempted` / else (`:86`, `:101`, `:160`) | Yes, for reachable inputs | `leader.action()` is `executeDirectRequest` (never stamps) or `solveCloudflareThenRequest` (old: `failure(...)` -> else; new: `CloudflareBlocked(attempted)` -> else). No reachable input diverges. |
| Leader domain redirect: `finalUrl?.let{extractDomain} != null && != leaderDomain && isNotBlank` | `extractDomain(result.finalUrl) != leaderDomain && isNotBlank` (`:89-90`) | Yes | `Success.finalUrl` is non-null; `extractDomain` returns `""` on failure, caught by `isNotBlank`. |
| `cfResult.success` then `if (cfResult.html != null) ... else retry` (`HEAD:126-150`) | `cfResult is Success`, unconditional use (`:134-146`) | Yes; else-branch was unreachable | `success` was only built from `WebViewResult.Success.html: String` (`WebViewTypes.kt:47`) or a `RequestResult.success(html: String)` retry. `html` could not be null. |
| Verify gate `verifyResult.success` / re-solve `cfResult.success` (`HEAD:185`, `:208`) | `is Success` (`:188`, `:199`) | Yes | none |
| Re-solve failure: `failure("CF re-solve failed")` to all followers | `cfResult.withSolveAttempted()` to all followers (`:207-210`) | Yes in effect | Old string was only read by the dead gate. New: `CloudflareBlocked` gets stamped; `Cancelled`/`Transport` pass through unstamped, and `needsCfSolve()` is false for those anyway. |
| `failAllFollowers(reason: String)` -> `failure(reason)` | `failAllFollowers(outcome)` propagates leader's outcome (`:266-278`) | Yes in effect | No follower-side code read `error.message`; repo-wide grep for the five literals finds only a log line in `CimaNowProvider.kt:1087` and the `WebViewResult.Cancelled` reason at `CfBypassEngine.kt:428`, neither a decision. |
| `getDocumentNoFallback` `isCloudflareBlocked \|\| code == 403 \|\| html contains "403 Forbidden"` (`HEAD:489-490`) | `is CloudflareBlocked \|\| (is HttpError && (code == 403 \|\| body contains "403 Forbidden"))` (`:491-493`) | **No** (spec-sanctioned) | `Success` (2xx/3xx) whose body contains `"403 Forbidden"`: old threw, new parses. `Transport`: old code -1, html null -> false; new false. Same. |
| Tier-3 `if (!result.isCloudflareBlocked) recordSuccess; return` (`HEAD:601`) | `if (result !is CloudflareBlocked)` (`:608`) | Yes | `HttpError` = old non-CF `success`; both record breaker success. The nested `if (result.isCloudflareBlocked)` at `HEAD:632` was always true after the early return; its removal is neutral. |
| Pre-solve retry `retry.success && !retry.isCloudflareBlocked` (`HEAD:743`) | `retry is Success` (`:749`) | Yes | `success` and `isCloudflareBlocked` were mutually exclusive by construction. |
| `getText`/`postText`/`post`: `result.html` | `(result as? Success)?.html` (`:141`, `:278`, `:284`) | **No** | Every `HttpError` (any non-CF, non-2xx/3xx code): old returned body, new returns `null`. See F1. |
| Solve-result mapping (`HEAD:765-781`) | `Success` -> `Success(html, 200, finalUrl)`; `Cancelled` -> `Cancelled`; else -> `CloudflareBlocked(403, targetUrl, attempted)`; `!webViewEnabled` -> `CloudflareBlocked(403, url, attempted)` (`:739`, `:777-787`) | Yes in effect | "WebView disabled" old: `failure` -> queue `else` -> `failAllFollowers("CF solve failed")`; getDocument: html null, gate dead -> `null`. New: stamped, `needsCfSolve()` false -> `null`. Same observable result. |

The three stamp sites vs the three live literals: `"CF solve failed"` -> `RequestQueue.kt:155`,
`"CF re-solve failed"` -> `:207`, `"CF Bypass failed"` -> `ProviderHttpService.kt:787`. Plus
`"WebView disabled"` -> `:739` as the spec's fourth row. `"Cookie verification"` had no producer
at HEAD; correctly dropped. `"User cancelled CF bypass"` -> `Cancelled` at `:784`. Both
`catch (e: Exception)` sites -> `Transport` at `:671`, `:718`.

## Behaviour-shift judgement for `HttpError`

Non-CF 404/500 were `RequestResult.success` with a body; now `HttpError(code, finalUrl, body)`.

- **Adoption gated on `is Success`** (`:414`, `:501`): correct. A redirect chain that ends in a
  404 on the new host no longer adopts that host until a 2xx lands there. Behaviour-based, no
  name involved, so R3-neutral, and it is exactly the Wave 6 rule already written down
  (`shared-refactor-waves.md:548`: adopt only when a document request ends 2xx and is not
  `CloudflareBlocked`). Spec section 3 lists `:404`/`:411` as `is Success` gates.
- **Meta-refresh follow gated on `is Success`** (`:424`, `:527`): correct; the only known
  meta-refresh case (LaRoza) is a 200.
- **Page cache gated on `is Success`** (`:476`): correct; caching a 404/500 body and handing it
  back on the `allowCached` re-read (`BaseProvider.kt:434`, `FaselHDV2.kt:239`, both detail
  pages) was a latent bug. Neither caller can want a cached error page.
- **Body still parsed** in `getDocument` (`:419`) and `getDocumentNoFallback` (`:521`): correct
  and matches HEAD.
- **Not carried into the text/post accessors**: F1. This is the one path that previously
  returned html and now returns `null`.

## Spec compliance

- Criterion 4 ("`FetchOutcome` is a sealed type; no error decision matches a message string"):
  **met**. `sealed interface` at `FetchOutcome.kt:11`; grep of the service and queue finds no
  `.message?.contains`. The only remaining `contains` on a string is `"403 Forbidden"` against a
  response body at `:493`, which the spec's section 3 table keeps explicitly.
- `classify` rule order vs section 3: matches (`FetchOutcome.kt:50-56`): CF check first, gated
  on `code !in 200..399`; `isBlocked` reused; `serverHeader` rule restricted to 403/503/429.
- Non-touch: `git diff -- shared/src/main/kotlin/com/cloudstream/shared/domain/` is empty.
  `allowedDomains` block (`RequestQueue.kt:119-129`) is byte-identical to `HEAD:110-121`; the
  only whitespace change is trailing spaces stripped from the blank lines at `HEAD:109` and
  `HEAD:122`, outside the block. `Host.solveCloudflare(url, allowedDomains: Set<String>)`
  preserves the parameter and its `takeLast(2)` computation. `DomainManager` untouched.
- `Host` has exactly 4 members (`RequestQueue.kt:33-38`). `execute` passes
  `rewriteDomain = true` (`ProviderHttpService.kt:62-63`) as the old lambda did;
  `onDomainRedirect` body is the old three lines verbatim (`:68-72`); `currentDomain` reads
  `sessionState.domain` (`:74-75`), same as `getCurrentDomain`.
- `runRemainingFollowers` (`:215-228`) is the two identical `coroutineScope` blocks from
  `HEAD:193-205` and `HEAD:218-230` lifted once; call sites at `:194`, `:204`.
- Scope: nothing from 4b-2 leaked in. `ArabseedV4.kt` edit is compelled by `postDebug`'s
  return type. No new UA or header literal; the only header read added is `response.header("Server")`
  at `:257`, `:728`. `FetchOutcome` has no unused variants: `Cancelled` produced at `:784`,
  `Transport` at `:671`/`:718`, `HttpError.finalUrl` and `Transport.cause` are data, not
  decisions, and are logged. `needsCfSolve()` is a named predicate for the spec's gate, not
  extra machinery.

## Tests

`FetchOutcomeTest`: all six spec rows present and assert the stated behaviour.
`noStringMatchingOnErrorMessages` is near-tautological (data-class inequality on a differing
`code`) but it is the assertion the spec asked for; its value is the two `assertEquals` on the
exact `HttpError` shape. `solveAttemptedSuppressesResolve` tests `needsCfSolve()` rather than
`document()`; acceptable because `:431` is exactly that call.

`RequestQueueTest` determinism: deterministic. `runBlocking` runs a single-threaded event loop;
an uncontended `Mutex.withLock` takes the fast path without suspending; `yield()` re-queues the
test coroutine behind the already-dispatched `async` bodies, so the leader is parked in
`gate.await()` before the follower's `enqueue` runs, and `f1` precedes `f2` in
`pendingRequests` because their `async` bodies run in dispatch order. No real threads, no
`Dispatchers.Default`. The fake already uses `CompletableDeferred` gates (`parked`); replacing
`yield()` with awaiting a `leaderParked: CompletableDeferred<Unit>` signalled from
`FakeHost.execute` would make the staging independent of dispatcher ordering, which is a
robustness nicety, not a flakiness fix. No tautologies.

### Test gaps, ranked

1. **Solve fails -> stamping.** `solveResponse = CloudflareBlocked(403, url)`; assert leader and
   both followers receive `CloudflareBlocked(solveAttempted = true)` and exactly one
   `solveCloudflare` call. Pins `RequestQueue.kt:155-157` and `:282-284`, the mechanism that
   replaces the thundering-herd string check and that F2 now makes load-bearing.
2. **Verify fails -> re-solve.** `executeResponse` returns `CloudflareBlocked` for `/2` once; assert
   two `solveCloudflare` calls (`/1`, then `/2`), verifier gets the re-solve result, rest run.
   Then the re-solve-fails variant asserting all followers get the stamped outcome (`:207-210`).
3. **Leader redirect carries through the interface.** Leader `Success` with `finalUrl` on another
   host; assert one `onDomainRedirect(old, new)` and that followers were executed against
   `currentDomain` via the rewritten URL (`:89-94`, `:241-256`). These are the R3-semantics the
   spec says must not change this wave; nothing currently pins them.
4. `classify(404, body, url, serverHeader = "cloudflare")` is `HttpError` (server-header rule is
   CF-code scoped); `classify(403, "<html>Forbidden</html>", url)` with no header is `HttpError`
   (the Akwam case). The second is half-covered by `noStringMatchingOnErrorMessages`.
5. Solve returns `Cancelled`: followers receive `Cancelled` unstamped and `needsCfSolve()` is
   false for it.
6. `getDocument` HttpError-body-still-parsed and `getDocumentNoFallback` throw conditions: not
   JVM-testable while they live in `ProviderHttpService` (OkHttp client, `app.baseClient`,
   `SystemCookieJar`). Spec section 8 accepts this for `HttpGateway` too. Record as accepted.

## Commit verdict: **not ready**

Blockers:
- F1: restore body return for `HttpError` in `getText`, `postText`, `post`, and the two
  `postDebug` consumers in `ArabseedV4.kt` (one helper, seven call sites).
- F2: record in the commit message and progress record that the `getDocument` CF fallback gate
  was unreachable at HEAD and is now live for CF-blocked followers; state that the four
  literals were dead comparisons, not live behaviour.

Non-blocking: F3 (reuse `isCloudflareResponse`), test gap 1 (recommended alongside F2), gaps
2-3 when convenient.
