# `shared/` refactor: progress report (Waves 0 to 3)

Date: 2026-09-07. Baseline `50df2f90`. Head of the refactor: `02d55477`.
Plan of record: [shared-refactor-waves.md](shared-refactor-waves.md), corrected by
[shared-architecture-review-third-eye.md](shared-architecture-review-third-eye.md).
Per-wave reviews: [reviews/wave-0-review.md](reviews/wave-0-review.md) to
[reviews/wave-3-review.md](reviews/wave-3-review.md).

What has been done, why, and what each decision rejected. It records the delta against the plan, not
the plan.

## 1. Purpose and rules

### The problem

- `shared/` is 25,693 lines over 82 files that every plugin compiles in by `srcDir`, so the
  CloudStream Gradle plugin dexes a private copy into each of the 41 `.cs3` files. A 225-line
  provider shipped a 1.5 MB dex (`shared-architecture-review.md:7-8`, metrics table `:23-33`).
- Two god objects: `ProviderHttpService` at 1,406 lines with 9 collaborators, and `NavigationEngine`
  at 4,410 lines with one consumer and 88 mentions of cimanow or freex
  (`shared-architecture-review.md:15`, `:78`).
- State ownership was undefined: cookies lived in four stores, the domain in two, plus three
  preference files; `updateCookies` wrote four places by hand
  (`shared-architecture-review.md:17`, `:82`).
- Errors were strings. The second Cloudflare path decided by matching four error-message literals,
  and every `BaseProvider` entry point caught `Exception` and returned null, which is
  indistinguishable from "no content" (`shared-architecture-review.md:86`).
- Debug code shipped to users: WebView remote debugging on, the full header set echoed to
  postman-echo.com, HTML dumped to `externalCacheDir`, and a domain-sync Worker that committed any
  domain any device sent it (`shared-architecture-review.md:13`, `:88`).

### Binding rules

| Rule | Statement |
|---|---|
| R1 | One User-Agent. No UA literal in `shared/` or in any provider module making browser-shaped requests. |
| R2 | One HTTP fingerprint across every tier: UA, `sec-ch-ua*`, `Accept-Language`, one `X-Requested-With` policy. |
| R3 | No domain-name heuristics. No denylist, aliases, multi-part-TLD or registrable-suffix decisions. Domain work is the last wave, so behavioural adoption lands on a split gateway, not inside the god object. |
| D1 | Android `CookieManager` is the single cookie jar; OkHttp reaches it through a `CookieJar` adapter (`shared-refactor-waves.md`, Owner decisions). |
| Process | No overengineering, no guessing. Every claim cites a file:line that was read. |
| Registry | An `ExtractorApi` subclass with zero direct callers is not dead: `loadExtractor` resolves by `mainUrl` prefix over `extractorApis`. Grep alone never proves an extractor dead. |
| Review | Every wave is reviewed by an independent reviewer before commit, and the fix pass is re-reviewed. |
| Tests | A wave's required behaviour must be covered by an assertion that would fail if the rule broke. |

## 2. Process

Each wave ran as: planner writes the wave section with acceptance criteria and named unit tests;
parallel implementer agents work on disjoint file sets; an independent reviewer reads the
uncommitted tree against the wave section and the rules; implementers run a fix pass; the reviewer
re-verifies per finding; then one commit. Each review is in `docs/reviews/`.

Test host: `FaselHDV2Provider/src/test/kotlin/com/cloudstream/shared/**`, plain JUnit 4, no Android
classes on the test path. The reason is structural: `shared/` has no `build.gradle.kts`, and
`settings.gradle.kts` includes every directory that has one while the root build applies
`com.android.library` plus the cloudstream plugin to every subproject, so a new `SharedTests` module
would be built and published as a plugin unless disabled, and a disabled directory is not included
at all. `FaselHDV2Provider` already compiles all of `shared/src/main` and already had JUnit, so a
test costs one file. Wire tests use MockWebServer at the app's OkHttp version, added in Wave 1.

Anything Android-dependent gets a pure seam instead: `Fingerprint.fromUserAgent(ua, locale, ...)`,
`FingerprintInterceptor.headersFor(fingerprint, ...)`, `CookieStorage.get/set` behind
`SystemCookieJar`, `MediaUrlValidator.buildExoPlayerHeaders(sourceHeaders, fingerprint, cookies)`.

## 3. Wave 0: test harness, search regression, release-risk flags

Commits `35753f59` and `a5c1d50e`.

**Goal.** A JVM suite that runs on every push, un-break the 22 providers whose custom search was
dead, stop shipping diagnostics. No design change.

**What changed.**

- Deleted the pageless `open searchNormal(query)` / `searchLazy(query)` from `BaseProvider` and
  migrated all 22 overrides to `(query, page)`. Real paging threaded for Animewitcher, Animeiat,
  Cimalight and Cimawbas; the other 18 return empty for `page > 1` before any network call
  (`35753f59`).
- `DebugFlags`: `DUMPS` and `WEBVIEW_REMOTE_DEBUGGING`, both `false`. Every `cacheDir` /
  `externalCacheDir` write in `NavigationEngine` and `BaseProvider.kt:478` is gated
  (`wave-0-review.md`, "All disk writes gated").
- Deleted `HEADER_ECHO_URL` and its caller, plus the `echoDiagnosticDone` field and its reset, with
  no dead code left behind.
- `configs/domain-sync-worker/index.js`: debug `GET` deleted (it returned `tokenLength`,
  `tokenPrefix`, owner and repo), `configFile` restricted to the 22 known names, which also closes
  an unvalidated path interpolation at `:64`, and an optional `X-Sync-Secret` check.
- CI: a `test` job running `:FaselHDV2Provider:testDebugUnitTest` and
  `shared/tools/check-injected-js.js`, with `build` gated on `needs: test`
  (`.github/workflows/build.yml:20-51`). `FaselHDV2Provider/build.gradle.kts` mirrors the cloudstream
  stub jar onto `testRuntimeOnly` so `BaseProvider` loads in JVM tests.

**Why.** `BaseProvider.search(query, page)` called only the paged overloads while 22 providers
overrode only the pageless ones, so their custom search had been unreachable since
`ede3ad15`/`50df2f90` (`shared-architecture-review.md:70`). Release-risk items: `:71-73`, `:88`.

**Decisions and rationale.**

| Decision | Alternative rejected | Why |
|---|---|---|
| Delete the pageless overloads outright | Keep them as deprecated shims delegating to the paged pair | The shim is what caused the regression: the pageless methods stayed `open`, so 22 overrides compiled and were never called. Deleting them turns the bug into 22 compile errors, each a visible decision. |
| `SYNC_SECRET` optional and left unset | Require the header unconditionally | The plugins are open source, so a shipped client secret is not a secret (`index.js:53-55`), and the client does not send the header yet. Setting it today would silently break domain sync for every installed plugin. The 22-name allowlist is the real defence; the secret is a hook for a later trust model. |
| One `DebugFlags` file, two `const val` | A flag per call site, or a runtime setting | Two constants are the whole surface and are JVM-assertable without loading `NavigationEngine`. A runtime setting would be a user-facing feature nobody asked for. |

**Metrics.** Runnable tests 18 to 22 (`SearchPagingTest`, `DebugFlagsTest`). LOC excluding docs and
`log3.txt`: +405 / -214 over both commits.

**Review verdict and fix pass.** Accept with fixes, no blocker (`wave-0-review.md`). `a5c1d50e`
applied them: reject a non-string `configFile` with 400 instead of a `TypeError`-driven 500, drop the
two `includes` checks made redundant by the `Set`, clamp the Algolia page index to `>= 0` in
Animewitcher, reword acceptance criterion 5 to "when `SYNC_SECRET` is set", and document that the
variable must stay unset.

**Regression watch and device checks owed.** Cimalight and Animeiat `&page=` parameter names are
plausible from repo evidence but unproven; the FaselHD AJAX-fallback log line, the absence of a
`setWebContentsDebuggingEnabled` line, and an empty `externalCacheDir` after a failed load are all
still owed on device.

## 4. Wave 1: one `Fingerprint` (R1, R2)

Commit `6462aa91`.

**Goal.** One identity object built from the device, consumed by every browser-shaped request on
every tier.

**What changed.**

- `shared/.../core/Fingerprint.kt`: built once from `WebSettings.getDefaultUserAgent`, `Locale`,
  `Build`; strips `; wv)` and `Version/`; no fallback UA.
- `shared/.../core/FingerprintInterceptor.kt`: one application interceptor with a Document and a
  Subresource header set, `sec-fetch-site` computed from the referer.
- `shared/.../webview/WebViewFactory.kt`: sets `userAgentString` and calls
  `RequestedWithHeaderControl.suppress`, with an optional logged per-session override.
- Routed through it: the three OkHttp paths in `ProviderHttpService`, all four WebView engines, the
  raw WebViews in `FaselHDExtractor`, `DrmPlayerDialog` and `YouTubePlayer`, `MediaUrlValidator` (now
  on `app.baseClient`), image headers and `ExtractorLink` playback headers.
- Deleted: `util/WebConfig.kt`, the four hand-rolled header blocks, `ProviderConfig.userAgent`,
  `BaseProvider.userAgent`, `SessionState.userAgent`/`buildHeaders`, `SessionProvider.getUserAgent`,
  the 18 shared UA literals and the 13 provider-module ones, and
  `NavigationEngine.hideXRequestedWithHeader` with its five reflection fallbacks.

**Why.** The third-eye inventory found 14 construction sites and five independent divergences,
including a Windows desktop Chrome 120 UA sent alongside cookies minted by an Android WebView, a
sniffer UA differing from the session UA, and three disagreeing `sec-ch-ua` brand spellings
(third-eye section 2). The original plan contained neither R1 nor R2 as goals; the third-eye doc
inserted `Fingerprint` as Wave 1 (`:5`, correction 7).

**Decisions and rationale.**

| Decision | Alternative rejected | Why |
|---|---|---|
| No fallback UA; construction throws | Keep a literal default when WebView is unavailable | The old `WebConfig` fallback was a Pixel 7 / Chrome 131 literal handed to anything running before a `Context` existed. A named `IllegalStateException` at plugin init is a bug report; a wrong fingerprint is a silent block. |
| Do not set `Accept-Encoding` | Set `gzip, deflate, br` explicitly like the deleted blocks | OkHttp's `BridgeInterceptor` adds `gzip` and enables transparent gzip only when the request sets neither `Accept-Encoding` nor `Range`. Setting it ourselves returns raw compressed bodies. |
| Caller UA suppresses the hints only when it differs from the fingerprint | Suppress on the presence of the header (the original rule) | Six callers pass `Fingerprint.current().userAgent` explicitly into `getDocument`/`getRaw`. Keying on presence gave them the same UA with fewer hints: two identities on one tier. Keying on value preserves the documented foreign-UA behaviour (review F5). |
| CimaNow's per-session TV-UA override honoured and logged as a deviation | Drop the override, set `SURF_AS_TV_UA = false` | The site's client-side `isTv()` check is what skips the popunder flow and the white empty page; losing it silently was a likely live regression (review F1). A recorded R1 exception, scoped to one session, logged once per WebView only when the override differs. The owner can veto by flipping the flag. |
| API-client UAs exempt (InnerTube, Viu) | Force them onto the device fingerprint | They are not browser-shaped requests, and an API contract can pin a client string. Each site carries `// API client, not a browser request (Wave 1, R1 exemption)` so the exemption is greppable (third-eye open question 2). |
| Brand spelling follows `RequestedWithHeaderControl` | Follow `WebConfig.buildSecChUa` or `NavigationEngine` | Three spellings disagreed. `RequestedWithHeaderControl` is what Chromium serializes onto the wire, so the OkHttp side follows it, not the reverse (third-eye correction 4). |

**Metrics.** Runnable tests 22 to 56. LOC excluding docs: +1,228 / -665.

**Review verdict and fix pass.** First pass: accept with fixes, five must-fix items. Fix pass:
F2 (`X-Requested-With: ""` deleted from `VideoSnifferEngine` and `CfBypassEngine` loadUrl extras),
F3 (Cimalight's fourth hardcoded brand string replaced), F4 (`Version/` token stripped, with a test),
F5 (value-based suppression, with pure and wire tests), and F6 to F9 and F11 pulled forward from
Wave 2 all landed. The re-review returned **not ready** on N1: the F1 override reached the WebViews
but not the three `HttpURLConnection` re-issues in `NavigationEngine`, so a CimaNow session still
presented two UAs. That was closed before the commit: `6462aa91` adds a `sessionUa()` helper at
`NavigationEngine.kt:208` reading `sessionUserAgentOverride ?: Fingerprint.current().userAgent`.

**Regression watch and device checks owed.** Laroza (desktop UA to mobile UA on both the embed fetch
and the link), the CF-solve WebView now presenting a Chrome brand triple from a WebView TLS stack,
the YouTube player's consent wall (N4), and the DRM dialog's Chrome 91 literal. Acceptance criterion
5 (all four tiers show an identical `user-agent` and `sec-ch-ua` in one captured set) is device-only
and still owed, as is `WebViewFactory`'s `headersControlledGlobally=true` line for all four tiers.

## 5. Wave 2: the system cookie jar is the only cookie store (D1)

Commit `aae6481f`.

**What changed.**

- `shared/.../core/SystemCookieJar.kt`: an OkHttp `CookieJar` over `CookieManager` behind a
  two-method `CookieStorage` seam; `saveFromResponse` writes `Cookie.toString()` verbatim against
  the response URL; `expireCookiesFor` sets `Max-Age=0` per name for one host.
- Installed on every page-fetching client: `getRaw`, `executeDirectRequest`, `executePostRequest`.
- Deleted: `SessionStore`, `CookieLifecycleManager`, `SessionState`'s cookie members, `updateCookies`,
  `snapshotSession`/`restoreSession`, `mergeSessionCookies`, `storeCdnCookies`,
  `syncCookiesToSystemCookieManager`, the `Set-Cookie` harvesting with its `trustedDomains` gate, and
  `SessionProvider.getCookiesForDomain`.
- Engines no longer hand WebView cookie maps back for merging; image and playback headers read the
  store once.
- `shouldRetryBeforeSolve` restored as a pure decision.
- `CloudflareKiller` removed from Shahid4u and Topcinema; both route through `httpService`.

**Why.** Four stores with no scoping meant `getCookiesForDomain` decided by name similarity whether
to attach a cookie to a foreign host: a hand-rolled reimplementation of cookie-jar scoping, and an
R3 violation in all but name. With `CookieManager` as the only jar, scope is whatever the
`Set-Cookie` said (third-eye `:5`, section 3).

**Decisions and rationale.**

| Decision | Alternative rejected | Why |
|---|---|---|
| The jar is the truth; `SessionState`/`SessionStore` cookie code deleted | The original P1: `SessionState` holds cookies, `CookieManager` becomes a sync target | That keeps two stores and adds sync code instead of deleting one, and the WebViews already read `CookieManager` directly. Reversed by D1. |
| `MediaUrlValidator` gets **no** jar | Install the jar there too, for consistency | `BridgeInterceptor` replaces a caller's `Cookie` header whenever the jar returns anything, so the probe would send the store's cookies while ExoPlayer, which has no jar, sends the source's. That breaks the validator's contract that validation predicts playback: the probe must send the player's exact `Cookie` (review F1, must-fix). |
| `expireCookiesFor` is per host with `Path=/`, limitation documented | Keep a global wipe, or fix scoping now | `removeAllCookies` destroys every provider's session. A correct expiry of a `Domain=`-scoped `cf_clearance` needs the registrable domain, which R3 puts in Wave 6. The write stays host-only, the KDoc says what it cannot expire (review F3). |
| Retry once before expiring and solving when `cf_clearance` is present | Always expire and solve | The deleted 10-second guard stopped re-solve storms; without it a second CF block seconds after a solve expires a fresh clearance and re-solves. Restored as a pure predicate with no timestamp: a stale clearance fails the retry and the solve proceeds (review F5). |
| `CloudflareKiller` removed from Shahid4u and Topcinema | Leave it and scope it in the app | Its `init` calls `removeAllCookies(null)`, so constructing one wipes every provider session once providers depend on the jar. No other path in this fork constructs one, so removing the two sites is the whole fix; the app-side change is Wave 5. |
| `trustedDomains` field kept, only its call site removed | Delete the field now | Keeps the wave cookie-only. The field is a Wave 6 deletion; mixing R3 work in would make the commit unrevertable as a unit. |

**Metrics.** Runnable tests 56 to 68 (jar parse and verbatim save, no host rewriting, invalidation
scope, MockWebServer round trip, retry decision). LOC excluding docs and `log3.txt`: +501 / -847.

**Review verdict and fix pass.** Not ready as-is, ready after two items: remove the jar from
`MediaUrlValidator` and keep `log3.txt` out of the commit. Both done. F3's KDoc correction landed;
F5's retry landed as `shouldRetryBeforeSolve`. F4, F6, F7, F8 and F10 were carried to Wave 3 by
agreement.

**Regression watch and device checks owed.**

- Topcinema `loadLinks`: two redirect-dependent fetches stayed on plain `app.get`, so they went with
  no cookies and no interceptor. Closed in Wave 3, but the device check on `/watch/` and `/download/`
  is still the proof.
- FaselHD sibling host (`w312x` versus `w318x`): works only if Cloudflare set `cf_clearance` with a
  parent `Domain`. Not verifiable from code.
- Provider A solves, provider B's session must survive.
- One `cf_clearance` after a re-solve, not two (the F3 question).
- Cold start: the jar writes what the server said, so a session-only clearance no longer survives a
  process restart. Browser-correct, but a visible change.

## 6. Wave 3: delete dead code and collapse duplicates

Commit `02d55477`.

**What changed.** 13 unreferenced shared files deleted (`WebViewFlowHelper`, `LazyExtractor`,
`GenericParser`/`ParserSpec`/`BaseParser`, `ProviderStateStore`, `DirectHttpStrategy` with
`StrategyTypes` trimmed to `VideoSource`, `JWPlayerExtractor`, `EvalDeobfuscator`, `PackerUnpacker`,
`LinkResolvers`, `RefererRotator`, `LazyExtractorLink`), plus dead members from
`ProviderHttpService` (including the injected `parser`), `CloudflareDetector`, `ProviderLogger`,
`ProviderConfig` and `ByseExtractor`. Collapsed: `parseCookieString` three ways and the DisableDevtool
shim four ways into `webview/WebViewShared`, the deleted-video phrase base, `fixUrl` three ways onto
`util/UrlUtils`, and four byte-identical `ExternalEarnVidsExtractor` copies into shared
`EarnVidsExtractor`. `getDocument` now parses with the final URL, which let Topcinema's two
redirect-dependent calls move onto `httpService` and closed Wave 2 F2. CimaNow's hollow
`withSessionGuard` inlined.

**Why.** The cost model is inverted: every line in `shared/` costs 41 times its size on device, so
dead code is not free (`shared-architecture-review.md:74`, `:76`).

**Decisions and rationale.**

| Decision | Alternative rejected | Why |
|---|---|---|
| Keep `ui/TvMouseComponents.kt` and `webview/WebViewTypes.kt` | Delete both, as both planning docs instructed | They are live: `TvMouseController` is constructed by `CfBypassEngine` and `NavigationEngine`, and every top-level type in `WebViewTypes.kt` has 4 to 52 external references. The roadmap was wrong; both docs were corrected. Same for `SessionState.withDomain` and `SnifferSelector.waitAfterClick`. |
| Fold EarnVids into the shared extractor, keeping the registered `getUrl` path with its embed referer and decoders before raw m3u8 | Restore `getUrl`'s old `finalUrl` referer and confine the copies' semantics to `extractDirect` | The fold is a union, not a pure subtraction: `getUrl` now sends `referer ?: finalUrl`, the packer substitutes the page URL for `location`, and Hex and Packer run before Raw. All three affect the six already-registered hosts, not only the four collapsed providers. Recorded as sanctioned changes in the commit message; the owner may veto and revert F1's referer. |
| Register `fdewsdc.sbs` | Leave it unregistered | It was reachable by `loadExtractor` and matched nothing. Additive in an otherwise subtractive wave, so it is called out explicitly. |
| Keep ArabSeedV4Parser's and KooraLive's `fixUrl` local | Collapse all five copies | Different semantics: one adds a scheme to a bare hostname, the other rewrites its own rotating hosts. Collapsing them would have changed behaviour. Both carry a KDoc saying why. |
| Do not propagate the "MailRue" typo | Keep the name for label stability | The shared extractor is otherwise identical. `loadExtractor` matches on `mainUrl` prefix, not name, and the only by-name caller is `requireReferer`, where both agree, so the change is a link label only. |
| Restore CRLF on `Replaymatch.kt` | Ship the LF rewrite | The rewrite turned a 10-line change into a 553-line diff (`git diff -w --stat`: 1 insertion, 10 deletions). Reviewability won. |

**Metrics.** Runnable tests 68 to 83. `MyCimaProvider.cs3` 657,385 to 586,889 bytes; its
`classes.dex` 1,687,368 to 1,495,100 bytes. LOC excluding docs and `log3.txt`: +560 / -4,129.

**Review verdict and fix pass.** Ready, subject to housekeeping (drop `log3.txt`, revert the CRLF
churn), a process condition (coordinator confirms build, tests and dex size), and owner sign-off on
F1, F2 and F4 as intentional behaviour changes. No High findings. The deletion audit verified zero
remaining references per file and member, and confirmed the registry invariant: no
`registerExtractorAPI` line removed, exactly one added.

**Regression watch and device checks owed.** The manual check on Animerco, Lodynet and Shahid4u is
now load-bearing because of the strategy-order change; Replaymatch is a control because it never
called its own copy. Also: the packer regex lost `DOT_MATCHES_ALL`, so a multi-line `eval(function(
p,a,c,k,e,d)` body no longer matches, and `EARNVIDS_HOSTS` and the registration block are hand-
mirrored, so adding a host to one and not the other is silent.

## 6a. Wave 4a: `WebViewSession` base

Commit: see the status table (first of the three Wave 4 commits; roadmap requires them separate).

**What changed.** New `shared/.../webview/WebViewSession.kt` (abstract base): a per-engine `Mutex`
so `runSession`/`fetch` calls serialise, a per-session `CoroutineScope(SupervisorJob() + Main)`
cancelled in `cleanup()`, `@Volatile resultDelivered`, `launchInSession`, `createWebView` via
`WebViewFactory`, shared `extractHtml`, `extractCookies`, `cleanupWebView`. `CfBypassEngine`,
`VideoSnifferEngine` and `ChromiumFetcher` extend it: 3 + 13 + 1 bare
`CoroutineScope(Dispatchers.Main).launch` calls rerouted into the session scope, duplicated
HTML/cookie/teardown code deleted, `ChromiumFetcher.fetchMutex` replaced by the base mutex (its 30 s
WebView reuse cache kept). `NavigationEngine`'s `navigator.plugins = [1,2,3,4,5]` spoof, a documented
bot tell, replaced by the sniffer's array-like fake, now one constant in `WebViewShared`.
`.gitattributes` (`* text=auto eol=lf`) added; no files renormalised yet.

**Why.** Wave 4 acceptance criterion 5 (concurrent sessions serialise, delivery flag volatile) and
the review findings that engines leaked unstructured coroutines and duplicated ~45-line blocks. The
Wave 1 fingerprint invariant becomes structural: every engine WebView is created by the base.

**Decisions.**

| Decision | Alternative rejected | Why |
|---|---|---|
| Teardown stays in the caller's `finally`, guard re-keyed on `deferred.isCompleted` | wrap each deliverer in its own `try/finally` | the caller's `finally` is the one place still alive after the session scope is cancelled; wrapping would double the teardown path |
| `cleanup()` runs only from `withSession`'s `finally` | cancel the scope from the delivery path | delivery runs inside the scope; cancelling there would cancel `deferred.complete` |
| `extractHtml` returns `String` ("" on failure) | `String?` | all three engines already returned "" |
| ChromiumFetcher detaches its `WebViewClient` in `fetch`'s `finally` | leave as is | a straggler `onPageFinished` on the reused WebView could set the instance flag for the next fetch |
| `NavigationEngine` not made a subclass yet | do it now | Wave 4c moves it into CimaNow; making it a subclass first would be churn |

**Metrics.** Tests 83 to 95 for this slice (12 new: `WebViewSharedTest` 4, `EngineFlagsTest` 3,
`WebViewSessionTest` 5 using `kotlinx-coroutines-test` 1.7.1 with `Dispatchers.setMain`). All 41
modules compile.

**Review.** `docs/reviews/wave-4a-review.md`: not ready as-is, ready after F1. F1 (P1) was a real
regression introduced by the scope cancellation: a caller cancelled while a deliverer was suspended
on the cookie read skipped teardown and leaked the WebView and dialog. Fixed as above. F2 straggler
callback fixed. F3 contention log line added. F4 unused `userAgentOverride` parameter dropped. F5
rename. F7 test comment. Deferred: F6 (`git add --renormalize` as its own commit), F8 (sniffer
player-mode teardown), the intercept hook the roadmap listed (no engine shares one yet).

**Regression watch.** Parallel server sniffs on one provider now queue behind a single 3-hour
sniffer session instead of racing (intended by criterion 5; the new log line makes it visible).
Device checks owed: CimaNow freex flow, one CF solve per provider under parallel search, session
cancel lines in `log3.txt`.

## 7. Cumulative status

| Wave | Commit | Runnable tests after | LOC (all files, `git show --stat`) | LOC (excluding `docs/`, `log3.txt`) | Status |
|---|---|---:|---|---|---|
| 0 | `35753f59` | 22 | 34 files, +1,700 / -211 | +400 / -211 | Committed; device checks owed |
| 0 fixes | `a5c1d50e` | 22 | 4 files, +86 / -4 | +5 / -3 | Committed |
| 1 | `6462aa91` | 56 | 62 files, +1,454 / -665 | +1,228 / -665 | Committed; device capture owed |
| 2 | `aae6481f` | 68 | 21 files, +651 / -847 | +501 / -847 | Committed; four device checks owed |
| 3 | `02d55477` | 83 | 57 files, +811 / -4,143 | +560 / -4,129 | Committed; EarnVids device check owed |
| 4a | see git log ("Wave 4a") | 95 | see commit | see commit | Committed; device checks owed |
| 4b-1 | uncommitted | 105 | - | - | Implemented (`FetchOutcome`, `RequestQueue.Host`), under review |
| 4b-2, 4b-3 | not started | - | - | - | Design at `docs/wave-4b-design.md` |
| 5 | not started | - | - | - | Discussion item: `shared-core` into the APK |
| 6 | not started | - | - | - | Domain handling under R3, last by owner decision |

Cumulative across Waves 0 to 3, excluding docs and `log3.txt`: +2,694 / -5,855, a net reduction of
3,161 lines, and 18 to 83 runnable tests.

## 8. Open items and carry-overs

Deduplicated from the four reviews' "Carry into" sections.

| # | Item | Source |
|---|---|---|
| 1 | The `X-Requested-With` policy has two documented exceptions: `NavigationEngine`'s `HttpURLConnection` re-issues still send `""` and `" "` (measured values per the comments there). Record or close them when the CimaNow path is revisited. | wave-1 carry |
| 2 | Hand-built `Accept-Language` literals remain in shared extractors that go through `app.get`, which has no interceptor (`EarnVidsExtractor`, `VKVideoEmbed`, `VidobaExtractor`, `ReviewRateExtractor`). Vidoba's `ExtractorLink` therefore sends a locale no WebView tier sends. | wave-1 F10 |
| 3 | Topcinema's two `loadLinks` fetches are now routed through `httpService`, so they carry the jar and the CF fallback. Device-verify `/watch/` and `/download/`. | wave-2 F2, closed in wave 3 |
| 4 | `SPOOFING_JS` reports `navigator.plugins` as a real `Array [1,2,3,4,5]`, which is exactly the bot signal CimaNow probes. CimaNow opts out; every other `NavigationEngine` caller still gets it. Unify on the sniffer's array-like fake in Wave 4. | wave-3 F11 |
| 5 | `RegexOption.DOT_MATCHES_ALL` on the EarnVids packer regex; register hosts from `EARNVIDS_HOSTS`; hoist the strategy list so `getUrl` and `extractDirect` share one pipeline; delete the now-unused `ProviderHttpService.mediaValidator`. | wave-3 F7, F9, F10 |
| 6 | Wave 6 domain items: delete `SessionProvider` entirely, the `DENYLISTED_HOSTS` denylist and `isValidProviderDomain` as a decision, the `trustedDomains` field, every `allowedDomains` parameter, the `www.` prefix stripping, and the five copies of domain string parsing; add behavioural adoption, a persisted host history and the two-success sync rule. Also the registrable-domain cookie expiry deferred from Wave 2 F3. | waves doc Wave 6 |
| 7 | Wave 5 question, still unanswered: how many older plugin builds must a new app keep loading? That sizes the `requiresCoreApi` compatibility layer. Plus the recommended app-side fix: scope or drop `removeAllCookies` in `CloudflareKiller`'s `init`. | third-eye open question 6, waves doc Wave 5 |
| 8 | The `SYNC_SECRET` story: either the client sends the header, which an open-source plugin cannot keep secret, or the criterion is dropped and the 22-name allowlist is the whole defence. Ties to Wave 6. | wave-0 carry 2 |
| 9 | Test seams still open: `chromeBrandMetadata` is private and Android-bound, so nothing asserts the WebView brand spelling equals `Fingerprint.secChUa`; `playbackHeaders` has no null-referer case; the EarnVids strategy order has no fixture test; `MediaUrlValidator` has no wire test proving the source cookie reaches the wire unchanged. | waves 1 to 3 test gaps |
| 10 | Diff hygiene: add `.gitattributes` with `eol=lf`; keep `log3.txt` out of wave commits. | wave-3 F5, F6 |

## 9. How to verify locally

```sh
# unit tests (the CI test job, .github/workflows/build.yml:44)
./gradlew :FaselHDV2Provider:testDebugUnitTest

# injected-JS check (the CI test job, :47)
node shared/tools/check-injected-js.js

# full compile across all 41 modules
./gradlew compileDebugKotlin

# plugin build, for size (the CI build job, :79); then measure
./gradlew make makePluginsJson
ls -l MyCimaProvider/build/MyCimaProvider.cs3      # expect 586,889 B
unzip -l MyCimaProvider/build/MyCimaProvider.cs3   # classes.dex, expect 1,495,100 B
```

Acceptance greps, per wave:

```sh
# Wave 0
grep -rn "postman-echo\|ENABLE_WEBVIEW_REMOTE_DEBUGGING = true" shared/          # empty
grep -rn "open fun searchNormal(query: String)" shared/                          # empty

# Wave 1 (R1/R2)
grep -rn "Mozilla/5.0" shared/src/main/kotlin                                    # empty
grep -rn "Mozilla/5.0" --include=*.kt . | grep -v /build/ | grep -v src/test     # API clients only
grep -rn 'X-Requested-With"\] = ""' shared/src/main                              # NavigationEngine only

# Wave 2 (D1)
grep -rn "CookieLifecycleManager\|SessionStore\|updateCookies\|restoreSession" shared/   # empty
grep -rn "removeAllCookies" --include=*.kt .                                     # empty

# Wave 3
git grep -n registerExtractorAPI -- '*Plugin.kt' shared/                         # no registration lost
grep -rn "ExternalEarnVidsExtractor" --include=*.kt .                            # empty

# Wave 6 pre-check (must still return hits until Wave 6 lands)
grep -rn "areDomainsRelated\|domainAliases\|MULTI_PART_TLDS\|trustedDomains\|allowedDomains\|DENYLISTED_HOSTS" --include=*.kt .
```

## 10. Source discrepancies and what this document uses

| Claim | Sources disagree | Used here |
|---|---|---|
| Wave 3 deleted LOC | Commit `02d55477` and the waves-doc Result block say 4,129 deleted / 198 added excluding docs and `log3.txt`; `wave-3-review.md` says 3,625 LOC across the 20 deletion-only files and `+474 / -4,398` for `git diff HEAD` over 47 files; the waves-doc Scope table totalled about 3,387 before its correction. | `git show --numstat 02d55477` excluding `docs/` and `log3.txt`: **+560 / -4,129**. The deletion figure matches the commit message; the addition figure does not, because the review's and the commit's numbers were taken from a working tree that also held the docs edits. The 3,625 figure counts only files deleted outright, so it is a subset, not a contradiction. |
| Wave 1 new tests | Commit `6462aa91` says 21 new, 56 total. | `git grep -c @Test` across the two commits: 22 to 56, so **34 new**. The commit message counted the tests written before the fix pass; the fix pass added 13 more. Totals (56) agree. |
| Wave 3 new tests | Commit says 11 new, 83 total; the waves-doc Result block says 83 total as "78 pre-existing plus the five in `EarnVidsStrategyOrderTest`", which implies 78 before, not 68. | Counted: 68 to 83, so **15 new**. The Result block's 78 is wrong; the 83 total is right and agrees with the commit. |
| Baseline test count | The waves doc says 18 tests, 4 classes; `git grep -c @Test` at `50df2f90` returns 19. | **18 runnable.** The nineteenth is inside the fully commented-out `shared/src/test/.../FaselHDExtractorTest.kt`, which contributes nothing. |
| `WebViewTypes.kt` and `TvMouseComponents.kt` | Listed as dead in both `shared-architecture-review.md` section 7a and the Wave 3 scope table. | **Live**, per the Wave 3 deletion audit. Both docs were corrected in `02d55477`. |
| Wave 3 acceptance criterion 4 | Says `registerSharedExtractors` should stop registering "the 4 external EarnVids duplicates". | Malformed: the four copies were plain `object`s and were never registered anywhere. The intent (no per-provider copy remains) is met. |
| Wave 1 commit verdict | `wave-1-review.md`'s fix-pass section ends "not ready" on N1. | N1 was fixed before the commit: `6462aa91` carries `sessionUa()` at `NavigationEngine.kt:208`. The review text was not updated. |
