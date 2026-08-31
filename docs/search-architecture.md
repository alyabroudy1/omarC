# Architecture Fix Plan — search-tasks.md

Synthesized 2026-08-29 from 4 code-exploration reports (lazy search, pagination, ChromiumFetcher 403, football providers).

---

## Root-cause map (evidence)

### A. Poisoned provider domain = the common root of tasks 1b and 3
- Cimawbas's persisted domain is literally `cloudflare.com` (log3.txt line 9: `urlDomain=cloudflare.com, sessionDomain=cloudflare.com`). Every request to it 403s forever.
- Poisoning vector: `ProviderHttpService.getDocumentNoFallback()` (`shared/.../service/ProviderHttpService.kt:748-757`) calls `checkAndUpdateDomain(url, result.finalUrl)` **unconditionally, including on CF-blocked/403 responses**. When the CF challenge redirects to a `*.cloudflare.com` host, that host gets persisted by `DomainManager.updateDomain()` (`shared/.../domain/DomainManager.kt:83-94`, persisted line 91 — no allow/deny list).
- The parallel path `solveCloudflareThenRequest` (`ProviderHttpService.kt:1086-1091`) already has an explicit comment + guard against exactly this. `getDocumentNoFallback` lacks the guard.
- `DomainManager.syncToRemote()` (`DomainManager.kt:96-121`) can push the poisoned domain to the remote config worker → re-poisons after local fix, spreads to other installs.

### B. Lazy placeholder construction (task 1)
- `BaseProvider.search()` (`shared/.../provider/BaseProvider.kt:186-218`): catches `CloudflareBlockedSearchException` from `searchLazy()` and returns one placeholder card (lines 202-210): name `"🔍 $name"`, url `"lazy://$name"` (`LAZY_SEARCH_PREFIX` in `LazySearchConfig.kt:14`), fixed GitHub poster, `TvType.Movie`.
- The card the user saw (`https://cloudflare.com/lazy://سيما وبس`) = poisoned domain (A) prepended to `lazy://name` — almost certainly by the app/MainAPI `fixUrl` logic prepending `mainUrl` because `lazy://` doesn't start with `http`. **Verify during implementation** whether `newMovieSearchResponse` fixUrl-s the url; if yes, the app-side resolver must parse by `lazy://` substring (or the card must be built without fixUrl).
- Click consumer is **app-side**: `SearchViewModel.resolveLazySearch` (compiled app jar; probed via reflection in `LazySearchConfig.kt:21-39`). After solving CF, the app re-calls `provider.search("LAZY_BYPASS:<query>")` → `BaseProvider.kt:187-188` strips the prefix and falls through to `searchNormal()`.
- CF-block detection: `getDocumentNoFallback` throws `CloudflareBlockedSearchException` on `isCloudflareBlocked || responseCode == 403 || html contains "403 Forbidden"` (`ProviderHttpService.kt:759-763`).
- **No existing delay/priority/ordering mechanism** for search results anywhere in `shared/`. Row ordering across providers lives app-side (arrival order in `SearchViewModel`).

### C. Pagination (task 2)
- App side is fine: `APIRepository.search(query, page)` → `MainAPI.search(query, page): SearchResponseList`; `SearchViewModel` tracks `ExpandableSearchList(currentPage, hasNext)`.
- **`BaseProvider` only overrides the legacy pageless `search(query): List<SearchResponse>`** (`BaseProvider.kt:186`). MainAPI's default paged `search(query, page)` discards `page`, calls the pageless overload, and returns `hasNext = false`. So the app always re-fetches page 1 and never offers more.
- Below that, nothing is page-aware: `searchNormal()`/`searchLazy()` take no page; `ParserInterface.getSearchUrl(domain, query)` (`shared/.../parsing/ParserInterface.kt:54`, default `"$domain/?s=$query"`) has no page. Contrast: `getMainPage()` already threads `page` via `paginationFormat` (`BaseProvider.kt:103-154`) — reuse that pattern.
- `Viu` and `Replaymatch` have private `searchWithPage(query, page)` helpers currently hardcoded to page 1.

### D. Background ChromiumFetcher 403 spam (task 3)
- Trigger: poisoned domain (A) → every fetch 403s → OkHttp Tier-3 fallback → `ChromiumFetcher.fetch()` → 403 → full CF-solve session (30s headless / 120s fullscreen, `ProviderHttpService.kt:1079`) via `RequestQueue.executeAsLeader` (`shared/.../queue/RequestQueue.kt:92-155`).
- Lifecycle holes (independent of the poisoning, worth fixing anyway):
  1. `ChromiumFetcher.fetch()` timeout guard uses a **detached** `CoroutineScope(Dispatchers.Main).launch { delay(...) }` (`ChromiumFetcher.kt:77-84`) — not cancelled when the caller is cancelled.
  2. `RequestQueue` leader work is not tied to any UI lifecycle; an in-flight CF-solve keeps hammering the URL after the user leaves the screen (log 12:31:07→:48 bursts after UI closed).
  3. Nothing calls `ChromiumFetcher.release()` automatically; cached WebView lingers.
  4. No circuit breaker: the same permanently-failing domain is retried indefinitely across flows.
- No WorkManager/JobScheduler anywhere — it's leftover coroutines, not a scheduled job.

### E. Football providers (task 4)
- `SyriaLiveProvider/.../SyriaLive.kt` (class `SyriaLive`, supportedTypes Live+Movie, no search overrides).
- `KooraLiveProvider/.../KooraLive.kt` (class `KooraLive`, Live only, overrides searchNormal:118 / searchLazy:130).
- `YallaShootProvider/.../YallaShoot.kt` (class `YallaShoot`, Live+Movie, overrides searchNormal:170 / searchLazy:182).
- KooraLive is already Live-only yet still searched ⇒ the app does **not** reliably filter search participation by `supportedTypes`. Need an explicit repo-side gate.

---

## The plan

### Phase 0 — Domain integrity (fixes 1b + kills the 403 trigger) — shared lib only
1. **Denylist in `DomainManager.updateDomain()`** (`DomainManager.kt:83`): reject `cloudflare.com`, `www.cloudflare.com`, `challenges.cloudflare.com` (+ any `*.cloudflare.com`, and obvious junk like non-http hosts / provider display names). Single choke point = defense in depth.
2. **Guard `getDocumentNoFallback`** (`ProviderHttpService.kt:748-757`): skip `checkAndUpdateDomain` when `result.isCloudflareBlocked || responseCode == 403` or the resolved host is denylisted — mirror the existing guard in `solveCloudflareThenRequest`.
3. **Self-heal migration**: in `DomainManager.ensureInitialized()`, if the persisted domain is denylisted → clear the pref, fall back to bundled/GitHub-config domain. Fixes already-poisoned installs (Cimawbas) without manual pref clearing.
4. **Guard `syncToRemote()`** (`DomainManager.kt:96-121`): never push a denylisted domain; ideally also validate domains *received* from remote config.

### Phase 1 — WebView/queue lifecycle hygiene (task 3 hardening) — shared lib only
1. `ChromiumFetcher.fetch()`: replace the detached timeout scope with `withTimeoutOrNull(timeout)` around the suspend body (or a scope tied to the fetch coroutine) so caller cancellation propagates (`ChromiumFetcher.kt:61-198`).
2. **Per-domain circuit breaker** in `ProviderHttpService` (or `RequestQueue`): after N (e.g. 3) consecutive hard-403/CF-solve failures for a domain within a window, open the circuit for a cooldown (e.g. 10 min) — fail fast instead of re-launching ChromiumFetcher + CF-solve chains. Log once at open, not per attempt.
3. Make CF-solve leader work cancellable/capped: single in-flight solve per domain (dedupe followers already exists via leader/follower — add "don't re-solve a domain whose solve just failed" using the breaker).
4. Optional: idle `release()` of the cached WebView after the 30s reuse window when no fetches are pending.

### Phase 2 — Lazy-search result ordering (task 1a)
Goal: real results render first, placeholders last — without artificial global slowdowns.

**Recommended: app-side partitioning (deterministic, zero added latency).**
In `SearchViewModel` (app repo), partition incoming per-provider rows into two sections as they stream in: real result rows keep arrival order at the top; any row whose items are `lazy://` placeholders is appended to a bottom "blocked — tap to solve" section (or one aggregated row of all placeholder cards). Placeholders never occupy the first screen. This is UI ordering logic and belongs app-side, where row ordering already lives; the plugin repo needs no change and old app builds are unaffected (they don't support lazy search anyway — the reflection gate already handles that).

**Fallback if the app can't be touched right now: plugin-side stagger.**
In `BaseProvider.search()`'s CF-catch branch, `delay(LAZY_PLACEHOLDER_DELAY_MS)` (~4–6s, configurable via `LazySearchConfig`) before returning the placeholder. Since row order is arrival order, fast real providers land first. Cheap, one-line, but adds latency to the placeholder row and is heuristic rather than guaranteed. Can ship as an interim measure and be removed once app-side partitioning lands.

**Also in this phase (correctness of the placeholder card):**
- Verify/fix the `fixUrl` mangling: ensure the card's url survives as pure `lazy://<providerName>` (build the `SearchResponse` without fixUrl, or make app-side `resolveLazySearch` extract by `lazy://` substring). Note: `lazy://<name>` carrying the provider *name* is by design (the resolver needs to know which provider to re-query) — the bug was only the poisoned-domain prefix (fixed by Phase 0) plus fixUrl prefixing.
- Consider setting the placeholder's `TvType` from the provider's `supportedTypes` instead of hardcoded `Movie` (cosmetic).

### Phase 3 — Search pagination (task 2) — shared lib + parsers, backward compatible
1. **`ParserInterface`**: add `getSearchUrl(domain, query, page: Int = 1)` (default: ignore page for page 1, or apply a new `searchPaginationFormat` mirroring the existing `paginationFormat` used by `getMainPage`). Optionally `hasNextSearchPage(document): Boolean` with default "false".
2. **`BaseProvider`**: override the **paged** `MainAPI.search(query: String, page: Int): SearchResponseList`; thread `page` into `searchNormal(query, page)` / `searchLazy(query, page)` (keep pageless wrappers delegating to page=1 so existing provider overrides like KooraLive/YallaShoot/Viu keep compiling). Return `newSearchResponseList(items, hasNext)` where `hasNext` = parser signal, defaulting to a heuristic: `items.size >= fullPageThreshold` or presence of a next-page link.
3. Keep the legacy pageless `search(query)` override delegating to the paged one (page=1) so both MainAPI entry points behave identically.
4. **Providers**: opt in incrementally — start with the top providers (CimaNow, 3isk/eishk, MyCima, Akwam, Laroza) by setting `searchPaginationFormat` / next-link selector in their parsers. Fix `Viu`/`Replaymatch` to pass the real `page` into their existing `searchWithPage`.
5. Lazy placeholders and pagination: for page > 1, if CF-blocked, return empty with hasNext=false (never emit a second placeholder).

### Phase 4 — Disable search for football providers (task 4) — trivial, plugin side
1. Add `open val supportsSearch: Boolean = true` to `BaseProvider`; first line of `search()` (both overloads): `if (!supportsSearch) return empty`. Explicit gate — doesn't rely on the app filtering by `supportedTypes` (which demonstrably doesn't exclude KooraLive today).
2. Set `override val supportsSearch = false` in `SyriaLive`, `KooraLive`, `YallaShoot`; delete their now-dead `searchNormal`/`searchLazy` overrides.
3. Also tighten `supportedTypes` to `setOf(TvType.Live)` on SyriaLive and YallaShoot for correctness.

---

## Sequencing & risk
- **Order: 0 → 4 → 1 → 2 → 3.** Phase 0 is the highest-value/lowest-risk (fixes two symptoms at once); Phase 4 is a 20-minute change; Phase 1 is contained hardening; Phase 2's recommended path needs an app-repo change (coordinate app release; ship the plugin-side delay as interim if needed); Phase 3 is the largest surface (touches parser interface + many providers) but fully backward compatible by design.
- Each phase is independently shippable and testable. Verify Phase 0 with the Cimawbas repro from log3.txt (search "unstoppable"; domain must self-heal and the post-close 403 bursts must stop).

## ADDENDUM (2026-08-31) — post-implementation findings

### Search pagination: the real blocker is APP-SIDE, not provider-side
- Plugin plumbing is verified working at runtime (device log: `Laroza.searchLazy START query='the', page=1`). `BaseProvider.search(query, page)` matches the jar's `MainAPI.search(String,int,Continuation<SearchResponseList>)` exactly and is genuinely invoked.
- **`SearchViewModel.bundleSearch()` hardcodes `hasNext = false`.** Verified by bytecode: both `ExpandableSearchList.<init>(List,I,Z)` call sites are preceded by constant pushes (`iconst_1, iconst_0` and `iconst_0, iconst_0`) — page and hasNext are literals, never read from the provider's returned `SearchResponseList`. When more than one provider matches a query, the merged list is built with `hasNext=false` unconditionally.
- Consequence: **in the combined multi-provider search screen, page 2 is architecturally unreachable** regardless of parser correctness. Only `SearchViewModel.expandAndReturn(name, ...)` computes a real `nextPage` and re-invokes a single provider's paged search — i.e. pagination works only in the single-provider expanded / "see all" view.
- => Fix belongs in the **app repo**: make `bundleSearch()` propagate a real `hasNext` (e.g. OR of per-provider hasNext, tracking per-provider page cursors) instead of the literal `false`. No provider-side change can work around it.
- Provider opt-in status: **Laroza opted in** (`search.php?keywords=<q>&page=N`, verified against live HTML; hasNext via `ul.pagination li.active` next-sibling not `.disabled`) in `LarozaProvider/.../LarozaParser.kt`. CimaNow, MyCima, Akwam, Anime4up, Cimaleek **not** opted in — their domains were DNS-sinkholed in the agent sandbox so paging shape could not be verified; left at defaults (`searchPaginationFormat=null`, `hasNextSearchPage=false`), behavior unchanged. Opt them in when their live search HTML can be inspected.
- Note: `getMainPage()` needs no `hasNext` (it pages until empty), which is why main-page pagination works while search does not.

### Remaining random ChromiumFetcher 403
- All `ChromiumFetcher.fetch` call sites enumerated: only `ProviderHttpService.executeDirectRequest` is live (and breaker-guarded). `ProviderHttpService.fetchViaChromeTls` and `DirectHttpStrategy.executeViaChromium` are **dead code** (zero callers / never instantiated) — candidates for deletion.
- Fixed: breaker domain key in `executeDirectRequest` now strips `www.` so it shares circuit state with `getDocument`'s `extractDomain`-derived key (previously `www.x.com` and `x.com` tracked separate circuits, weakening the breaker).
- Added: `ChromiumFetcher.onReceivedHttpError` now logs the target URL + request URL with the status code (previously a bare `code=403`, which is why attribution required reasoning by elimination).
- **UNRESOLVED / needs a fresh capture.** Caveats on the 2026-08-31 log: (a) it is sparse (13 lines/70s) and likely filtered, so the absence of preceding `[executeDirectRequest]` lines is NOT reliable evidence of a non-`executeDirectRequest` caller; (b) `shared/` ships inside each provider **plugin**, not the app APK, so the APK's install date says nothing about whether the breaker is live on device — check the plugin build/download date instead.
- Next step: rebuild + reload plugins, capture unfiltered logcat (or filter `ChromiumFetcher|ProviderHttp|CircuitBreaker`); the new URL in the 403 line names the culprit host directly. Benign candidate explanation: home-row prefetch loading several providers at once (bursts, no visible provider screen).

### CORRECTION (app source found) — search has TWO modes; only one lacked pagination
App source lives one level up at `/Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2` (branch `develop`, fork of recloudstream/cloudstream, versionName 4.6.2 / versionCode 69). `omarC/` is a *separate nested git repo* for the plugins.

`SearchFragment` toggles on `isAdvancedSearch` (`SearchFragment.kt:443-444`):
- **Advanced = per-provider rows** (`searchMasterRecycler`, `ParentItemAdapter`) fed from `currentSearch` → each row gets the provider's REAL `currentPage`/`hasNext` and pages via `expandAndReturn(name)`. **This mode already paginated correctly** — it was only ever blocked by providers reporting `hasNext=false` (now fixed for Laroza). `bundleSearch` is NOT involved here.
- **Non-advanced = merged grid** (`searchAutofitResults`, `SearchAdapter`) fed from `searchResponse` → `bundleSearch()`. This mode had **no pagination hook whatsoever**: `bundleSearch` hardcoded `hasNext=false` AND the observer ignored `hasNext`/`currentPage` entirely, and no scroll listener was attached.

FIXED (app repo, `:app:compilePrereleaseDebugKotlin` clean):
1. `SearchViewModel.bundleSearch()` — returns `ExpandableSearchList(list, 1, lists.values.any { it.hasNext })` instead of a literal `false`.
2. `SearchViewModel.expandAllAndReturn()` (new) — pages every provider that still has a next page (in parallel via `amap`, honoring the existing `lock` set), merges with `distinctBy { it.url }`, reposts `currentSearch` + rebundled `searchResponse`. The merged grid has no per-provider row to expand, so paging it means paging all contributors at once.
3. `SearchFragment` — feeds `data.hasNext` into the grid's `SearchAdapter.hasNext`, and attaches an endless-scroll listener on `searchAutofitResults` mirroring the working home-expanded idiom (`!isRecyclerScrollable() && hasNext && expandCount != count` → `expandAllAndReturn()`). Cast safety confirmed: `AutofitRecyclerView` uses `GrdLayoutManager : GridLayoutManager : LinearLayoutManager`, which `isRecyclerScrollable()` requires.

Note: the app's build variants are `prerelease`/`stable`, so the compile task is `:app:compilePrereleaseDebugKotlin` (plain `compileDebugKotlin` is ambiguous).

## Open items to confirm during implementation
1. Does `newMovieSearchResponse` fixUrl the `lazy://` url (explains the `https://<domain>/lazy://` shape)? Check MainAPI in the jar.
2. Exact contract of app-side `resolveLazySearch` (does it expect `lazy://<name>` verbatim?) — needed before changing the placeholder URL shape.
3. Whether the app repo is available to edit for Phase 2's partitioning (it's not in this plugin repo).

---

## Provider search-pagination opt-in status (2026-08-31)

Search pagination is **opt-in per parser**. Infra: `ParserInterface.searchPaginationFormat`,
`supportsSearchPagination`, and tri-state `hasNextSearchPage(doc): Boolean?` (`null` = "no opinion").
`BaseProvider` resolves it as:

```kotlin
val hasNext = getParser().hasNextSearchPage(doc) ?: (getParser().supportsSearchPagination && items.isNotEmpty())
```

The `?:` fires only on `null`, so a parser's explicit `false` (a precise selector) is never overridden.
The fallback mirrors `getMainPage()`, which pages until a page comes back empty.

### How to opt in a new provider
- **Suffix case** (page is a query param): `override val searchPaginationFormat: String get() = "&page=%d"`. Done.
- **Path case** (page segment precedes the query string, e.g. WordPress): override the 3-arg
  `getSearchUrl(domain, query, page)` (delegate to the 2-arg version when `page <= 1`) **and**
  `supportsSearchPagination = true`.
- Add a precise `hasNextSearchPage` only if the site has a usable pagination element (see Laroza).

| Provider | Tier | Search URL | Paging | hasNext | Verified |
|---|---|---|---|---|---|
| Laroza | reference | `search.php?keywords=` | `&page=%d` | precise selector (`li.active` sibling) | ✅ live HTML + on device |
| Cimawbas | A | `search.php?keywords=…&video-id=` | `&page=%d` | fallback | ⚠️ engine-inferred |
| CimaLight | A | `search.php?keywords=` | `&page=%d` | fallback | ⚠️ engine-inferred |
| Bristege | A | `search.php?keywords=` | `&page=%d` | fallback | ⚠️ engine-inferred |
| Krmzy | B | `?s=` | `/page/N/?s=` (3-arg override) | fallback | ❌ WP convention guess |
| Gesseh (eseek) | B | `?s=` | `/page/N/?s=` (3-arg override) | fallback | ❌ WP convention guess |

**Tier A** = same site engine as Laroza (identical `search.php?keywords=` search URL) — strong evidence.
**Tier B** = WordPress-style (`/page/%d/` main-page `paginationFormat` + `?s=` search) — canonical WP
convention, but unverified.

**Why unverified:** all provider domains except `laaroza.mom` are DNS-sinkholed on the dev machine
(they resolve to `203.0.113.250`, a block page), so live HTML could not be fetched. Re-check the ⚠️/❌
rows against real HTML when on a network that reaches them.

**Failure mode is benign**, not corrupting: a wrong paging URL returns an empty or duplicate page →
the app dedupes via `distinctBy { it.url }` → the scroll listener's `expandCount != count` guard stops
re-firing. Symptom is "pagination silently doesn't work", never wrong or duplicated results.

### Deliberately not opted in
- **ArabSeedV4** — search URL is `/find/?word=`, not `?s=`, so the WP shape doesn't apply. More
  importantly `ArabseedV4.kt` overrides the **pageless** `searchNormal(query)`/`searchLazy(query)` with
  custom parallel movies+series logic that never reaches the paged path — a parser-level change would
  be inert. Needs a provider-level rewrite to support pagination.
- All others (dima-toon, TukTukcima, Tuniflix, CimaLeek, Shahid4u, Anime4up, Wecima, FaselHDV2,
  CimaClub, EgyDead, …) — no custom `paginationFormat`, not the `search.php?keywords=` engine, and no
  reusable pagination selector, i.e. no in-repo evidence of a paging shape. Left at defaults
  (behavior identical to before).
- SyriaLive / KooraLive / YallaShoot — football providers, search disabled entirely
  (`supportsSearch = false`).
