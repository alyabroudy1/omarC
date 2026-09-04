# Wave 3 review: delete dead code and duplicates

Independent ("third eye") review of the uncommitted working tree on top of `aae6481f` (Wave 2).
Reviewed against `docs/shared-refactor-waves.md` "## Wave 3" (`:314-387`). Read-only; no gradle run
(a build was in progress). Every claim below cites a file:line that was read, or a `git`/`grep` command
that was run. Note for anyone re-checking: the 20 deletions are **staged** (`D ` in `git status`), so
plain `git diff` omits them; use `git diff HEAD` (47 files, +474 / -4,398).

## Verdict

**Ready to commit, with three conditions** (none is a code blocker):

1. Exclude `log3.txt` from the commit — it was already modified before this wave and is unrelated.
2. Commit the Replaymatch line-ending normalisation separately, or at least call it out: 281 CRLF
   lines became LF, so the file shows a 553-line diff for what is a 10-line change (`git diff -w --stat
   -- Replaymatch` = 1 insertion, 10 deletions).
3. Owner sign-off on two **behaviour changes on the pre-existing shared `EarnVidsExtractor.getUrl`
   path** (F1, F2 below) that go beyond "fold the four copies": they are defensible, but they are not
   subtractive and they touch the six hosts that were already registered before this wave.

The deletions are clean: every deleted file and member has zero remaining references, the four
extractor-registry invariants hold (no `registerExtractorAPI` line removed; one added), R1/R2/R3 hold,
and `DomainManager.kt` is not in the diff. The implementers were right and both planning docs were
wrong about `TvMouseComponents.kt` and `WebViewTypes.kt` being dead.

## Findings

| # | Severity | file:line | Issue | Minimal fix |
|---|---|---|---|---|
| F1 | Medium (behaviour change, needs sign-off) | `shared/.../extractors/EarnVidsExtractor.kt:206-214`, `:275` | The **registered** `getUrl` path now sends `referer ?: finalUrl` as `Referer` and as the playback referer. At HEAD it always sent the embed URL itself (`finalUrl`) and set `this.referer = activeReferer ?: finalUrl`. This changes the request every `loadExtractor` caller makes to the six hosts that were already registered (`SharedExtractors.kt:49-54`), not just the four collapsed providers. The copies' semantics (caller referer) are correctly preserved in `extractDirect` (`:300-306`); carrying them into `getUrl` was a choice, not a fold. | Either (a) keep and record it as a sanctioned change in the commit message plus the Wave 3 report, with the spec's manual check extended to one `loadExtractor` EarnVids site, or (b) restore `val fallback = finalUrl` in `getUrl` and leave the caller-referer contract to `extractDirect` only. |
| F2 | Medium (behaviour change, needs sign-off) | `EarnVidsExtractor.kt:89-95` | `PackerObfuscationStrategy` now substitutes `'$pageUrl'` for `location` / `location.href` / `window.location`. At HEAD shared substituted `''`. This is the copies' behaviour (deleted `Animerco/.../ExternalEarnVidsExtractor.kt:149-154` at HEAD), so it is "the union", but it also alters the decoded payload on the registered path. Almost certainly an improvement; still not subtractive. Note `'$pageUrl'` is unescaped — a `'` in the embed URL would break the substituted JS (unlikely; embed URLs are `/e/<id>`). | Sign-off as above. Optional hardening later: `pageUrl.replace("'", "%27")`. |
| F3 | Medium (order change, owed device check) | `EarnVidsExtractor.kt:172-178`, `:186-190`, `:315-319` | The copies ran the raw-`.m3u8` scan **first** on the raw HTML and returned immediately (HEAD `Animerco/.../ExternalEarnVidsExtractor.kt:35-46`), then packer only. The fold runs Hex, Packer, then Raw **last**. Result differs whenever the page contains a raw `.m3u8` and a decodable payload that yields a different URL. The "decoy" rationale in the KDoc is a hypothesis, not evidence. Also new for these callers: the Hex strategy runs at all, and the packer regex lacks `DOT_MATCHES_ALL` (copies had it, `:140-143` at HEAD) so a multi-line `eval(function(p,a,c,k,e,d){...}` body no longer matches. | Keep the order, but the spec's manual check (Animerco, Lodynet, Replaymatch, Shahid4u each resolve one server) is now load-bearing and must be recorded before merge to `main`. Consider adding `RegexOption.DOT_MATCHES_ALL` to `packedRegex` (`:72`) — it only widens matching. |
| F4 | Low (behaviour change, additive) | `SharedExtractors.kt:55-56`, `EarnVidsExtractor.kt:26-28` | `fdewsdc.sbs` is newly registered. No provider references that host (`grep -rn fdewsdc` hits only shared). Before, a `loadExtractor("https://fdewsdc.sbs/...")` matched nothing and returned false; now it runs EarnVids with a hard-coded `Referer: https://shhahid4u.cam`. Additive, low risk, but it is a new registration in a "purely subtractive" wave and hard-codes one site's referer into a shared extractor. | Accept, and say so in the commit message. |
| F5 | Low (diff hygiene) | `Replaymatch/src/main/kotlin/com/replaymatch/Replaymatch.kt` | CRLF→LF rewrite of the whole file (281 CRs at HEAD, 0 now; `core.autocrlf=input`, no `.gitattributes`). The only semantic change is `:272` calling `shared.util.fixUrl`. | Separate commit, or note `git diff -w` in the message. Consider a `.gitattributes` `* text=auto eol=lf` so it never recurs. |
| F6 | Low (unrelated file) | `log3.txt` | Modified before the wave started (in the session's opening `git status`); +23/-13 of log noise. | `git restore log3.txt` or leave it out of the commit. |
| F7 | Low (dead field left behind) | `ProviderHttpService.kt:58` | `val mediaValidator = MediaUrlValidator()` now has zero users: its two callers (`validateMediaUrls`, `isMediaAccessible`) were deleted and `grep -rn "\.mediaValidator"` hits nothing. | Delete the field (one line) or carry to Wave 4. |
| F8 | Low (stale comments) | `shared/.../extractors/SnifferExtractor.kt:24`, `shared/.../session/SessionProvider.kt:8` | Both still describe `LazyExtractor`, which is deleted. | Reword the two comments. |
| F9 | Low (two lists to keep in step) | `EarnVidsExtractor.kt:19-28` vs `SharedExtractors.kt:49-56` | `EARNVIDS_HOSTS` and the `registerExtractorAPI` block are hand-mirrored; `EarnVidsUnificationTest.theRegisteredHostListIsTheUnion` (`:41-45`) checks `EARNVIDS_HOSTS` against a third list in the test, not against what is registered. Adding a host to one and not the other is silent. | Acceptable for now; if it drifts, register from a `List<Pair<host, displayName>>`. |
| F10 | Low (duplication inside the fold) | `EarnVidsExtractor.kt:300-330` | `extractDirect` rebuilds the header map and the strategy list that `getUrl` already has (`:186-190`, `:216-`). Also applies `.replace("\\/", "/")` to every strategy's output (`:325`) whereas `getUrl` only does it inside `RawM3u8Strategy` (`:177`). Harmless (the copies unescaped everything too), but it is a second copy of the pipeline inside the file that just removed four copies. | Hoist the strategy list to a private val; optionally unescape in both paths the same way. |
| F11 | Info (pre-existing, not this wave) | `NavigationEngine.kt:4232` vs `VideoSnifferEngine.kt:730-736` | `SPOOFING_JS` reports `navigator.plugins` as the real Array `[1,2,3,4,5]`; the sniffer's comment documents CimaNow's `Array.isArray(navigator.plugins) && navigator.plugins[0] === 1` bot signal — an exact match. Mitigated today: CimaNow passes `injectSpoofingJs = false` (`CimaNowProvider.kt:1367`) and the param doc (`NavigationEngine.kt:229-237`) already names the tell. Every other NavigationEngine caller still gets the crude spoof by default. Severity for this wave: none (unchanged). Severity as a standing issue: Medium — any other site adopting the same probe will catch it. | Wave 4 (`WebViewSession` base): replace with the sniffer's array-like `Object.create(null)` fake (`VideoSnifferEngine.kt:738-750`) so all engines share one spoof. |

Nothing rated High. Item C of the brief (Topcinema regression if `finalUrl` were CF-only) is **refuted**, see "Wave 2 carry-over" below.

## Deletion audit

Method: `git diff HEAD --name-only --diff-filter=D` (20 files, 3,625 LOC by `wc -l`; the spec's
~3,387 counted two files that were kept), then `grep -rn --include="*.kt" "\b<Symbol>\b"` excluding
`/build/`, `src/test` and `docs/`, and `git grep ... HEAD` to confirm each was referenced only from
inside the deleted set at HEAD.

| File / member | Verified dead? | Evidence | Status |
|---|---|---|---|
| `webview/WebViewFlowHelper.kt` (685) | Yes | 0 refs now; at HEAD referenced only from deleted files | Deleted |
| `extractors/LazyExtractor.kt` (542) | Yes | `abstract class LazyExtractor : ExtractorApi()` (HEAD `:20`); `git grep ": LazyExtractor\|LazyExtractor()" HEAD` empty → no subclass, never registered. Two stale comment mentions remain (F8) | Deleted |
| `parsing/GenericParser.kt` + `ParserSpec.kt` | Yes | 0 refs | Deleted |
| `session/ProviderStateStore.kt` | Yes | 0 refs | Deleted |
| `strategy/DirectHttpStrategy.kt` | Yes | 0 refs | Deleted |
| `extractors/JWPlayerExtractor.kt` | Yes | 0 refs; not an `ExtractorApi` (no `ExtractorApi` token in the HEAD blob); never registered | Deleted |
| `parsing/BaseParser.kt` | Yes | 0 refs (`NewBaseParser` is a different class and is live) | Deleted |
| `extractors/EvalDeobfuscator.kt`, `PackerUnpacker.kt`, `LinkResolvers.kt`, `RefererRotator.kt` | Yes | 0 refs each | Deleted |
| `com/lagradost/cloudstream3/utils/LazyExtractorLink.kt` | Yes | 0 refs | Deleted |
| `strategy/StrategyTypes.kt` → `RequestStrategy`, `RequestContext`, `StrategyRequest`, `StrategyResponse` | Yes | 0 refs each; `VideoSource` kept and used (`ProviderHttpService.kt` still imports/uses it) | Trimmed |
| `ui/TvMouseComponents.kt` | **No — live** | `CfBypassEngine.kt:28,392`, `NavigationEngine.kt:38,3829` construct `TvMouseController` | Correctly kept; both docs wrong |
| `webview/WebViewTypes.kt` | **No — live** | Every top-level type has external refs: `Mode` 45, `ExitCondition` 41, `WebViewResult` 52, `NavigationStep` 28, `NavigationResult` 8, `CapturedEmbedRequest` 7, `CapturedVideoRequest` 5, `CapturedLinkData` 4 | Correctly kept; both docs wrong |
| `ProviderHttpService.getMainPage/search/getPlayerUrls` + `parser` ctor param | Yes | `grep "httpService\.\(getMainPage\|search\)("` empty; `getPlayerUrls` 0 refs; `BaseProvider.kt:97` no longer passes `parser`; `ParserInterface` itself is still live (`MyCimaClone.kt:6`, `DimaToonParser.kt:8`) and untouched | Deleted |
| `sniffVideosVisible`, `navigateWithSteps`, `validateMediaUrls`, `isMediaAccessible` | Yes | 0 refs each | Deleted (leaves F7) |
| `ProviderHttpService.kt` `if (false /* disabled */)` block | Yes | Unreachable by construction | Deleted |
| `CloudflareDetector.isSuccessfulLoad` | Yes | 0 refs | Deleted |
| `ProviderLogger.logSessionState/logRequestStart/logRequestComplete` | Yes | 0 refs | Deleted |
| `ProviderConfig.validateWithContent/cookieMaxAgeMs/videoSniffTimeoutMs` | Yes | 0 refs | Deleted |
| `ByseExtractor.tryWebViewExtraction` | Yes | Was a stub that only logged "not implemented" (HEAD `:448-456`). Failure path still logs: `ProviderLogger.w(EXTRACTOR_TAG, methodName, "API extraction failed")` at `:328` | Deleted |
| `storeCdnCookies`, `SessionSnapshot` | Already gone | 0 refs at HEAD and now (removed in Wave 2) | N/A — spec row stale |
| `SessionState.withDomain` | **No — live** | `ProviderHttpService.kt:120` | Correctly kept; spec row wrong |
| `SnifferSelector.waitAfterClick` | **No — live** | `CimaLeekProvider/.../CimaLeek.kt:752` passes `waitAfterClick = 3500L`; serialised at `SnifferSelector.kt:28,44` | Correctly kept; spec row wrong |
| `YoutubeProvider/.../CloudflareDetector.kt` | Yes | `git grep CloudflareDetector HEAD -- YoutubeProvider` hits only the file itself | Deleted |
| `Witanime/.../VideaExtractor.kt`, `MailruExtractor.kt` | Shadows, not dead | See registry row below | Deleted; callers rewired to shared |
| 4 × `ExternalEarnVidsExtractor.kt` | Not dead — callers rewired | HEAD callers `AnimercoProvider.kt:295,316`, `lody.kt:302`, `Shahid4u.kt:303`; Replaymatch never called its copy | Deleted; see Collapse audit |
| `CimaNowSession.withSessionGuard` | Yes (after inlining) | Body was `return block()` (HEAD `:406-420`); two call sites became `run { }` (`CimaNowProvider.kt:2578`, `:2887`) with no `return@withSessionGuard` labels left (grep empty); `reestablishSession` unwrapped (`CimaNowSession.kt:379-399`) | Deleted |
| `CimaNowNavigationPolicy.sessionHosts` ctor param | Yes | Only occurrence was the declaration; sole caller `CimaNowProvider.kt:1438` never passed it | Deleted |
| `ChromiumFetcher` anti-bot JS block | Yes (no-op) | It lived in `getOrCreateWebView` (HEAD `:265`, block at `:301`), i.e. `evaluateJavascript` on a fresh WebView **before** any `loadUrl` (`fetch` at `:216`) — runs in `about:blank` and is discarded on navigation. Also deleted with it: the equally dead `navigator.webdriver` spoof | Deleted |
| `ChromiumFetcher` `Cookie` re-injection (Wave 2 F7) | Yes | Callers `ProviderHttpService.kt:330` (`fetchViaChromeTls`: Referer + caller headers) and `:641` (`executeDirectRequest`: Referer + caller headers). The only providers building a `"Cookie" to` header (`CimaNowProvider.kt:2087,2115`, `TukTukcima.kt:117`) send it through `getRaw`, which never reaches ChromiumFetcher. The jar is the WebView's own store anyway | Deleted |

### Extractor-registry invariant (coordinator's added row)

Rule: an `ExtractorApi` subclass with zero direct callers is **not** dead because `loadExtractor`
resolves by registry. Checked explicitly:

- **Which deleted files are `ExtractorApi`s.** Of the 20 deleted files, exactly three contain the
  token: `Witanime/.../MailruExtractor.kt:8`, `Witanime/.../VideaExtractor.kt:23`, and
  `shared/.../LazyExtractor.kt:20` (abstract, no subclasses, no registration — see table). The four
  `ExternalEarnVidsExtractor` files were plain `object`s (HEAD `Animerco/...:11`), never registered
  (`git grep ExternalEarnVidsExtractor HEAD` hits only their three direct callers).
- **Registration lists before/after.** `git diff HEAD -- '*Plugin.kt' shared/.../SharedExtractors.kt |
  grep registerExtractorAPI` shows **no removed line** and one added:
  `registerExtractorAPI(EarnVidsExtractor("fdewsdc.sbs", "Fdewsdc"))` (`SharedExtractors.kt:56`).
  `WitanimePlugin.kt:15-16` still registers `VideaExtractor()` and `MailruExtractor()`, now the shared
  classes (imports at `:5-6`).
- **Are the shared replacements equivalent?** `diff -w` of the HEAD Witanime shadows against shared,
  ignoring package/import lines: Videa differs only in the `TAG` string and brace formatting (same
  `name`, `mainUrl`, `requiresReferer`, same algorithm); Mailru differs only in `name` ("MailRue" →
  "MailRu") and formatting. `requiresReferer = false` on both sides.
- **Does the "MailRu" name collision change which implementation the registry picks?** No.
  `loadExtractor` matches on **`mainUrl` prefix**, iterating `extractorApis` in reverse so the most
  recently registered wins (`library/.../utils/ExtractorApi.kt:857-861`); the name is not consulted.
  Both the deleted shadow and shared use `mainUrl = "https://my.mail.ru"`, identical to the built-in
  `MailRu` (`library/.../extractors/MailRuExtractor.kt:12`, in the built-in list at `ExtractorApi.kt:1004`),
  so Witanime's plugin registration was already shadowing the built-in by position and still does.
  Witanime also calls `MailruExtractor().getUrl(...)` directly (`WitanimeProvider.kt:583-584`), which
  bypasses the registry entirely. The name does matter in one place: `getExtractorApiFromName`
  (`ExtractorApi.kt:1244-1249`) iterates **forward** and returns the first match, so a by-name lookup
  for "MailRu" now yields the built-in instead of ours. Its only caller is `requireReferer(name)`
  (`:1252-1254`); both implementations have `requiresReferer = false`, so the outcome is identical.
  User-visible effect: link labels change from "MailRue" to "MailRu".

**Confirmed:** no registered extractor was removed and no `registerExtractorAPI` registration was lost.

## Collapse audit

| Duplicate | Surviving impl | Behaviour preserved? | Evidence |
|---|---|---|---|
| `parseCookieString` ×3 (`NavigationEngine`, `VideoSnifferEngine`, `CfBypassEngine`) + `ChromiumFetcher.extractCookies` inline | `WebViewShared.kt:16-22` (top-level, `internal`) | **Yes, exactly.** Same `split(";")` / `split("=", limit=2)` / trim / `filter { key.isNotBlank() }`. Only addition: `null`/blank input → `emptyMap()`, which every caller previously guarded with `?: return emptyMap()` (e.g. HEAD `ChromiumFetcher.kt:361`). Empty values were **kept** by all four originals — the spec row saying "drops the empty value" was wrong, see Doc corrections | Removed bodies in `git diff HEAD` for the three engines and `ChromiumFetcher.kt:316-320` are byte-for-byte the same algorithm |
| DisableDevtool shim ×4 | `DISABLE_DEVTOOL_BYPASS_JS` (`WebViewShared.kt:37-61`) | **Yes.** Three copies were verbatim; NavigationEngine's was a minified equivalent (`od`/`o` names). Interpolated as `$DISABLE_DEVTOOL_BYPASS_JS` inside existing `"""..."""` raw strings that end in `.trimIndent()` (`CfBypassEngine.kt:148`, `VideoSnifferEngine.kt:770`, `NavigationEngine.kt:4231`). The constant contains no `$`, no backticks; `trimIndent` runs on the composed string so the mixed indentation is cosmetic only. ChromiumFetcher's fourth copy was a no-op (see Deletion audit) and was dropped rather than replaced — correct | Diff hunks read |
| Deleted-video phrase list ×2 (`VideoSnifferEngine`) | `DELETED_VIDEO_PHRASES_JS` (`WebViewShared.kt:70-83`, 25 phrases) | **Yes.** Site 1 (`:874`) used single-quoted JS strings, now double — equivalent JS; `"we're sorry…"` is safe in double quotes. Emitted as `[$DELETED_VIDEO_PHRASES_JS]` → trailing comma before `]` is legal ES5 and adds no element. Site 2 (`:1627`) keeps its 26 extra phrases after the shared base; the same 25 were removed from each site | Counted 25 in both removed hunks and in the constant |
| 4 × `ExternalEarnVidsExtractor` | `EarnVidsExtractor.extractDirect(pageUrl, referer)` (`:300-333`) + strategies | **Union with three deliberate deltas (F1-F3).** The "byte-identical" claim is slightly off: raw md5s differ (CRLF and package lines); after stripping CR and `package`, Lodynet/Replaymatch/Shahid4u are identical and Animerco differs by one unused `import AppUtils`. Semantically one copy. Preserved: referer = caller's, `fdewsdc.sbs` → `https://shhahid4u.cam` (`:301-305` vs HEAD `:25-30`); `location` → pageUrl substitution; `var links`/`hls4`/`hls` parsing (shared Target 2/3, `:128-158`); 4-pass unpack. Changed: strategy order (raw m3u8 last, F3), Hex now runs, `sources:[{file:}]` target added, packer regex without `DOT_MATCHES_ALL` (F3), symtab empty-entry guard (`:105`: shared keeps the token when the symtab slot is empty; the copies substituted "" — shared's is the standard unpacker semantics, so this is a fix) | HEAD `Animerco/.../ExternalEarnVidsExtractor.kt` read in full; shared `:66-162`, `:186-333` read |
| Witanime `Videa`/`Mailru` shadows | `shared/.../VideaExtractor.kt`, `MailruExtractor.kt` | **Yes** (formatting + name only; see registry row). "MailRue" typo not propagated, as reported | `diff -w` above |
| `YoutubeProvider/CloudflareDetector` | (none needed) | N/A — zero refs at HEAD | `git grep` |
| `fixUrl` ×5 → 3 collapsed, 2 kept | `shared/util/UrlUtils.kt:21-29` | **Replaymatch: identical** (HEAD `:272-281` had the same five branches incl. `trimEnd('/')`). **Tuniflix: differs on one edge** — a bare relative `x` was returned unchanged, now `mainUrl/x`; blank → blank both ways (`tuniflix.kt:178` callers are iframe `src` attrs, `:151-169`). **TukTukcima: differs on edges** — old `"$mainUrl/$url".replace("//","/").replace("https:/","https://")` collapsed internal `//` and mangled protocol-relative URLs into `https://<mainUrl host>/cdn/...`; new resolves `//cdn/x` correctly; the single caller is a season `href` (`TukTukcima.kt:63`). Both deltas are in the direction of correctness, as the implementer reported. **ArabSeedV4Parser** (`:127-130`) and **KooraLive** (`:34-37`) kept with KDoc explaining why — correct: they have different semantics (bare hostname → add scheme; rewrite own rotating hosts) | HEAD bodies read via `git diff HEAD` |
| CimaNow `withSessionGuard` | inlined `run { }` | Yes — the guard had been a `return block()` seam since Wave 2 | `CimaNowSession.kt` hunk |

## Wave 2 carry-over (brief item C): `getDocument` parses with `result.finalUrl ?: url`

**Refuted as a regression.** `result.finalUrl` is populated on the direct path, not only on the CF
path: `executeRequestHelper` sets `finalUrl = response.request.url.toString()` (`ProviderHttpService.kt:725`)
and returns it via `RequestResult.success(html, code, finalUrl)` (`:733`); `RequestResult.finalUrl` is a
first-class field (`RequestQueue.kt:303-317`). OkHttp's `response.request` is the request after redirects,
so this equals the old `app.get(...).url`. `RequestQueue.executeAsLeader` passes the result through
unchanged (`:74-90`). Hence `Jsoup.parse(it, result.finalUrl ?: url)` (`:412`) gives `doc.location()` the
post-redirect URL, and Topcinema's `finalWatchUrl = watchDoc.location().ifBlank { rawUrl }` (`:425`) and
`getBaseUrl(finalWatchUrl)` (`:426`) see what they saw before. The CF-solved path already did this
(`:467`), and the meta-refresh path used `result.finalUrl ?: url` at `:416` before this wave, so the change
also makes `absUrl()`/`abs:href` resolution correct after a redirect for every `getDocument` caller — a
global but strictly-correcting change. Cache entries already stored `finalUrl` (`:466`, `:477`).

Topcinema specifics (`topcinemaProvider.kt:421-424`, `:447-450`): `getDynamicHeaders` minus `User-Agent`
keeps `Accept-Language` and `Referer` (`:286-292`), the fingerprint interceptor supplies identity, and the
requests now go through the jar and the CF fallback that Wave 2's F2 said they lacked. `?: continue` on a
null document replaces the old thrown-exception path inside the same `try`. `allowCached` is not passed,
so no stale-page risk. This closes Wave 2 F2 as the Wave 2 review proposed (`wave-2-review.md:14`, `:134`).

## Spec compliance checklist

| # | Criterion (`shared-refactor-waves.md`) | Status | Evidence |
|---|---|---|---|
| 1 | All 41 modules compile with no source change other than deletions and import fixes | **Unverifiable here / met with sanctioned exceptions** | No gradle run (build in progress). Non-deletion changes present: the sanctioned collapses (`WebViewShared.kt`, `UrlUtils.kt`, `EarnVidsExtractor.kt` additions), the Wave 2 carry-over (`ProviderHttpService.kt:412`, Topcinema `:418-456`), and F1/F2/F4 which need sign-off. Coordinator to confirm the build. |
| 2 | `:FaselHDV2Provider:testDebugUnitTest` still passes (18 + prior waves) | **Unverifiable here** | Three new test classes added in the established location (`FaselHDV2Provider/src/test/kotlin/com/cloudstream/shared/...`), 10 test methods; all exercise `internal`/public top-level functions with no Android class-init (`WebViewShared.kt:3-7` KDoc; `EarnVidsExtractor.kt` helpers are top-level). Coordinator to run. |
| 3 | Rebuilt thin plugin `classes.dex` measurably smaller vs 1,523,388 B baseline | **Coordinator to fill** | 3,625 LOC deleted from shared (`wc -l` over `git diff HEAD --diff-filter=D`). |
| 4 | `registerSharedExtractors` no longer registers the 4 external EarnVids duplicates | **N/A — criterion was malformed; intent met** | The four copies were `object`s never registered anywhere (`git grep ExternalEarnVidsExtractor HEAD` → only their callers). The intent — no per-provider EarnVids copies remain — is met: all four files deleted, callers rewired (`AnimercoProvider.kt:296,317`, `lody.kt:302`, `Shahid4u.kt:304`). |
| — | Purely subtractive, compile-guided | **Met, except F1/F2/F4** | See Findings. |
| — | R1/R2: no new UA/header/brand literal | **Met** | `grep -rn "Mozilla/5.0\|sec-ch-ua" --include="*.kt" . \| grep -v /build/ \| grep -v src/test`: UA literals only in `YoutubeProvider/.../InnerTubeClient.kt:225,232,239`, `InnerTubeConfig.kt:33`, `Viu/.../viu.kt:43` (all pre-existing API-client UAs, untouched by the diff); `sec-ch-ua` only in `Fingerprint.kt:57`, `FingerprintInterceptor.kt:67-69`, `NavigationEngine.kt:1349-1351,1965-1967`, `ChromiumFetcher.kt:57-59` (drop-list), `RequestedWithHeaderControl.kt`, and comments. None of these lines are in `git diff HEAD`. |
| — | R3: `DomainManager` and alias code untouched | **Met** | `git diff HEAD --name-only \| grep -i "domain\|alias"` → nothing. |
| — | R3: the 5 domain-string-parsing copies not collapsed | **Met** | Still present: `ProviderHttpService.extractDomain` (`:895`), `RequestQueue.extractDomain` (`:291`), `SessionProvider.extractBaseDomain` (`:42`), `DomainManager` prefix stripping (`:71-72`, `:94-95`). `CookieLifecycleManager.normalizeKey` left in Wave 2 as recorded. |
| — | Extractor-registry invariant (coordinator's rule) | **Met** | See "Extractor-registry invariant" above. |
| — | Manual verification: Animerco, Lodynet, Replaymatch, Shahid4u each resolve one server | **Owed (device)** | Load-bearing because of F3. Replaymatch never called its copy, so it is a control, not a test of the fold. |

## Doc corrections needed

| Doc | Location | Says | Should say |
|---|---|---|---|
| `shared-refactor-waves.md` | `:323` | `webview/WebViewTypes.kt (minus what NavigationEngine needs)` — 348 LOC to delete | Live: every type has 4-52 external references (see Deletion audit). Remove the row; total drops by 348. |
| `shared-refactor-waves.md` | `:324` | `ui/TvMouseComponents.kt` — 342 LOC to delete | Live: `CfBypassEngine.kt:392`, `NavigationEngine.kt:3829`. Remove the row; total drops by 342. |
| `shared-architecture-review.md` | `:312-313` (section 7a "zero inbound references") | Same two rows | Same correction; the "types only used by WebViewFlowHelper and NavigationEngine" note is wrong — `Mode`/`ExitCondition`/`WebViewResult` are used by `ProviderHttpService`, `VideoSnifferEngine`, `CfBypassEngine` and many providers. |
| `shared-refactor-waves.md` | `:343-347` dead-member list | `storeCdnCookies`, `SessionSnapshot` | Already deleted in Wave 2; drop. |
| `shared-refactor-waves.md` | `:344` | `SessionState.withDomain` | Live at `ProviderHttpService.kt:120`; drop. |
| `shared-refactor-waves.md` | `:347` | `SnifferSelector.waitAfterClick` | Live: set by `CimaLeek.kt:752`, serialised at `SnifferSelector.kt:28,44`; drop. |
| `shared-refactor-waves.md` | `:373` `CookieStringParserTest.parsesNameValuePairs` | `"a=1; b=2; c="` … "drops the empty value, matching the surviving implementation" | All three original copies (and ChromiumFetcher's) filtered on **blank name only** (`.filter { it.key.isNotBlank() }`), so `c` → `""` is kept. The surviving implementation and the test (`CookieStringParserTest.kt:18-21`) are right; the row is wrong. Reword to "keeps `c` with an empty value; only a blank name is dropped". |
| `shared-refactor-waves.md` | `:357-359` (Risk) | "four different md5s and have drifted" | Raw md5s differ only by CRLF/package (and one unused import in Animerco); semantically one copy. |
| `shared-refactor-waves.md` | acceptance criterion 4 | "`registerSharedExtractors` no longer registers the 4 external EarnVids duplicates" | They were never registered; reword to "no per-provider `ExternalEarnVidsExtractor` remains; callers use shared `EarnVidsExtractor.extractDirect`". |
| `shared-refactor-waves.md` | `:325-336` total | ~3,387 | Actual deleted: 3,625 LOC across 20 files (includes the four EarnVids copies, two Witanime shadows and the Youtube detector, which the table did not count, and excludes the two live files it did). |

## Test gaps, ranked

1. **EarnVids strategy order and fold semantics (F3) — highest.** Nothing asserts that a page with a raw
   `.m3u8` *and* a packed payload resolves to the packed result, that a `var links = {"hls4": ...}`
   payload still decodes, or that a packed payload spanning lines matches. `PackerObfuscationStrategy`
   and `RawM3u8Strategy` are pure (`decode(String, String)`), so a fixture test with a canned packed
   payload needs no Android. This is the one collapse whose behaviour changed and is untested.
2. **`extractDirect` referer contract.** The test asserts host matching and URL normalisation, not that
   the `fdewsdc.sbs` referer hijack and caller-referer pass-through survive. Needs `app.get` seam, so
   defer unless a MockWebServer pattern exists (it does for `SystemCookieJarWireTest`).
3. **`fixUrl` edge cases that changed for Tuniflix/TukTukcima.** `FixUrlTest` covers the shared contract
   (bare relative → `base/x`, trailing slashes) but there is no test naming the old TukTukcima
   double-slash collapse as intentionally dropped. One assertion `fixUrl("a//b", base) == "base/a//b"`
   would document the decision.
4. **`DELETED_VIDEO_PHRASES_JS` / `DISABLE_DEVTOOL_BYPASS_JS` are well-formed JS.** Cheap guard: assert
   the phrase constant has 25 quoted entries and no unbalanced quote; assert the shim has no `$`. Low
   value, low cost.
5. `parseCookieString` — adequately covered (4 tests incl. blank-name and value-with-`=`).

## Carry into Wave 4

- F11: unify the `navigator.plugins` spoof across engines using the sniffer's array-like fake; retire
  `SPOOFING_JS`'s `[1,2,3,4,5]` (`NavigationEngine.kt:4232`).
- F7: delete `ProviderHttpService.mediaValidator` (`:58`) if `WebViewSession` work touches the class.
- F9/F10: register EarnVids hosts from `EARNVIDS_HOSTS`; hoist the strategy list so `getUrl` and
  `extractDirect` share one pipeline.
- Consider `RegexOption.DOT_MATCHES_ALL` on `packedRegex` (`EarnVidsExtractor.kt:72`).
- `.gitattributes` with `eol=lf` to stop CRLF churn (F5).
- Update the two planning docs per "Doc corrections".

## Commit verdict

**Ready**, subject to:

- **Blockers (housekeeping):** drop `log3.txt` from the commit; separate or annotate the Replaymatch
  CRLF→LF rewrite.
- **Blockers (process, not code):** coordinator confirms criteria 1-3 (build, tests, dex size) once the
  running build finishes; owner acknowledges F1/F2/F4 as intentional behaviour changes in the commit
  message (or reverts F1's `getUrl` referer to `finalUrl`).
- **Owed before merge to `main` (device):** the spec's manual check on Animerco, Lodynet, Shahid4u
  (Replaymatch is a control) — now load-bearing because of F3.

No High findings. The deletions themselves are safe to commit as-is.
