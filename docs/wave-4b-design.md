# Wave 4b design: `HttpGateway` + `FetchOutcome` + `ProviderRuntime`

Scope: the `ProviderHttpService` split half of Wave 4 (`docs/shared-refactor-waves.md`, "## Wave 4"), excluding the `WebViewSession` base (Wave 4a, done in the working tree: `webview/WebViewSession.kt`; `CfBypassEngine`, `ChromiumFetcher`, `VideoSnifferEngine` are subclasses) and excluding the `NavigationEngine` move (Wave 4c; only its ownership changes here, see section 5).

Baseline: `ProviderHttpService.kt` is 992 lines at `02d55477`, not the 1,406 quoted in the roadmap. Line citations are against that file.

Owner decisions on the open questions (2026-09-07): eager `api.runtime` at `Plugin.load` is acceptable because the gateway's heavy fields are lazy; the CimaNow freex device check is a release gate for 4b-2; the dead `BaseProvider.handleDomainDifference` stays until Wave 6 (R3 non-touch).

## 1. Usage census

Callers counted with `grep -rEn "httpService(\?)?\.<member>\b" --include='*.kt' .` excluding `/build/`. Only `BaseProvider.kt` reaches the class by type (`:11`, `:85`) plus `CimaNowSession.kt:4,29,380`.

| Member (decl) | Call sites | Where | Fate |
|---|---|---|---|
| `getDocument` `:377` | 83 | 27 files; top: CimaNowProvider 8, BaseProvider 7, EgyDead 7, Krmzy 6, Cimawbas 6 | `ProviderRuntime.document` |
| `getDocumentNoFallback` `:485` | 9 | BaseProvider:259, anim3rb:153,194, ArabseedV4:54, Animerco:79, eishk:50, Gesseh:76, FaselHDV2:177, Cimawbas:92 | `document(solveCf = false)` |
| `getText` `:131` | 31 | 12 files; top: CimaLeek 8, CimaNow 4, KooraLive 4, Krmzy 4, Animerco 4 | `ProviderRuntime.text` |
| `getRaw` `:217` | 22 | CimaNowProvider 10, Anim3rb 6, Krmzy 2, TukTukcima 2, CimaNowSession:154 | `ProviderRuntime.raw` |
| `postText` `:273` | 14 | 10 files | `ProviderRuntime.post` (returns `String?`) |
| `post` `:267` | 2 | FaselHDV2:141, EgyDead:322 | `post` + caller-side `Jsoup.parse` |
| `postDebug` `:282` | 2 | ArabseedV4:119,162 | `post`; both only read `success && html` |
| `sniffVideos` `:287` | 1 | Krmzy:791 | `ProviderRuntime.sniff` |
| `getImageHeaders` `:531` | 21 | BaseProvider 5, Anim3rb 5, eseek 4, ArabSeed 2, CimaNow 2, FaselHD 2, Wecima 1 | `ProviderRuntime.imageHeaders` |
| `getImageHeadersFull` `:541` | 5 | Cimawbas:48,77,98,149,168 | delete; alias of the above |
| `ensureInitialized` `:87` | 45 | BaseProvider 5, plugins 40 (19 files) | internal to `HttpGateway`; every call site deleted |
| `mainUrl` `:70` | 1 | BaseProvider:33 | `"https://${runtime.domain}"` |
| `currentDomain` `:67` | 1 | EgyDead:329 | `ProviderRuntime.domain` |
| `userAgent` `:73` | 15 | CimaNowProvider 10, TukTukcima:92, SyriaLive:230, YallaShoot:229, KooraLive:152, CimaLeek:518 | delete; interceptor supplies it, or `Fingerprint.current().userAgent` where a string is needed |
| `cookies` `:81` | 4 | anim3rbProvider:249,328,448,496 | delete; `SystemCookieJar` already attaches them |
| `navigationEngine` `:44` | 3 | CimaNowProvider:1340,2444,3135 | delete from gateway; CimaNow owns the engine |
| `snifferEngine` `:84` | 0 | none | delete; drops `VideoSnifferEngine` as a gateway dep |
| `chromiumFetcher` `:47` | 0 | none | internal |
| `fetchViaChromeTls` `:322` | 0 | none | delete |
| `dnsPolicy` `:145` | 0 external | comment only | internal (`applyDnsPolicy :159`) |
| `updateDomain` `:109` | 0 external | internal (`:60`, `:103`, `:831`) | internal |
| `create` `:957`, `instances` `:898` | 1 | BaseProvider:85 | `HttpGateway.forProvider`, same `getOrPut(config.name)` cache |
| internals `:546`, `:670`, `:717`, `:733`, `:784`, `:790`, `:817`, `:842`, `:873`, `:882`, `:891`, `:357-375`, `:916-955`, `:989` | n/a | n/a | move verbatim |

`ProviderHttpServiceHolder`: 8 real usages, not 10. `LarozaExtractor.kt:33`, `CimaNowTVEmbed.kt:34`, `ByseExtractor.kt:342`, `GameHubExtractor.kt:46,120`, `VidobaExtractor.kt:145`, `OkPrimeExtractor.kt:23`, `VKVideoEmbed.kt:33`, `ReviewRateExtractor.kt:49,181`. `FaselHDExtractor.kt:177` is a comment; `Laroza.kt:6-7` are unused imports.

## 2. `ProviderRuntime`

```kotlin
package com.cloudstream.shared.core

interface ProviderRuntime {
    /** Current provider host, no scheme. Replaces currentDomain and mainUrl. */
    val domain: String

    /** Queued, domain-rewriting, CF-solving GET parsed as HTML. solveCf=false throws
     *  CloudflareBlockedSearchException instead of solving. Replaces getDocument and
     *  getDocumentNoFallback. */
    suspend fun document(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        solveCf: Boolean = true,
        adoptRedirect: Boolean = false,
        allowCached: Boolean = false
    ): Document?

    /** Unqueued GET body as text, no domain rewriting, Tier-3 Chrome-TLS retry kept. Replaces getText. */
    suspend fun text(pathOrUrl: String, headers: Map<String, String> = emptyMap()): String?

    /** Form POST body as text. Replaces postText, post and postDebug. */
    suspend fun post(
        pathOrUrl: String,
        form: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        rewrite: Boolean = false
    ): String?

    /** Raw okhttp3.Response with session identity, no CF solve. Replaces getRaw verbatim. */
    suspend fun raw(url: String, headers: Map<String, String> = emptyMap(), useSession: Boolean = true): okhttp3.Response

    /** Headless-then-fullscreen CF WebView load, regex-scraped video sources. Replaces sniffVideos. */
    suspend fun sniff(url: String): List<VideoSource>

    /** One image header shape from Fingerprint.imageHeaders. Replaces getImageHeaders and getImageHeadersFull. */
    fun imageHeaders(targetDomain: String? = null): Map<String, String>
}
```

7 members, one slot spare. `HttpGateway` implements it directly; there is no separate `ProviderScope` class.

Deviations from the third-eye proposal, each forced by a call-site count:

| Third-eye | Here | Reason |
|---|---|---|
| `url(path)` | dropped | 1 caller, served by `domain`; `document`/`text`/`post` accept a path (`buildUrl :784`) |
| `post(...): Document` | `post(...): String?` | 14 `postText` sites vs 2 `post` sites |
| no `text` | `text` added | 31 sites, and `raw` is not a substitute: `getText` has the Tier-3 Chrome-TLS retry (`:632-661`), `getRaw` has none |
| `raw(..., cookies)` | `raw(..., useSession)` | current parameter name (`:220`); none of 22 sites passes it |
| `sniff(url, exit, mode)` | `sniff(url)` | single caller passes only a URL; implementation hardcodes HEADLESS then FULLSCREEN (`:288-315`) |
| `playbackHeaders(url, referer)` | dropped | zero provider callers; extractors call `Fingerprint.playbackHeaders` directly |
| `solveCf=false` replaces `skipHeadless`/`webViewEnabled` | replaces `getDocumentNoFallback` only | those config flags are set once in `BaseProvider.kt:92` and never varied; Wave 6 |
| `imageHeaders()` | `imageHeaders(targetDomain = null)` | keeps the existing signature; zero migration cost |

Migration of what providers lose:

| Lost member | Sites | New call |
|---|---|---|
| `ensureInitialized()` | 40 | delete the line; the gateway calls it at the head of every entry point, and `BaseProvider` calls it before reading `mainUrl` |
| `userAgent` | 15 | delete the hand-built `User-Agent` entry (the interceptor supplies it; equal caller UA is a no-op); where a string is needed (CimaNowProvider:1161,1165,2447,3056) use `Fingerprint.current().userAgent` |
| `cookies` | 4 | delete; Anim3rb passes a Cookie header to `getRaw`, which already has the jar |
| `getImageHeadersFull()` | 5 | `imageHeaders()` |
| `postDebug(...)` | 2 | `post(...)`; `success && html != null` becomes `!= null` |
| `post(...): Document?` | 2 | `runtime.post(...)?.let { Jsoup.parse(it, url) }` |
| `navigationEngine` | 3 | `CimaNowProvider` owns `private val navigationEngine by lazy { NavigationEngine { ActivityProvider.currentActivity } }` |
| `mainUrl` | 1 | `MainAPI.mainUrl` getter returns `"https://${runtime.domain}"` |
| `getDocument(rewriteDomain = ...)` | 72 pass `true`, 0 pass `false` | drop the parameter; always rewrites |
| `getDocument(checkDomainChange = true)` | 10 | `document(..., adoptRedirect = true)`, same body (`:404-406`, `:817-835`) |
| `getText(rewriteDomain = false)` | 17 explicit, 0 pass `true` | drop the parameter |

## 3. `FetchOutcome`

```kotlin
package com.cloudstream.shared.core

sealed interface FetchOutcome {
    data class Success(val html: String, val code: Int, val finalUrl: String) : FetchOutcome
    /** solveAttempted = a CF solve already ran and failed for this URL; do not launch another. */
    data class CloudflareBlocked(val code: Int, val finalUrl: String?, val solveAttempted: Boolean = false) : FetchOutcome
    data class HttpError(val code: Int, val finalUrl: String?, val body: String?) : FetchOutcome
    data class Transport(val cause: Throwable) : FetchOutcome
    data object Cancelled : FetchOutcome
}

/** Pure. No Android, no OkHttp. */
fun classify(code: Int, body: String, finalUrl: String, serverHeader: String? = null): FetchOutcome
```

`classify` rules, in order: `code !in 200..399 && (CloudflareDetector.isBlocked(code, body) || (code in 403/503/429 && serverHeader contains "cloudflare"))` gives `CloudflareBlocked(solveAttempted = false)`; `code in 200..399` gives `Success`; otherwise `HttpError`. It reuses `CloudflareDetector.isBlocked` (`CloudflareDetector.kt:71-76`) so the marker list stays in one place; the code gate inside `isCloudflareResponse` (`:60-62`) closes the `cf_clearance` (`:16`) and `ray_id` (`:21`) false positives. `serverHeader` is new to the HTTP path. The body-only `isCloudflareChallenge(html, responseCode)` overload keeps its behaviour for WebView HTML (`CfBypassEngine.kt:221,238`, `sniffVideos :299`).

`RequestResult` (`RequestQueue.kt:303-343`, 29 references, all inside `RequestQueue.kt` and `ProviderHttpService.kt`) is deleted; `FetchOutcome` is the single result type.

The string-matched literals at `ProviderHttpService.kt:422-425`:

| Literal | Produced at | Becomes |
|---|---|---|
| `"CF solve failed"` | `RequestQueue.kt:154` | `CloudflareBlocked(solveAttempted = true)` |
| `"CF re-solve failed"` | `RequestQueue.kt:229` | `CloudflareBlocked(solveAttempted = true)` |
| `"CF Bypass failed"` | `ProviderHttpService.kt:780` | `CloudflareBlocked(solveAttempted = true)` |
| `"Cookie verification"` | nowhere | dead condition; deleted |
| `"WebView disabled"` (`:734`) | | `CloudflareBlocked(solveAttempted = true)` (same effect at `:427`) |
| `"User cancelled CF bypass"` (`:778`) | | `Cancelled` |
| `RequestResult.failure(e)` (`:666`, `:713`) | | `Transport` |
| `"Leader request failed"` (`:160`), `failAllFollowers` reasons (`:273`) | | the leader's own outcome, propagated |

Decision sites that switch on `FetchOutcome`:

| Site (current) | Switch |
|---|---|
| `:418-427` CF fallback gate | `is CloudflareBlocked && !solveAttempted` |
| `:489-513` no-fallback throw | `is CloudflareBlocked` or `is HttpError && (code == 403 or body contains "403 Forbidden")` |
| `:601-661` Tier-3 gate and breaker | `is CloudflareBlocked` retries via Chromium; anything else records success |
| `:717-731` response mapping | `classify(...)` |
| `:765-781` solve result mapping | `WebViewResult.Success` to `Success`, `Cancelled` to `Cancelled`, else `CloudflareBlocked(solveAttempted = true)` |
| `RequestQueue.kt:76-162` leader `when` | `Success` / `CloudflareBlocked(!solveAttempted)` / else |
| `RequestQueue.kt:185`, `:208` verify gate | `is Success` |
| `:246-263` `getRaw` warning | `classify(code, peekedBody, url, server) is CloudflareBlocked` |
| `:404`, `:411` adoption and meta-refresh gates | `is Success` |

## 4. `HttpGateway`

```kotlin
class HttpGateway private constructor(
    private val config: ProviderConfig,
    private val context: Context,
    private val activityProvider: () -> Activity?,
    private val fingerprint: () -> Fingerprint = { Fingerprint.current() }
) : ProviderRuntime, RequestQueue.Host {
    private val domainManager by lazy { DomainManager(context, config.name, config.fallbackDomain, config.githubConfigUrl, config.syncWorkerUrl) }
    private val cf by lazy { CfBypassEngine(activityProvider) }
    private val chromium by lazy { ChromiumFetcher(activityProvider) }
    private val queue = RequestQueue(this)
    companion object { fun forProvider(context: Context, config: ProviderConfig, activityProvider: () -> Activity?): HttpGateway }
}
```

Dependency choices: `Fingerprint` is a `() -> Fingerprint` seam so `imageHeaders()` is JVM-testable; `SystemCookieJar` is not injected (stateless, constructed per client today at `:238`, `:577`, `:693`); engines and `DomainManager` are lazy so `forProvider` allocates nothing heavy (section 6 calls it from `Plugin.load`). `VideoSnifferEngine` and `NavigationEngine` are not deps (`snifferEngine` has 0 callers; `sniffVideos :288` uses `cfBypassEngine`).

Public surface: the 7 `ProviderRuntime` members, `suspend fun ensureInitialized()`, and `forProvider`. Everything else private.

Pipeline for `document(path, headers, solveCf, adoptRedirect, allowCached)`:
1. `ensureInitialized()` (`:87-106` verbatim).
2. `buildUrl` (`:784`).
3. `allowCached` probe (`:389-396`; `CachedPage`/`recentPages` `:357-375` verbatim).
4. `solveCf == false`: `executeDirect` only, no queue (`:486-487`); validate the resolved host before adoption (`:497-505`); throw on blocked (`:509-513`); meta-refresh (`:517-521`); return.
5. `solveCf == true`: `queue.enqueue(url, headers)` (`:399`).
6. On `Success` and `adoptRedirect`: `checkAndUpdateDomain` (`:404-406`, `:817-835`).
7. Meta-refresh follow (`:411-414`, `:842-867`).
8. On `CloudflareBlocked(!solveAttempted)` and `config.webViewEnabled`: breaker gate (`:432`), `queue.enqueueAction { solveCloudflareThenRequest(...) }` (`:441-445`), breaker record (`:447-452`), cache and return (`:461-463`).
9. Cache clean successes (`:471-475`).

Moved verbatim (with `RequestResult` swapped for `FetchOutcome`): `executeDirect :546-668`, `executePost :670-715`, `executeRequestHelper :717-731`, `solveCloudflareThenRequest :733-782`, `rewriteUrlIfNeeded :790-815`, `updateDomain :108-127`, `extractDomain :891-895`, `applyDnsPolicy :159-162`, `applyProviderTimeout :182-190`, `DomainCircuitBreaker :916-955`, `CF_PEEK_BYTES :906`, `shouldRetryBeforeSolve :989-992`. Deleted: `fetchViaChromeTls :322-328`, `getImageHeadersFull :541`, `postDebug :282`, `cookies` + `parseCookieHeader :81, :882-889`, `snifferEngine :84`, `navigationEngine :44, :975`, `videoSnifferEngine :43, :974`, `userAgent :73`, the `Fingerprint.current()` warm-up at `:963`.

`RequestQueue` decoupling. Today the queue is built with four lambdas closing over a half-constructed `this` (`:56-65`). Replace with one nested interface, single implementer:

```kotlin
class RequestQueue(private val host: Host) {
    interface Host {
        suspend fun execute(url: String, headers: Map<String, String>): FetchOutcome
        suspend fun solveCloudflare(url: String, allowedDomains: Set<String>): FetchOutcome
        suspend fun onDomainRedirect(oldHost: String, newHost: String)
        val currentDomain: String
    }
}
```

Four members: `onDomainRedirect` (`:85`, `:132`) and `currentDomain` (`:172`, `:242`) carry R3 semantics that must not change this wave. An interface removes the constructor cycle, names the contract, and gives tests one fake.

## 5. Per-provider scope

`ProviderHttpService.instances` (`:898`) becomes `HttpGateway.instances`, same `getOrPut(config.name)` (`:965`). `ProviderHttpServiceHolder` is deleted; nothing replaces it.

`BaseProvider`:

```kotlin
private val gateway by lazy {
    val context = PluginContext.context ?: throw RuntimeException(...)   // BaseProvider.kt:82-83 unchanged
    HttpGateway.forProvider(context, ProviderConfig(...), { ActivityProvider.currentActivity })
}
val runtime: ProviderRuntime get() = gateway
```

`runtime` is public because `Plugin.load` needs it (section 6). `protected val httpService` (`:81`) and `Holder.initialize` (`:100`) go.

`CimaNowSession` takes `ProviderRuntime` instead of `ProviderHttpService` (`:29`, `:380`); it uses only `getRaw` (`:154`) and `getDocument` (`:384`).

The 8 Holder-using extractors all need a fetch, not just identity, which contradicts the third-eye note that only Laroza needs the gateway. Each passes an absolute foreign URL plus an explicit `Referer`, so none needs domain rewriting or per-provider state:

| Extractor | Needs today | New constructor |
|---|---|---|
| `LarozaExtractor.kt:33,47` | `getDocument(url, headers)` | `LarozaExtractor(host, name, runtime)` |
| `CimaNowTVEmbed.kt:34-37` | `getText(url, headers)` | `CimaNowTVEmbed(runtime)` |
| `ByseExtractor.kt:342-348` | `getText` as `app.get` fallback | `ByseExtractor(host, name, runtime)` |
| `GameHubExtractor.kt:46-47,120-121` | `getText` twice | `GameHubExtractor(runtime)` |
| `VidobaExtractor.kt:145-152` | `getDocument(url, headers)` | `VidobaExtractor(runtime)` |
| `OkPrimeExtractor.kt:23,36` | `getDocument(url, headers)` | `OkPrimeExtractor(runtime)` |
| `VKVideoEmbed.kt:33,43` | `getText(url, headers)` | `VKVideoEmbed(runtime)` |
| `ReviewRateExtractor.kt:49-53,181-182` | `getText` twice | `ReviewRateExtractor(runtime)` |

`ExtractorApi.getUrl` is fixed by the app, so constructor injection is the only option. Two consequences: the `?: return`/`?: throw` null branches disappear (a constructed instance always has its runtime); and because `extractorApis` is global and `loadExtractor` picks the last registration by `mainUrl` prefix, an instance built by plugin B can serve a link raised by plugin A. That is strictly better than the Holder (one global winner, possibly null), but it makes an invariant load-bearing: **`text` and `raw` must never rewrite the URL and must derive their default `Referer` from the target host, not the provider domain.** Both hold today (`:560` derives `Referer` from `urlDomain`; `text` never rewrites); `RuntimeIsHostDerivedTest` pins it.

## 6. `registerSharedExtractors`

Today: 21 call sites with no argument (`MyCimaPlugin.kt:14`), each registering about 40 instances into the app-global `extractorApis` (`SharedExtractors.kt:19-87`).

Minimal change: one parameter, one list, unchanged order.

```kotlin
fun Plugin.registerSharedExtractors(runtime: ProviderRuntime) { ... }
```

Call sites, e.g. `MyCimaPlugin.kt:11-15`:

```kotlin
val api = MyCima()
registerMainAPI(api)
registerSharedExtractors(api.runtime)
```

`api.runtime` resolves the lazy at plugin load on the main thread; with lazy engine/DomainManager fields and no `Fingerprint` warm-up, the cost is one `ProviderConfig` plus one object. If measurement later disagrees, the fallback is `registerSharedExtractors(runtime = { api.runtime })` with the 8 extractors holding a `() -> ProviderRuntime`.

The per-plugin duplication in `extractorApis` is not fixed here and cannot be (one classloader per plugin, one copy of shared each). This change binds each copy correctly instead of resolving a global. The real fix is Wave 5.

Name collisions with app built-ins (`MailruExtractor.kt:15-16` vs `MailRu`, `OdnoklassnikiApiExtractor.kt:15` on `ok.ru`, Vidmoly proxies at `SharedExtractors.kt:58-59`): leave the names alone. `loadExtractor` matches on `mainUrl` prefix in reverse registration order and never consults the name (`ExtractorApi.kt:857-861`, per `docs/reviews/wave-3-review.md`). The only name-sensitive path is `getExtractorApiFromName`, whose sole caller is `requireReferer`, and both implementations return `false`. Revisit in Wave 5.

## 7. Parser rename

`BaseParser` was deleted in Wave 3, so the name is free. Changes: `parsing/NewBaseParser.kt` becomes `BaseParser.kt`; `abstract class NewBaseParser : ParserInterface` (`:69`) becomes `BaseParser`; `TAG` (`:71`) updated; `BaseProvider.getParser()` (`:78`) retyped `ParserInterface`.

Mechanical: 160 occurrences in 86 files (84 provider files, the parser file, `BaseProvider.kt`, `SearchPagingTest.kt`). One `sed -i '' 's/\bNewBaseParser\b/BaseParser/g'` over `*.kt` plus the file rename, then compile.

Retyping `getParser()` is safe: `BaseProvider` uses only members declared on `ParserInterface` (`parseMainPage`, `parseSearch`, `getSearchUrl`, `hasNextSearchPage`, `supportsSearchPagination`, `parseLoadPageData`, `getPlayerPageUrl`, `extractWatchServersUrls`, `buildServerSelectors`). Overrides returning a concrete parser stay legal (covariant return). The three casts (`ArabseedV4.kt:102`, `FaselHDV2.kt:76`, `FaselHDV2.kt:246`) keep working.

## 8. Test plan

JVM only, JUnit 4, `:FaselHDV2Provider:testDebugUnitTest`.

| Class | Test | Asserts |
|---|---|---|
| `FetchOutcomeTest` | `cfChallengeIsBlocked` | 403 plus a challenge body gives `CloudflareBlocked` |
| | `plainCfClearanceCookieIsNotBlocked` | 200 whose body merely contains `cf_clearance` gives `Success` |
| | `responseCodeIsActuallyUsed` | same body, 200 vs 503, classify differently |
| | `noStringMatchingOnErrorMessages` | equal human text, different codes, not equal |
| | `serverHeaderAloneBlocksOnCfCode` | 503 with `Server: cloudflare` and no markers gives `CloudflareBlocked` |
| | `solveAttemptedSuppressesResolve` | `CloudflareBlocked(solveAttempted = true)` fails the `document` fallback gate |
| `ProviderRuntimeSurfaceTest` | `hasAtMostEightMethods` | `ProviderRuntime::class.java.declaredMethods.size <= 8` (default-arg bridges live in `DefaultImpls`, not on the interface) |
| `RequestQueueTest` | `secondCallerForSameHostParks` | fake `Host` records calls; two concurrent enqueues on one host produce one `execute` |
| | `followersRunAfterLeaderSuccess` | each follower completes exactly once |
| | `cfBlockedLeaderSolvesOnceThenVerifies` | one `solveCloudflare`, verifier runs before the rest |
| | `leaderTransportFailurePropagatesToFollowers` | followers receive the leader's `Transport` |
| `RuntimeIsHostDerivedTest` | `defaultRefererFollowsTargetHost` | mockwebserver: `text()` to the server records `Referer` equal to the server host, never the provider domain |
| `CfRetryDecisionTest` | existing tests | move with `shouldRetryBeforeSolve` to package `core` |

Pure seams: `classify(...)`, `RequestQueue.Host`, `HttpGateway.fingerprint: () -> Fingerprint`. `HttpGateway` itself is not unit-tested (touches `app.baseClient`, `SystemCookieJar`, WebViews).

## 9. Commit split

**4b-1: `FetchOutcome` plus `RequestQueue` decoupling.** Compiles with `ProviderHttpService` still in place. Files: add `core/FetchOutcome.kt`; change `queue/RequestQueue.kt` (ctor `:25-30`, leader `:72-163`, followers `:165-264`, `failAllFollowers :266-277`, delete `RequestResult :303-343`); change `service/ProviderHttpService.kt` (implement `RequestQueue.Host`, replace `:56-65` with `RequestQueue(this)`, retype `:546`, `:670`, `:717`, `:733`, rewrite gates at `:418-427`, `:489-513`, `:601-661`); add `FetchOutcomeTest`, `RequestQueueTest`. Risk: the `:422-425` gate is behaviour-carrying; `solveAttempted` is set at exactly the three sites that produced the three live literals.

**4b-2: `HttpGateway` plus `ProviderRuntime` plus migration.** Files: add `core/ProviderRuntime.kt`, `core/HttpGateway.kt`; delete `service/ProviderHttpService.kt`, `service/ProviderHttpServiceHolder.kt`; change `provider/BaseProvider.kt`, 8 extractors, `extractors/SharedExtractors.kt`, 19 plugin files, 28 provider files, `CimaNowSession.kt`; move `CfRetryDecisionTest`. Steps: (1) `HttpGateway` as a copy of `ProviderHttpService` with the section 4 deletions and `ProviderRuntime` implemented; (2) point `BaseProvider` at it and expose `runtime`; (3) delete both old files, let the compiler enumerate call sites; (4) mechanical passes, each compiled: `getDocument` to `document`, `getDocumentNoFallback` to `document(solveCf = false)`, `getText` to `text`, `postText`/`post`/`postDebug` to `post`, `getRaw` to `raw`, `getImageHeaders*` to `imageHeaders`, delete `ensureInitialized`, delete `userAgent`/`cookies` header building, `navigationEngine` to a CimaNow field; (5) extractor constructors and `registerSharedExtractors(runtime)`; (6) plugins. Risks: the 40 deleted `ensureInitialized` calls (mitigated by gateway-side calls at every entry point); the 15 deleted `User-Agent` headers change CimaNow's freex requests on the wire (freex device check is a release gate); eager `api.runtime` at load (mitigated by lazy fields); CimaNow owning `navigationEngine` locally.

**4b-3: parser rename.** `sed` plus one file rename plus the `getParser(): ParserInterface` retype. 86 files, no behaviour. Kept separate so `git log --follow` on the parser stays readable.

## 10. R3 leak table

Everything is a move or re-plumbing, not a rule change. Nothing new decides on a domain name.

| Site | Touch | Why unavoidable | Residue for Wave 6 |
|---|---|---|---|
| `ProviderHttpService.kt:108-127` `updateDomain` | moved verbatim incl. `SessionProvider.addDomainAlias` (`:123`) | file is deleted | alias registration still exists |
| `:817-835` `checkAndUpdateDomain` | moved verbatim | same | name-free adoption rule pending |
| `:790-815` `rewriteUrlIfNeeded` | moved verbatim | same | host-history rewriting pending |
| `:497-505` `isValidProviderDomain` guard | moved verbatim into the `solveCf = false` branch | same | denylist call site survives |
| `:842-867` `handleMetaRefreshRedirect` | moved verbatim | same | third adoption entry point survives |
| `:87-106` `ensureInitialized` domain sync | moved verbatim; now implicit at the head of each gateway call | 45 provider call sites deleted; sync must still precede the first request | unchanged |
| `RequestQueue.kt:112-121` `allowedDomains` | body and parameters unchanged; delivery changes from lambda to `Host.solveCloudflare` | the constructor cycle is the target | honours the explicit non-touch |
| `RequestQueue.kt:80-86`, `:128-133` `onDomainRedirect`; `:172`, `:242` `getCurrentDomain` | lambda to interface member | same | two adoption sites remain in the queue |
| `BaseProvider.kt:388-417` `handleDomainDifference` | not touched (dead, zero callers) | n/a | delete in Wave 6 |
| `DomainManager.kt` | not touched | n/a | n/a |
