# Wave 4b-2 review: `HttpGateway` + `ProviderRuntime` + migration

Reviewed: working tree on top of `9707fb11` (deletions/moves staged, everything else unstaged).
Spec: `docs/wave-4b-design.md` sections 1, 2, 4, 5, 6, 8, 9 (4b-2), 10; `docs/shared-refactor-waves.md`
Wave 4 acceptance criteria 1, 3, 6 and the non-touch clause. Method: `git diff HEAD`, a line diff of
`git show HEAD:shared/.../service/ProviderHttpService.kt` against `shared/.../core/HttpGateway.kt`, and a
read of every touched provider/extractor diff. Gradle was not run; every claim below is by reading.
Line numbers are working-tree unless prefixed `HEAD:`.

## Verdict

**Not ready.** The gateway itself preserves HEAD behaviour region for region, the R1/R2 migration is
clean, the registration audit passes, R3 is untouched, and the init-ordering change is safe. One
migration rule is wrong and it breaks live extraction paths: the design's census "`getDocument`: 72
pass `rewriteDomain = true`, 0 pass `false`" (design section 2) missed the sites that relied on the
**default** `rewriteDomain = false` (8 sites) plus one that passed `false` explicitly. `document()` now
always rewrites the host to the provider's session domain, so three shared extractors and one Krmzy
path fetch `https://<provider-domain>/<foreign-path>` instead of the embed host. Everything else is a
Low or a note.

## Findings

| # | Severity | Where | Issue | Minimal fix |
|---|---|---|---|---|
| F1 | **High** | `shared/.../extractors/LarozaExtractor.kt:43`, `OkPrimeExtractor.kt:34`, `VidobaExtractor.kt:152` | Each calls `runtime.document(url, headers)` with an absolute foreign URL (`mp4.okhd.site`, `okprime.site`, `vidoba.org`). `HttpGateway.document` (`HttpGateway.kt:377`) always goes `requestQueue.enqueue` → `Host.execute` (`:66`) → `executeDirectRequest(url, headers, rewriteDomain = true)` → `rewriteUrlIfNeeded` (`:783-806`), which replaces the host with `sessionState.domain` whenever it differs. At HEAD these three called `getDocument(url, customHeaders)` with the default `rewriteDomain = false`, which took the `enqueueAction { executeDirectRequest(url, headers, rewriteDomain = false) }` branch (`HEAD:398-402`) and never rewrote. Result: every OkPrime/Laroza-embed/Vidoba extraction now requests `https://<provider>/e/<id>`. This also voids the section-5 cross-plugin invariant for the three `document`-based extractors: the design states the invariant for `text`/`raw` only, but maps these three onto `document`. | Add `rewrite: Boolean = true` to `ProviderRuntime.document` (a parameter, not an eighth member) and route `rewrite = false` to the HEAD branch: `requestQueue.enqueueAction(url) { executeDirectRequest(url, headers, rewriteDomain = false) }`. Pass `rewrite = false` at the three extractor sites. Restores `HEAD:398-402` verbatim. |
| F2 | **High** | `Krmzy/src/main/kotlin/com/krmzy/KrmzyProvider.kt:480` | HEAD (`HEAD Krmzy:482`) passed `rewriteDomain = false` explicitly for `resolvedExtractorUrl`, which can be the inner `url=` target (`:424-434`), i.e. a foreign embed host. Now rewritten to the Krmzy domain. Same mechanism as F1; contradicts the design's "0 pass `false`". | `runtime.document(resolvedExtractorUrl, headers = ..., rewrite = false)` once F1's parameter exists. |
| F3 | Medium | `Shahid4u.kt:78`, `Topcinema/.../topcinemaProvider.kt:40, :421, :447`, `eseek/.../GessehProvider.kt:122` | Same default-`false` sites as F1 but on provider-own URLs (`httpGet` helpers, watch/download pages, `resolveRealUrl` output). Rewriting a same-host URL is a no-op, so these are almost certainly harmless — but they are a silent behaviour change the design said did not exist, and Gesseh's `resolveRealUrl` follows redirects to whatever host the site answers with (`GessehProvider.kt:100-118`). | Either accept (document in the design census) or pass `rewrite = false` at the five sites to stay verbatim. Reviewer's preference: accept for Shahid4u/Topcinema (own host), pass `rewrite = false` for Gesseh `:122`. |
| F4 | Low | `Anim3rb/.../anim3rbProvider.kt:327, :447, :493` | The three deleted hand-built `Cookie` headers were built from `AndroidCookieStorage.get(mainUrl)` (provider domain) and sent to `raw(url)`. The jar (`SystemCookieJar.loadForRequest`, `SystemCookieJar.kt:51-58`) loads for the **request** host. When session domain == request host, OkHttp's `BridgeInterceptor` replaces the header with the jar's cookies, so HEAD and now are identical. When they differ (the very case the comment at `:326-327` describes — session domain poisoned to `video.vid3rb.com`), HEAD still sent provider-domain cookies and now nothing is sent for that host. Edge case inside R3 territory; not a dropped branch in the gateway. | Accept; note in the Wave 6 leak table as "anim3rb raw() cookie scope now follows the request host". |
| F5 | Low | `HttpGateway.kt:920-926` (`forProvider`) vs `HEAD:962-965` | The `Fingerprint.current()` warm-up at plugin load is deleted per design section 4. Consequence: the first `WebSettings.getDefaultUserAgent(context)` (`Fingerprint.kt:156`) now runs wherever the first request happens — an OkHttp thread inside `FingerprintInterceptor`, which has no `Looper`. At HEAD every `BaseProvider` triggered the warm-up on the main thread via the `httpService` lazy (reading `mainUrl` at construction). Modern WebView providers bootstrap from a non-UI thread by posting to the main looper, but the repo has no prior evidence of this path running off-main. | Release gate (below): first request per fresh process must log `Fingerprint … Device fingerprint resolved` with no `WebViewFactory`/`Looper` exception. If it fails, the one-line fix is `Fingerprint.current()` inside `forProvider`. |
| F6 | Low | `HttpGateway.kt:66-80` | `execute`, `solveCloudflare`, `onDomainRedirect`, `currentDomain` are public (required by `RequestQueue.Host`). Design section 4 says "everything else private". Unavoidable for an interface implementation; the class is otherwise not exposed (providers see `ProviderRuntime`, `BaseProvider.gateway` is private, `BaseProvider.kt:80`). | Accept; amend the design sentence. |
| F7 | Note | `log3.txt` | Modified in the working tree; unrelated to this slice (was `M` before the slice started). | Do not include in the 4b-2 commit. |
| F8 | Note | `ProviderRuntime.document` KDoc (`ProviderRuntime.kt:17`) | Says "domain-rewriting" unconditionally. After F1 the doc should say "rewrites the host to the session domain unless `rewrite = false`". | Update with F1. |

No new UA/header literal was introduced (`grep -rn "Mozilla/5.0" --include='*.kt' . | grep -v src/test` is identical at HEAD and in the tree: Viu and the YouTube InnerTube API clients only).

## Behaviour-preservation table

| Member | HEAD region | Gateway region | Preserved? | Notes |
|---|---|---|---|---|
| `document(solveCf=true)` queue path | `HEAD:377-479` | `HttpGateway.kt:351-441` | **Partially** | `ensureInitialized()` + `buildUrl` added at the head (`:357-358`), the `rewriteDomain` branch at `HEAD:398-402` collapsed to `requestQueue.enqueue(url, headers)` (`:370`). That collapse is F1/F2/F3. Cache probe (`:360-367`), `adoptRedirect` → `checkAndUpdateDomain` (`:374-376`), HttpError body still parsed (`:379-380`), meta-refresh (`:383-386`), `needsCfSolve()` gate, breaker open check, `webViewEnabled`, `enqueueAction { solveCloudflareThenRequest(...) }`, breaker record, CF-solved cache write, clean-success cache write (`:391-440`): all verbatim. |
| `document(solveCf=false)` | `HEAD:485-528` (`getDocumentNoFallback`) | `:449-505` (`documentNoSolve`) | Yes | `executeDirectRequest(url, headers, rewriteDomain = true)` — all 9 HEAD callers passed `true` (verified by `git grep`), so the constant is correct. `isCfBlocked` (CF or 403 or body contains "403 Forbidden") verbatim; `isValidProviderDomain` guard before adoption verbatim (`:466-478`); throw `CloudflareBlockedSearchException(config.name, sessionState.domain)` verbatim (`:485`); meta-refresh verbatim; no cache probe, as at HEAD. |
| `text` | `HEAD:131-135` | `:181-186` | Yes | `rewriteDomain = false` constant; HEAD had 0 callers passing `true`. Tier-3 Chrome-TLS retry lives in `executeDirectRequest` (`:560-621`) and is reached unchanged. `ensureInitialized()` added. |
| `post` | `HEAD:267-285` (`post`, `postText`, `postDebug`) | `:268-279` | Yes | `rewrite` → `executePostRequest(..., rewriteDomain)`; returns `bodyOrNull()` (Success html or HttpError body, `FetchOutcome.kt:70-74`) exactly like `postText`. The two `post(): Document?` callers parse with the request URL as base: `EgyDead.kt:321` (`data`, absolute) and `FaselHDV2.kt:140-141` (`ajaxUrl` = `"$mainUrl/wp-admin/admin-ajax.php"`, absolute) — same base HEAD's `Jsoup.parse(it, fullUrl)` produced. ArabseedV4's two `postDebug` sites read `bodyOrNull()` (`HEAD ArabseedV4:119-125, :162-175`) → now `post()` null-check; equivalent. `rewrite = true` kept at `EgyDead.kt:321`, `FaselHDV2.kt:140`, `Wecima.kt:59`. |
| `raw` | `HEAD:225-266` | `:216-266` | Yes | Body verbatim: `useSession` jar switch, `RequestKind.Subresource`, `FingerprintInterceptor`, DNS policy, provider timeout, `peekBody(CF_PEEK_BYTES)` → `classify` → CF error log / non-OK warn. Only additions: `ensureInitialized()` (`:221`), log strings renamed. |
| `sniff` | `HEAD:287-316` | `:281-311` | Yes | HEADLESS 30 s, on `Timeout` + `isCloudflareChallenge(partialHtml)` → FULLSCREEN 120 s, `extractVideoSources`, `distinctBy { url }` verbatim. `Fingerprint.current()` → `fingerprint()` seam. |
| `imageHeaders` | `HEAD:531-542` | `:511-518` | Yes | `getImageHeadersFull` alias removed; 5 Cimawbas sites now `imageHeaders()` (`Cimawbas.kt` diff). Not suspend, so no `ensureInitialized()` — same as HEAD. |
| `ensureInitialized` | `HEAD:87-106` | `:87-106` | Yes | Verbatim incl. `SessionProvider.initialize` and the `domainManager.currentDomain != sessionState.domain → updateDomain` sync. |
| `updateDomain` | `HEAD:109-127` | `:108-127` | Yes | Verbatim, now `private`. `SessionProvider.addDomainAlias(oldDomain)` retained (`:121`). |
| `executeDirectRequest` | `HEAD:546-668` | `:522-635` | Yes (one equivalent substitution) | Default `Referer` `"https://${urlDomain ?: sessionState.domain}/"` → `defaultRefererFor(targetUrl).ifBlank { "https://${sessionState.domain}/" }` (`:534`). `defaultRefererFor` (`:941-944`) is `java.net.URL(url).host` → `"https://$host/"`, i.e. the same value; the only divergence is an empty-string host, where HEAD produced `https:///` and now the session fallback is used. Breaker keying, Tier-3 gate (`chromium.fetch`), `isCloudflareBlocked || isBlocked(...)` check, failure recording: verbatim. |
| `executePostRequest`, `executeRequestHelper`, `solveCloudflareThenRequest`, `buildUrl`, `rewriteUrlIfNeeded`, `checkAndUpdateDomain`, `handleMetaRefreshRedirect`, `extractMetaRefreshUrl`, `extractDomain`, `DomainCircuitBreaker`, `CF_PEEK_BYTES`, `shouldRetryBeforeSolve`, `CachedPage`/`recentPages`/`pageCacheTtlMs`, `dnsPolicy`/`applyDnsPolicy`/`applyProviderTimeout` | various | `:637-954` | Yes | Line diff shows only `private` modifiers, `cfBypassEngine`→`cf`, `chromiumFetcher`→`chromium`, `Fingerprint.current()`→`fingerprint()`, log-name renames. |
| Deleted per section 4 | `fetchViaChromeTls :322`, `getImageHeadersFull :541`, `postDebug :282`, `cookies`+`parseCookieHeader :81,:882`, `snifferEngine :84`, `navigationEngine :44,:975`, `videoSnifferEngine :43,:974`, `userAgent :73`, `mainUrl :70`, `Fingerprint.current()` warm-up `:963` | — | As specified | All confirmed absent from `HttpGateway.kt`. Warm-up deletion is F5. |

## Init-ordering analysis

* `HttpGateway.domain` (`:84-85`) reads `sessionState.domain` and does **not** trigger init; neither did
  HEAD's `currentDomain`/`mainUrl` (`HEAD:67-71`). `BaseProvider.mainUrl` (`BaseProvider.kt:31-32`) therefore
  returns the compiled `fallbackDomain` until the first gateway entry point runs, exactly as at HEAD.
* What changed is that 40 provider-side `ensureInitialized()` calls (19 files; `git grep` at HEAD counts
  40 outside `shared/`, 0 remain) are gone, so a provider override can now build `"$mainUrl/..."`
  **before** any init. Coverage of that case:
  * `document(...)` — both branches call `ensureInitialized()` first (`:357`), then `buildUrl`, then
    `executeDirectRequest(..., rewriteDomain = true)` → `rewriteUrlIfNeeded` (`:783`) replaces the stale
    fallback host with the synced domain. Covered. Queue grouping uses the un-rewritten host
    (`RequestQueue.kt:57`) — cosmetic only. `adoptRedirect` then hits the `finalHost == sessionState.domain`
    early return (`:814`), so no spurious adoption.
  * `text`/`raw`/`post(rewrite=false)` — no rewriting by design, so a stale-host URL would be sent as-is.
    Grep of every `runtime.text|post|raw(` site with `mainUrl` in the URL: `AnimercoProvider.kt:230`,
    `Wecima.kt:92`, `FaselHDV2.kt:140, :193`. All four are preceded in the same method by a `document(...)`
    call, so `initialized == true` by the time the URL is built. No exposed path found.
  * Paths whose first call is `text`/`raw` use the app-supplied absolute `data`/`url` (KooraLive `load`
    `:126`, `loadLinks` `:153`; SyriaLive `:236`; YallaShoot `:229`; CimaLeek `:525`; anim3rb `:327`) —
    identical to HEAD (`getText(rewriteDomain = false)`, `getRaw`), which also did not rewrite.
  * `imageHeaders()` pre-init would build the `Referer` on the fallback domain; every caller sits after a
    fetch. Same as HEAD.
* `BaseProvider`'s own five entry points still call `gateway.ensureInitialized()` before reading `mainUrl`
  (`BaseProvider.kt:113, :164, :254, :308, :430`).
* Eager `api.runtime` in `Plugin.load`: `forProvider` (`:920-926`) allocates one `HttpGateway` whose
  `DomainManager`, `CfBypassEngine`, `ChromiumFetcher` are `by lazy` (`:44-56`); `RequestQueue(this)` is a
  `Mutex` and a map. Nothing touches disk, network or WebView. `PluginContext.init(context)` precedes the
  first `api.runtime` read in all 21 plugins (verified by grep, e.g. `ArabseedV4Plugin.kt:15` before `:25`,
  `EgyDeadPlugin.kt:14` before `:22`, `CimaNowPlugin.kt:12` before `:21`).

Conclusion: no init-ordering regression. The one runtime-order change is F5 (where the fingerprint is
first built), not the domain sync.

## R1/R2 table: the 15 `httpService.userAgent` sites

| HEAD site | Now | Request path | Verdict |
|---|---|---|---|
| `CimaLeek.kt:518` | `:517` `Fingerprint.current().userAgent` local; used in `:572` headers for `runtime.text` | `FingerprintInterceptor` (same UA → not foreign → matching hints) | No-op on the wire; value kept for parity with the existing `:306`/`:416` pattern. OK |
| `CimaNowProvider.kt:1161` | `:1170` `asTvUserAgent(Fingerprint.current().userAgent)` | `NavigationEngine` WebView UA | Value needed. OK |
| `CimaNowProvider.kt:1165` | `:1174` `Fingerprint.current().userAgent` | same | OK |
| `CimaNowProvider.kt:2066` (Jetload `headers`) | deleted (`:2074-2077`) | `runtime.raw` ×3 | Interceptor supplies UA. No-op |
| `CimaNowProvider.kt:2152` | `:2161` `Fingerprint.current().userAgent` with comment | hand-built OkHttp client, no interceptor | Justified deviation. OK |
| `CimaNowProvider.kt:2447` | `:2456` `Fingerprint.current().userAgent` | `navigationEngine.renderHtmlInSandbox` | Value needed. OK |
| `CimaNowProvider.kt:2630` (`sessionHeaders`) | deleted (`:2638-2639`) | `runtime.raw` ×3 (`:2641, :2685, :2733`); `sessionHeaders` is not handed to anything else | No-op |
| `CimaNowProvider.kt:2923` (get-link `headers`) | deleted (`:2930-2932`) | `runtime.raw` ×3 | No-op |
| `CimaNowProvider.kt:2982` (`postHeaders`) | deleted (`:2988-2991`) | `runtime.post` → `executePostRequest` → interceptor | No-op |
| `CimaNowProvider.kt:3056` | `:3062` `Fingerprint.current().userAgent` local | `navigationEngine.execute` (`:3141`) and M3u8Helper (`:3200`) | Value needed. OK |
| `CimaNowProvider.kt:3194` | `:3200` `mapOf("User-Agent" to userAgent)` | `M3u8Helper.generateM3u8` (app client, no interceptor) | Justified deviation. OK |
| `KooraLive.kt:152` | `:151` local; `:169` `pHeaders` for `runtime.text` | interceptor | No-op on the wire. OK |
| `SyriaLive.kt:230` | `:229` local; `:234`/`:260` for `runtime.text` (no-op), `:290`/`:317` for `M3u8Helper` | mixed | Justified where M3u8Helper needs it. OK |
| `TukTukcima.kt:92` | deleted (`:91-93`) | `runtime.raw` | No-op. `inertiaHeaders` (`:106`) never carried a UA. OK |
| `YallaShoot.kt:229` | deleted; no remaining `userAgent` reference in the file | `runtime.text` | No-op. OK |

15/15 accounted for. Remaining `"User-Agent" to` hits in the tree are `Fingerprint.current().userAgent`
(or a local of it) or the pre-existing API clients (Algolia, Viu, YouTube/InnerTube, Watanflix,
Odnoklassniki `MINT_UA`).

## Registration audit

* **Constructors.** `ReviewRateExtractor(runtime)`, `GameHubExtractor(runtime)`, `OkPrimeExtractor(runtime)`,
  `VidobaExtractor(runtime)`, `VKVideoEmbed(runtime)`, `CimaNowTVEmbed(runtime)`,
  `ByseExtractor(host, name, runtime)`, `LarozaExtractor(mainUrl, name, runtime)`. Every
  `ProviderHttpServiceHolder.getInstance()` null branch is gone (Laroza `:33-37`, OkPrime `:23-24`,
  ReviewRate `:49-52, :181`, GameHub `:46, :120`, Vidoba `:145`, VK `:33`, Byse `:342`, CimaNowTV `:34`).
  `FaselHDExtractor.kt:177` comment updated; no runtime needed (hardcoded IPv4, correct reasoning).
* **`registerSharedExtractors(runtime)`** (`SharedExtractors.kt:20-88`): same list, same order; only the
  eight constructors gained the argument. No extractor deleted.
* **21 plugins** all still call `registerSharedExtractors(api.runtime)`; the order of
  `registerMainAPI`/`registerSharedExtractors` relative to each other is unchanged per plugin.
  `EgyDeadPlugin.kt:22, :24` and `FaselHDV2Plugin.kt:24, :26` pass `api.runtime` to their direct
  `ReviewRateExtractor`/`OkPrimeExtractor` registrations; `LarozaPlugin.kt:25` uses the shared list.
  Sniffer registrations (`EgyDeadPlugin:28`, `FaselHDV2Plugin:32`, `LarozaPlugin:29`) untouched.
* **Cross-plugin serving invariant (section 5).** `text` (`:181-186`) calls
  `executeDirectRequest(..., rewriteDomain = false)`: never rewrites. `raw` (`:216`) builds the request from
  `buildUrl(url)` only: never rewrites. Default `Referer` in `executeDirectRequest` is
  `defaultRefererFor(targetUrl)` (`:534`), i.e. the target host; `raw` sends no default `Referer` at all
  (verbatim HEAD). Holds for `text`/`raw`. **Does not hold for `document`** — see F1: the three
  `document`-based extractors are rewritten to the constructing provider's domain.

## Spec compliance

| Criterion | Status | Evidence |
|---|---|---|
| Wave 4 AC 1: `ProviderRuntime` ≤ 8 methods; thin provider compiles against it alone | Met | 7 members (`ProviderRuntime.kt:13-47`); `BaseProvider` uses only `runtime.*` plus `gateway.ensureInitialized()`. `ProviderRuntimeSurfaceTest` pins it. |
| Wave 4 AC 3: Holder gone; no extractor resolves a global | Met | `service/ProviderHttpServiceHolder.kt` deleted; `grep ProviderHttpServiceHolder` finds only `docs/`. |
| Wave 4 AC 6: MyCima unchanged as thin-provider proof | Met | `MyCima.kt` diff is one line (`postText` → `post`, `:237-238`); `MyCimaPlugin.kt` is the three-line pattern from design section 6. |
| Non-touch: `RequestQueue.allowedDomains` signatures, `DomainManager` denylist/adoption sites | Met | `git diff HEAD --stat` lists neither `DomainManager.kt` nor `RequestQueue.kt`. `allowedDomains` computation in `document` (`:411-413`) verbatim. |
| R3 untouched | Met | `updateDomain` incl. `addDomainAlias` (`:108-127`), `checkAndUpdateDomain` (`:808-828`), `rewriteUrlIfNeeded` (`:783-806`), `isValidProviderDomain` guard (`:470`), `handleMetaRefreshRedirect` (`:835-863`) all verbatim moves. `BaseProvider.handleDomainDifference` still present (`BaseProvider.kt:389`). Section 10 leak table honoured; add F4 as a residue line. |
| Design §2: exactly the 7 members, signatures as specified | Met | Matches character for character except KDoc. |
| Design §4: lazy fields, `fingerprint` seam, `ensureInitialized` public and at every entry head | Met | `:44-56`, `:41`, `:87`; called at `:185, :221, :275, :282, :357`. `imageHeaders` is non-suspend and cannot (same as HEAD). |
| Design §4: "everything else private" | Met with F6 caveat | `Host` members are public by interface necessity. |
| Design §2 migration table: `getDocument(rewriteDomain)` "72 pass true, 0 pass false; drop the parameter" | **Not met — census error** | 8 default-`false` sites + 1 explicit `false` at HEAD (F1–F3). |
| Design §5: `text`/`raw` never rewrite; Referer host-derived | Met | see Registration audit. |
| Design §6: one parameter, one list, unchanged order; `api.runtime` at load | Met | `SharedExtractors.kt:20`; 21 plugins. |
| Design §9 (4b-2) file list | Met | Adds, deletes, 8 extractors, `SharedExtractors.kt`, plugins, providers, `CimaNowSession.kt`, `CfRetryDecisionTest` move all present. Test count (112) not verified — gradle not run. |
| Overengineering | None | New symbols: `ProviderRuntime`, `HttpGateway`, `defaultRefererFor`. Nothing else. `HttpGateway` is 954 lines against the deleted 992 — still one class, by the design's own admission ("the split is behind an interface"). Acceptable for this wave: the provider-facing contract is now 7 members, the class is private to `shared`, and internal decomposition without a behaviour change was explicitly out of scope. It should be named as Wave 5/6 debt. |

CimaNow specifics: `navigationEngine` is `by lazy { NavigationEngine { ActivityProvider.currentActivity } }`
(`CimaNowProvider.kt:50`), the same `activityProvider` lambda HEAD passed via `BaseProvider.kt:96` into
`ProviderHttpService.create` → `NavigationEngine(activityProvider)` (`HEAD:975`). Lifetime moves from the
process-wide `instances` map to the single `CimaNowProvider` instance; equivalent in practice (one
provider instance per plugin load). `CimaNowNavigationPolicy(runtime)` uses only `runtime.raw`
(`CimaNowSession.kt:154`); `reestablishSession(runtime, ...)` uses only `runtime.document` (`:384`).
Beyond the mechanical migration the only CimaNow changes are the five `Fingerprint.current().userAgent`
locals and `VKVideoEmbed(runtime)`/`CimaNowTVEmbed(runtime)` at `:1769, :2232, :2250`. Nothing else.

## Test gaps, ranked

1. **`document(rewrite = false)` path has no test and the regression F1 slipped through.** Smallest pure
   seam: lift `rewriteUrlIfNeeded` to a top-level `internal fun rewriteHost(url: String, currentDomain: String): String`
   (it uses only `URI` and `String.replace`) and test: foreign host with `rewrite = false` untouched;
   same-host no-op; `www.` normalisation; port preserved. Then a `ProviderRuntime` fake in
   `RequestQueueTest` style is unnecessary — the decision is the pure function.
2. **`buildUrl`** (`:777-781`) is pure; test `"http…"` passthrough, `"/path"` and `"path"` both giving
   `https://<domain>/path`.
3. **`classify`-adjacent decisions in `document`**: `isCfBlocked` in `documentNoSolve` (`:453-455`) is a
   pure predicate on `FetchOutcome`; extract `internal fun isNoSolveBlocked(outcome): Boolean` and pin
   the three cases (CloudflareBlocked, HttpError 403, HttpError with "403 Forbidden" body).
4. **`RuntimeIsHostDerivedTest`** asserts the right pure rule (`defaultRefererFor` for three hosts incl.
   a port, and the unparseable fallback), but it does not pin "text never rewrites" — after (1) it can.
5. **`ProviderRuntimeSurfaceTest`**: the `$default` filter is sound — Kotlin's synthetic
   `<name>$default` bridges are the only extra methods a Kotlin interface with default arguments can
   carry (on either `-Xjvm-default` setting they are static and named `…$default`), and `declaredMethods`
   excludes inherited `Object` methods. It counts 7 today. It is a soft guard: a new member with default
   args still counts as one, which is the intent.
6. **Coverage lost with `ProviderHttpService`**: none — `CfRetryDecisionTest` moved intact (package line
   only, `git diff -M` similarity 95%); the class had no other direct tests. `HttpGateway`'s pipeline
   remains untested by design (Android-bound).

## Release gates

1. **F1/F2 fixed and compiled** before any device check; without it OkPrime, Laroza embeds, Vidoba and
   Krmzy's extractor page fetch are broken.
2. **CimaNow freex device check** (design owner decision): full flow mints a `/watching/` URL; the
   `sessionHeaders`/`postHeaders` chain now carries the UA via `FingerprintInterceptor` instead of a
   caller header — confirm `get-link.php` still returns a link (note `cimanow-freex-getlink-http`).
3. **Wire Referer check**: with two plugins loaded (e.g. FaselHDV2 and EgyDead, both registering
   `OkPrimeExtractor`), trigger an OkPrime link from the plugin that did **not** register last and confirm
   the request goes to `okprime.site` with `Referer` derived from the caller (`customHeaders["Referer"]`),
   not the constructing provider's domain.
4. **First-request fingerprint (F5)**: fresh process, first request on a provider whose class does not
   touch `Fingerprint` at construction (e.g. MyCima); logcat must show `Fingerprint … Device fingerprint
   resolved` and no WebView-init exception on the OkHttp thread.
5. **Init sync smoke**: a provider whose persisted domain differs from `fallbackDomain` — first
   `getMainPage` after restart must show one `rewriteUrlIfNeeded … Rewrote URL` (or none if the compiled
   fallback is current) and no request to the stale host.
6. Run `:FaselHDV2Provider:testDebugUnitTest`; expected 112 green (not verified here).

## Commit verdict

**Not ready.** Blockers:

* **F1** — `document()` always rewrites; add `rewrite: Boolean = true` to `ProviderRuntime.document`,
  restore the `enqueueAction { executeDirectRequest(url, headers, rewriteDomain = false) }` branch from
  `HEAD:398-402`, pass `rewrite = false` in `LarozaExtractor.kt:43`, `OkPrimeExtractor.kt:34`,
  `VidobaExtractor.kt:152`.
* **F2** — `KrmzyProvider.kt:480` pass `rewrite = false` (HEAD did).
* Decide **F3** (five provider-own sites; Gesseh `:122` recommended `rewrite = false`) and correct the
  census line in `docs/wave-4b-design.md` section 2 ("72 pass true, 8 rely on the default `false`, 1
  passes `false`").

Everything else (F4–F8) can land with the slice or as follow-up notes. After F1/F2, the gateway is a
faithful move of HEAD's behaviour and the slice matches the design.

## Fix-pass (post-review)

Applied in the working tree (not committed). Line numbers post-fix.

* **F1/F2/F3** — `ProviderRuntime.document` gained `rewrite: Boolean = true`
  (`ProviderRuntime.kt:23`), a parameter on the existing member, so the interface stays at 7
  members and `ProviderRuntimeSurfaceTest` still counts 7. `HttpGateway.document`
  (`HttpGateway.kt:360-388`) restores HEAD's branch: `rewrite = false` →
  `requestQueue.enqueueAction(url) { executeDirectRequest(url, headers, rewriteDomain = false) }`
  (verbatim `HEAD:398-402`), `rewrite = true` → the current `requestQueue.enqueue(url, headers)`.
* `rewrite = false` passed at the 5 sites that relied on the default at HEAD and can see a foreign
  host: `shared/.../extractors/LarozaExtractor.kt:43`, `OkPrimeExtractor.kt:34`,
  `VidobaExtractor.kt:152` (absolute embed URLs — F1), `Krmzy/.../KrmzyProvider.kt:480`
  (HEAD passed `rewriteDomain = false` explicitly — F2), `eseek/.../GessehProvider.kt:122`
  (`resolveRealUrl` follows redirects to arbitrary hosts — F3, reviewer's recommendation).
  `Shahid4u.kt:78` and `Topcinema/.../topcinemaProvider.kt:40, :421, :447` stay on the default:
  provider-own hosts, where the rewrite is a no-op (F3, accepted).
* **F8** — the `document` KDoc now states the conditional rewrite.
* **F6** — design section 4's "Everything else private" amended to except the four
  `RequestQueue.Host` overrides.
* **Test gap 1/2** — the pure parts of `rewriteUrlIfNeeded` and `buildUrl` are now top-level
  `internal fun rewriteHost(url, fromHost, toHost)` and `internal fun buildUrl(pathOrUrl, domain)`
  (`HttpGateway.kt:943-975`); the gateway's private one-liners delegate to them and the log line
  is unchanged. New `FaselHDV2Provider/src/test/kotlin/com/cloudstream/shared/core/UrlRewriteTest.kt`
  (7 tests). **`rewriteHost` keeps HEAD's semantics deliberately**: HEAD's `rewriteUrlIfNeeded` used
  `url.replace(host, currentDomain)`, a global string replace, so an old host repeated in the query
  string was rewritten too. The test
  `alsoRewritesTheOldHostInsideTheQueryString_currentBehaviour` documents that rather than fixing
  it; the host-component-only narrowing is Wave 6's
  `HostHistoryRewriteTest.rewritesHostComponentNotQueryString`. No `documentRewriteFalseBypassesRewrite`
  test: the `rewrite` branch selects between two queue calls, so there is no pure decision to pin
  without a `RequestQueue` fake.
* Design section 2's `rewriteDomain` census row and the `document` signature corrected.
* **F4, F5, F7** unchanged: F4/F5 are release-gate/Wave-6 notes, `log3.txt` stays out of the commit.
