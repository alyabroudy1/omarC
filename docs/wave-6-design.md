# Wave 6 design: domain handling under R3

Implements [shared-refactor-waves.md](shared-refactor-waves.md) "## Wave 6" (`:510-622`) and
[shared-architecture-review-third-eye.md](shared-architecture-review-third-eye.md) section 3
(`:51-89`). Written against the tree at `50df2f90`. Wave 4c may be moving `NavigationEngine`
concurrently; every `webview/` line below is cited but not scheduled by this document (see §8 Q5).

Binding owner rules: **no** aliases, related-domain heuristics, registrable-suffix/TLD logic,
`www.`-stripping policy, denylists, or name-based validation. A purely syntactic "is a hostname"
check is allowed. One source of truth for the current domain (`DomainManager`); `SessionProvider`
deleted. `CookieManager` is the only jar. Behaviour-based only.

---

## 1. Inventory: every name-based domain decision in the current tree

Verdicts reference the three replacement rules of §2: **A** = adoption rule, **H** = host-history
rewrite rule, **S** = remote-sync rule, **X** = syntactic host parse only.

| # | File:line | What it decides by name | Verdict |
|---|---|---|---|
| 1 | `shared/.../domain/DomainManager.kt:156-160` `DENYLISTED_HOSTS` | three literal Cloudflare hosts may never be a provider domain | **delete** (22 LOC with #2). Replaced by A: a challenge response can no longer be adopted at all |
| 2 | `DomainManager.kt:167-177` `isValidProviderDomain` | rejects the denylist plus `*.cloudflare.com`; also blank / non-ASCII / dot-less | **split**: delete `:172-174` (the name decision); keep `:168-170` as **X** (`hostOf`, §2.1) |
| 3 | `DomainManager.kt:98-102` | `updateDomain` refuses a "denylisted" host | **delete**; `adopt()` is the only writer (A) |
| 4 | `DomainManager.kt:113-117` | `syncToRemote` refuses a "denylisted" host | **delete**; replaced by S |
| 5 | `HttpGateway.kt:474-490` `documentNoSolve`'s `isValidProviderDomain` guard | whether a resolved host may become the domain, by name | **delete** (17 LOC → 1). A subsumes it: non-`Success`, non-2xx and CF-marked HTML are all rejected without names |
| 6 | `session/SessionProvider.kt:19-21` `domainAliases` | which hosts "share cookies with the main domain" | **delete**. The jar decides cookie scope from `Set-Cookie` (`SystemCookieJar.kt:60-66`); no alias set exists |
| 7 | `SessionProvider.kt:42-67` `extractBaseDomain` | strips subdomains + TLD to a "core name" | **delete** (26 LOC). Registrable-suffix logic, forbidden outright |
| 8 | `SessionProvider.kt:69-75` `MULTI_PART_TLDS` | 18 literal public suffixes | **delete** (7 LOC) |
| 9 | `SessionProvider.kt:84-97` `areDomainsRelated` | two hosts are "the same site" if their base names match | **delete** (14 LOC). Replaced by H: membership in the persisted history, not similarity |
| 10 | `SessionProvider.kt:103-128` `addDomainAlias` | registers the old host as an alias iff "related" | **delete** (26 LOC) |
| 11 | `SessionProvider.kt:133,138-142` `getDomainAliases` / `clearDomainAliases` | readers of #6 | **delete** with the file |
| 12 | `HttpGateway.kt:123,126` | calls #10 on every domain change and logs the alias count | **delete** |
| 13 | `provider/BaseProvider.kt:389-417` `handleDomainDifference` | if the `load` URL's host differs from `mainUrl`'s and is "related", alias it | **delete** (29 LOC). Zero callers (`grep -rn handleDomainDifference --include='*.kt' .` → the definition only) |
| 14 | `queue/RequestQueue.kt:119-130` `allowedDomains` | `takeLast(2)` of the dotted host of both the request and the redirect target — a hand-rolled registrable-domain guess, twice | **delete** (12 LOC) |
| 15 | `HttpGateway.kt:425` | the same `split(".").takeLast(2)` guess for the `document()` CF fallback | **delete** (1 LOC) |
| 16 | `HttpGateway.kt:70-71,:709,:742`; `RequestQueue.kt:35,:132,:198` | the `allowedDomains` parameter carrying #14/#15 | **delete** the parameter; `solveCloudflare(url)` |
| 17 | `webview/CfBypassEngine.kt:53,:170-192` | `baseDomain()` = `takeLast(2)`, then `allowedDomains.any { nextBase.contains(it) \|\| it.contains(nextBase) }` — a substring-similarity test | **delete** (params + `:186-192`, ~14 LOC). Confirmed no-op: the miss branch at `:193-197` returns `false` (allow) anyway, so the set never changed an outcome. Matches `wave-2-review.md`'s reading and the waves doc `:535` |
| 18 | `NavigationEngine.kt:227,:1403,:2487-2495` — **note**: Wave 4c has already moved this file out of `shared/webview/` into `CimaNowProviderV2/src/main/kotlin/com/cimanow/webview/` in the working tree (`git status`: `RM`), so after 4c it is one provider's own code | main-frame navigation blocked unless `nextHost == d \|\| nextHost.endsWith(".$d")` | **judge: not a provider-domain decision.** It is a provider-declared navigation lock with live callers (`CimaNowProvider.kt:1342-1346,:1359`, `:3130-3136,:3146`) that pass literal host lists. The `endsWith(".$d")` widening *is* a subdomain heuristic and should go; exact-host membership decides who may navigate, like the worker's `configFile` allowlist. Owner question Q5 |
| 19 | `HttpGateway.kt:580` `.removePrefix("www.")` | `www.x` and `x` share one circuit-breaker key | **delete the strip** (X). Keying by exact host is correct under R3; the comment at `:578-580` documents the deliberate old coupling and goes with it |
| 20 | `HttpGateway.kt:842-846` `extractDomain` | `URI(url).host?.removePrefix("www.")` | **replace by X** (`DomainRules.hostOf`, no strip) |
| 21 | `RequestQueue.kt:298-304` `extractDomain` | same body, second copy | **replace by X** |
| 22 | `DomainManager.kt:70-73` and `:93-96` | scheme/slash trimming of a candidate domain, two copies | **replace by X**: one normalizer, used for the remote-config value and nothing else |
| 23 | `FaselHDV2Provider/.../FaselHDV2Parser.kt:120-124` `hostOf` | fourth live copy, also `removePrefix("www.")` | provider-local parser helper, used to classify iframe hosts, not to decide a domain. **Leave**; note it as the one surviving copy so the count is honest |
| 24 | `provider/ProviderConfig.kt:25-26` `trustedDomains` | "trusted domain substrings for private server detection" — a substring test over host names | **delete** (2 LOC). Its only call site went in Wave 2 (`wave-2-review.md` §3); `grep -rn trustedDomains --include='*.kt' .` returns the declaration only |
| 25 | `HttpGateway.kt:552-553` | logs `isAlias = urlDomain != sessionState.domain` | **delete** the field from the log line; the concept is gone |
| 26 | `HttpGateway.kt:767-776` `rewriteUrlIfNeeded` + `:955-965` `rewriteHost` | rewrites **any** host that differs from the current domain, by whole-URL `String.replace` | **replace by H** (§2.2). Not name-based, but it is the decision H replaces, and the substring replace is a live bug (`UrlRewriteTest.kt:30-36`) |
| 27 | `RequestQueue.kt:290-296` `rewriteFollowerUrl` | same, second copy | **replace by H** |
| 28 | `HttpGateway.kt:778-796` `checkAndUpdateDomain` | adopts on any host change, from any outcome the caller hands it | **replace by A** (19 LOC) |
| 29 | `HttpGateway.kt:803-829` `handleMetaRefreshRedirect` → `:817` | adopts the meta tag's target host before fetching it | **replace by A** (adopt the *followed* fetch's outcome, §2.4) |
| 30 | `RequestQueue.kt:86-99` and `:134-142` | two more adoption sites (leader final URL; post-CF final URL), unconditional on host change | **replace by A** (§2.5) |
| 31 | `HttpGateway.kt:748-752` | a comment forbidding adoption after a CF solve, because the solve may end on a CF host | **delete the special case**: A's own test decides it (§2.4) |
| 32 | `configs/domain-sync-worker/index.js:11-34,:78-83` | `KNOWN_CONFIG_FILES` — a **provider file name** allowlist, also the path-traversal fix for `configs/${configFile}` (`:99`) | **keep unchanged.** Not a domain decision; it decides who may write. Waves doc `:523` |
| 33 | `core/SystemCookieJar.kt:74-77` KDoc | promises registrable-domain widening "until Wave 6" | **correct the KDoc**: under R3 that widening never happens (§2.6) |

Counts: 5 copies of domain-string parsing existed (waves doc `:537`); `CookieLifecycleManager.normalizeKey`
went in Wave 2, so 4 remain (#20, #21, #22, #23) and #23 stays as a parser helper. Rows 6-11 are the
whole of `SessionProvider.kt`.

---

## 2. The replacement design

### 2.1 `DomainManager` after the wave

One pure rules object (JVM-testable, no Android, no OkHttp) plus a thin persisted owner.

```kotlin
// shared/src/main/kotlin/com/cloudstream/shared/domain/DomainRules.kt  (new, pure)

/** Syntactic host parse. Decides nothing about *which* site: no strip, no suffix, no list. */
internal fun hostOf(url: String): String? {
    val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return null
    val h = host.lowercase()                       // hosts are case-insensitive (RFC 4343)
    if (h.isBlank()) return null
    if (h.any { it.isWhitespace() || it.code > 127 }) return null
    if (!h.contains('.')) return null
    return h
}

internal object DomainRules {

    /** Rule A. The host this outcome may be adopted at, or null. */
    fun adoptedHost(outcome: FetchOutcome, kind: RequestKind): String? {
        if (kind != RequestKind.Document) return null
        val s = outcome as? FetchOutcome.Success ?: return null
        if (s.code !in 200..299) return null
        if (CloudflareDetector.isCloudflareChallenge(s.html)) return null
        return hostOf(s.finalUrl)
    }

    /** Rule H. [url] pointed at [current], or [url] unchanged. */
    fun rewrite(url: String, history: Set<String>, current: String): String {
        val host = hostOf(url) ?: return url
        if (host == current || host !in history) return url
        return replaceHostComponent(url, current)
    }

    /** Host component of the authority only. Everything else survives byte for byte. */
    private fun replaceHostComponent(url: String, toHost: String): String { /* §2.2 */ }
}
```

```kotlin
class DomainManager(
    context: Context,
    private val providerName: String,
    private val fallbackDomain: String,
    private val githubConfigUrl: String?,
    private val syncWorkerUrl: String? = null,
    private val scope: CoroutineScope,                 // structured; replaces :121
    private val clock: () -> Long = System::currentTimeMillis
) {
    val currentDomain: String                          // single source of truth
    val hostHistory: List<String>                      // persisted, ordered oldest→newest, capped

    suspend fun ensureInitialized()                    // as today, minus the name checks
    fun buildUrl(path: String): String                 // unchanged (:144-147)

    /** Rule A + H bookkeeping + rule S trigger. True iff the current domain changed. */
    fun adopt(outcome: FetchOutcome, kind: RequestKind): Boolean

    /** Rule H. */
    fun rewrite(url: String): String

    /** Rule S evidence: this host redirected away or hard-failed in this session. */
    fun noteFailure(host: String)
}
```

`adopt` semantics, in order:

1. `val host = DomainRules.adoptedHost(outcome, kind) ?: return false`.
2. Append `host` to `hostHistory` if absent (cap eviction: drop the oldest, §3). Appending happens
   even when `host == currentDomain`, so the first-ever success records the host we are already on.
3. `adoptionCount[host]++` (in-session, not persisted).
4. If `host == currentDomain` → return false. Otherwise: append the **outgoing** `currentDomain` to
   the history (it is a host this provider was served on), set `currentDomain = host`, persist both
   keys, log one line naming old and new. Return true.
5. Rule S: see §4.

Note the 2xx tightening at step 1: `classify` maps `200..399` to `Success`
(`core/FetchOutcome.kt:52`), so today a 3xx body could adopt. Rule A requires 2xx, as the waves doc
says (`:548`). The CF test is `isCloudflareChallenge(html)`
(`cloudflare/CloudflareDetector.kt:49-56`) rather than `isBlocked(code, html)` (`:70-74`), because
`isBlocked` requires a CF *response code* and a solve returns a synthetic `code = 200`
(`HttpGateway.kt:749`) — the marker test is the strictly stronger one and the only one that can
reject a 200 challenge page. Neither names a host.

### 2.2 Host-component rewriting

`replaceHostComponent` splices the authority's host span and nothing else:

- find `"://"`; the authority runs to the first `/`, `?` or `#` after it (or end of string);
- inside the authority, the host starts after `@` if present and ends at the last `:` (port) if the
  remainder is all digits, else at the authority's end;
- replace exactly that span.

No `URI(scheme, authority, path, query, fragment)` reconstruction (it re-quotes `%`), no
`HttpUrl` round-trip (it normalizes empty paths to `/` and drops default ports). This is what makes
`rewritesHostComponentNotQueryString` and `preservesPortAndScheme` both pass, and it is the fix for
the substring bug pinned at `UrlRewriteTest.kt:30-36`.

`buildUrl(pathOrUrl, domain)` (`HttpGateway.kt:971-975`) is unaffected and stays.

### 2.3 What "provider-initiated document request" means in code

Two conditions, both required:

1. the call is `ProviderRuntime.document(...)` (`core/ProviderRuntime.kt:21-28`) with
   `adoptRedirect = true`, and
2. `kind == RequestKind.Document` at the `adopt()` call.

`adoptRedirect` already exists and is already opt-in per call site — 17 sites, all main-page /
search / load document fetches: `BaseProvider.kt:126,:169,:260`; `FaselHDV2.kt:123,:176`;
`Cimawbas.kt:37,:65,:89`; `anim3rbProvider.kt:48,:153,:155`; `eishk.kt:28,:48,:66`;
`GessehProvider.kt:60,:74`. Nothing else in the repo passes it.

Never adopt: `text` (`ProviderRuntime.kt:31`), `post` (`:34-40`), `raw` (`:43`), `sniff` (`:46`),
`imageHeaders` (`:49`), and every extractor and intercepted subresource. `text` and `post` tag their
OkHttp requests `RequestKind.Document` for fingerprint purposes (`HttpGateway.kt:571,:685`), which is
exactly why the `adoptRedirect` gate is the primary condition and the `kind` argument is the
belt-and-braces one: it makes an accidental future wiring from an extractor path inert and testable
(`doesNotAdoptFromExtractorOrImageRequest`).

`RequestKind` (`core/FingerprintInterceptor.kt:11`) grows to
`Document, Subresource, Extractor, Image`; the `when` at `:72-84` routes the two new members to the
existing subresource branch. One enum, no new type.

### 2.4 Meta-refresh and CF-solve final URLs

**Meta-refresh** (`HttpGateway.kt:803-829`). Delete the pre-emptive
`checkAndUpdateDomain(currentUrl, metaRefreshUrl)` at `:817`. The target URL is fetched first
(`:819` `requestQueue.enqueue(metaRefreshUrl)`) and its own outcome is passed to `adopt()`. So the
target host is adopted only if *its* fetch is a clean 2xx non-CF document — which is precisely
`adoptsMetaRefreshTargetOnlyAfterItPasses`. The cross-host precondition at `:809-810` stays (a
same-host refresh is a page reload, not a domain move).

**CF solve** (`HttpGateway.kt:744-752`). The comment forbidding adoption here goes. The solve returns
`FetchOutcome.Success(result.html, 200, result.finalUrl)`; that value is fed to the same `adopt()`.
A solve that ended on a challenge page is rejected by the marker test, a solve that ended on the CF
proxy with real content is adopted — which is the case `RequestQueue.kt:135-142` was hand-coding.
No host names, and the FaselHD proxy hop (`RequestQueue.kt:104-107`) stays un-adopted because the
proxy response is CF-flagged.

### 2.5 Where each decision site calls it

| Site now | After |
|---|---|
| `HttpGateway.kt:57` `sessionState` field | **deleted**. `currentDomain` is `domainManager.currentDomain`; `session/SessionState.kt` (29 LOC) goes with `SessionProvider` |
| `:79-80`, `:84-85` `currentDomain` / `domain` | `domainManager.currentDomain` |
| `:87-107` `ensureInitialized` | keeps the mutex and `domainManager.ensureInitialized()`; delete `:94-95` (`SessionProvider.initialize`) and `:100-102` (the `sessionState` re-sync) |
| `:109-127` `updateDomain` | **deleted** (19 LOC). Only `adopt()` and the remote config write the domain |
| `:393` `checkAndUpdateDomain(url, success.finalUrl)` | `if (adoptRedirect) domainManager.adopt(result, RequestKind.Document)` |
| `:402`, `:504` meta-refresh | §2.4 |
| `:474-490` the `isValidProviderDomain` guard | `if (adoptRedirect) domainManager.adopt(result, RequestKind.Document)` — one line; the `isCfBlocked` pre-test is no longer needed for adoption (it is still needed for the throw at `:494`) |
| `:410`, `:414`, `:425`, `:430`, `:434`, `:580` breaker keys | `hostOf(url)` / `hostOf(targetUrl)`, exact host, no `www.` strip, no `takeLast(2)` |
| `:528`, `:651`, `:716` `rewriteUrlIfNeeded(url)` | `domainManager.rewrite(url)` |
| `:765` `buildUrl` | `domainManager.buildUrl(pathOrUrl)` |
| `:767-776`, `:778-796`, `:842-846`, `:955-965` | **deleted**; `DomainRules` owns all four |
| `RequestQueue.kt:33-38` `Host` | `execute(url, headers)`; `solveCloudflare(url)`; `adopt(outcome: FetchOutcome)`; `noteFailure(host: String)`; `rewrite(url: String): String`; `val currentDomain: String`. `onDomainRedirect(old, new)` is gone — the queue no longer computes a redirect, it hands over an outcome |
| `RequestQueue.kt:53-79` `enqueue` / `enqueueAction` | gain `adopt: Boolean = false`, threaded from `document(adoptRedirect)` into `QueuedRequest` |
| `:86-99` leader success | `if (leaderHost != hostOf(result.finalUrl)) host.noteFailure(leaderHost)`; then `if (leader.adopt) host.adopt(result)`. Delete `:89-95`. Order matters: adoption happens **before** followers are released at `:99`, so followers still see the new domain — that is why the queue keeps an adoption hook instead of letting `document()` adopt after the fact |
| `:101-158` CF branch | delete `:119-130`; `host.solveCloudflare(solveUrl)`; on success `host.noteFailure(requestDomain)` (it *was* CF-blocked — observed failing) then `if (leader.adopt) host.adopt(cfResult)`; delete `:135-142` |
| `:160-164` failure branch | `host.noteFailure(extractDomain(leader.url))` |
| `:179`, `:219`, `:246` | `host.rewrite(request.url)` |
| `:290-296`, `:298-304` | **deleted**; keying and rewriting both come from `DomainRules` |
| `BaseProvider.kt:389-417` | **deleted**. `mainUrl` (`:31-32`) already reads `runtime.domain` and needs no change |

One behaviour change worth naming: the queue's leader key is now the exact host, so `www.x` and `x`
get separate leaders where they used to share one (`RequestQueue.kt:300`). Correct under R3 — they
are two hosts — and the cost is at most one extra solve the first time a site moves between them.

### 2.6 Cookies

Nothing changes and nothing becomes name-based. The jar writes `Set-Cookie` verbatim against the
response URL (`SystemCookieJar.kt:60-66`) and reads by request URL (`:51-58`); `CookieManager`
decides scope. `expireCookiesFor` stays per-host with `Path=/` (`:79-87`).

The Wave 2 finding F3 (`docs/reviews/wave-2-review.md` F3) said widening that expiry "needs the
registrable domain, which is Wave 6 territory". Under R3 it is **never** territory: registrable-suffix
logic is forbidden, so a `Domain=`-scoped `cf_clearance` cannot be expired by us and the invalidation
stays partly cosmetic for exactly that cookie. Action: fix the KDoc at `SystemCookieJar.kt:74-77` to
drop the "until Wave 6" promise and state the permanent limitation. The mitigation already in the
tree is the pre-solve retry (`HttpGateway.kt:718-727`, `shouldRetryBeforeSolve`), which makes a stale
clearance cost one request instead of a solve.

The anim3rb note (`wave-4b-2-review.md` F4, `anim3rbProvider.kt:326-327`) resolves itself: the comment
describes cookies going to a session domain poisoned to `video.vid3rb.com`. Under rule A a subresource
host can never become the domain, so the case cannot recur; the stale comment can go.

### 2.7 The poisoned-domain self-heal, without a denylist

Walk of the incident in `docs/README.md:41-49` and `search-architecture.md:10-13`:

1. **Then**: `getDocumentNoFallback` called `checkAndUpdateDomain(url, result.finalUrl)`
   unconditionally, including on the 403 challenge whose redirect chain ended on `cloudflare.com`;
   `updateDomain` persisted it (`DomainManager.kt:106`); `syncToRemote` could push it to the worker.
   Cimawbas ended up with `urlDomain=cloudflare.com, sessionDomain=cloudflare.com` (`log3.txt:9`).
2. **Now, step by step.** The challenge response is a 403 with CF markers → `classify` returns
   `CloudflareBlocked` (`FetchOutcome.kt:49-51`) → `adoptedHost` returns null at the
   `as? Success` cast. If the site instead served a 200 challenge, `isCloudflareChallenge(html)`
   rejects it. If a CF solve ran and ended on `cloudflare.com` still showing a challenge, the same
   marker test rejects it. Adoption is therefore impossible on that host in every observed shape,
   and no host name appears anywhere in the decision.
3. **Rewrite** cannot reintroduce it either: `cloudflare.com` is not in the history (it was never
   adopted), so rule H leaves URLs alone rather than pointing them at it — and conversely, once the
   real host is adopted, links still on the old real host *are* rewritten because that host **is** in
   history.
4. **Sync** cannot spread it: rule S needs two adoptions of the host, and it has zero.
5. **An install already poisoned** heals per §3: the migration drops the pre-Wave-6 persisted value
   once, so the first launch starts from the remote config / bundled fallback, and the first clean
   2xx document adopts the real host — one log line naming it, which is the manual check the waves
   doc asks for (`:611-616`).

### 2.8 The FaselHD multi-host scenarios

| Scenario (waves doc `:564-571`) | This design |
|---|---|
| main page `faselhd.center` → `faselhds.shop`, 2xx non-CF | adopted by A; `faselhd.center` is appended to history at `adopt` step 4 |
| cached/embedded links still on `faselhd.center` | rewritten by H (host in history), **host component only** |
| watch link on `faselhds.shop` while main is `faselhd.center` | fetched as-is: `loadLinks` uses `document(rewrite = false)` / `raw`, so no rewrite, and `adoptRedirect` is false there, so no adoption. Cookies come from whatever that host set itself |
| third-party CDN / embed host | never rewritten (absent from history), never adopted (never a document request with `adoptRedirect`) |
| `www.faselhd.center` vs `faselhd.center` | two hosts. Each is rewritten only if it is itself in history. If the site redirects between them, A records the target and the other one enters history as the outgoing domain, so both end up present — which is how the case resolves itself without a `www.` policy |
| CF challenge redirect to `cloudflare.com` | §2.7 |

**Not handled, stated plainly.** A link on a sibling host that was **never** the adopted domain and is
now dead — the stale `w312x.faselhdx.xyz` shape referenced at `HttpGateway.kt:121` — is not rewritten
and will fail. Name similarity used to save it. Nothing in this design does. Two behaviour-based
options exist and **neither is in scope now**; add one only when a log shows the case:
(a) retry a failed provider-initiated document request once against the current domain when the
failure is at DNS/connect level (`FetchOutcome.Transport` with an `UnknownHostException` /
`ConnectException` cause — a transport class, not a name); (b) a parser-level hook that normalises
link hosts for that one site.

---

## 3. Persistence

`SharedPreferences("domain_$providerName")` (`DomainManager.kt:27-30`), two keys:

| Key | Value | Written by |
|---|---|---|
| `domain` | current host, no scheme (unchanged key and format) | `adopt()` step 4; the remote-config branch |
| `host_history` | JSON array of hosts, ordered oldest→newest, cap **8** (Q1) | `adopt()` steps 2 and 4 |

`org.json` is already imported (`DomainManager.kt:11`), so no new dependency. Eviction drops the
oldest entry; the current domain is never evicted (it is re-appended on every adoption of itself).

**Migration.** A pre-Wave-6 install has `domain` but no `host_history`. On that exact condition:

- seed `host_history` with the persisted `domain` (so links on it keep being rewritten later), then
- **clear** `domain` and start from `fallbackDomain` + the remote-config fetch (`:61-86`).

No name check is involved and none is possible — R3 forbids telling a good persisted host from a
poisoned one by inspection. What the drop costs a healthy install is one re-adoption: the site is
fetched on the bundled/remote host, redirects to its current one, and A adopts it on the first
document request. What it buys is that acceptance criterion 6 holds for **every** provider,
including one with `githubConfigUrl == null`, where a poisoned persisted domain would otherwise 403
forever and never produce the successful document its own self-heal depends on. This is the one
place where the design chooses a one-off cost over a permanent hole; see Q4 if the owner prefers to
keep the persisted value and rely on remote config alone.

The remote-config value (`:75-79`) is not an adoption: it sets `currentDomain` and appends to
history, does not increment `adoptionCount`, and sets `lastSyncedDomain` so it can never be pushed
back to the worker.

---

## 4. Worker sync rule

**Client** (replaces `DomainManager.kt:111-142`). Push iff all of:

1. `adopt()` just returned a host `H`, and
2. `adoptionCount[H] >= 2` — the second passing adoption on `H`, and
3. some host observed failing in this session (`noteFailure`) was the current domain immediately
   before `H`'s first adoption, and
4. `H !in syncedHosts` — exactly one push per host per process.

`noteFailure(host)` is called from `RequestQueue` on a leader redirect away from `host`
(`:86-99`), on a CF block of `host` that a solve got through (`:134`), and on the leader failure
branch (`:160-164`). All three are outcome classes, not names.

The push body is unchanged (`:123-129`: `provider`, `configFile`, `newDomain`, `currentVersion`) and
runs on the injected `scope` — `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by
`HttpGateway` and passed in, replacing the detached `CoroutineScope(Dispatchers.IO).launch` at
`:121`. Tests inject a `TestScope`, which is what makes `RemoteSyncRuleTest` assert "exactly one
push" rather than sleep.

**Server** (`configs/domain-sync-worker/index.js`): **unchanged.** Explicitly, **no domain
validation is added** — no denylist, no plausibility check, no suffix test. The worker's checks stay
what they are: method (`:49-51`), optional shared secret (`:58-63`), required fields (`:69-74`), and
the provider-file-name allowlist (`:11-34`, `:78-83`) which is also the path-traversal fix for
`configs/${configFile}` at `:99`. Rule S is the whole defence against a bad push, and it lives on
the client because only the client can observe behaviour.

---

## 5. Deletions and callers

| Item | LOC | Callers to update |
|---|---|---|
| `session/SessionProvider.kt` (whole file) | 171 | `HttpGateway.kt:13,:94,:95,:117,:123,:126`; `BaseProvider.kt:401,:406,:408`; stale comment `extractors/SavefilesExtractor.kt:32`. **Verified**: every other caller listed in the waves doc (`:532`) and third-eye §3 already went in Waves 2-4b — `grep -rn SessionProvider --include='*.kt' .` returns only those files plus the definition. CimaNow is already clean (`wave-4b-2-review.md` F6) |
| `session/SessionState.kt` | 29 | `HttpGateway.kt:12,:57` and the 12 `sessionState.domain` reads; doc comments `CfBypassEngine.kt:18`, `VideoSnifferEngine.kt:40`, `RequestQueue.kt:23` |
| `DomainManager` denylist + validator + guards (`:98-102,:113-117,:149-177`) | 40 | `HttpGateway.kt:481` |
| `HttpGateway.updateDomain / checkAndUpdateDomain / rewriteUrlIfNeeded / extractDomain` (`:109-127,:767-796,:842-846`) | 55 | internal |
| `HttpGateway` top-level `rewriteHost` incl. its caveat KDoc (`:944-965`) | 22 | `UrlRewriteTest.kt` (§6) |
| `documentNoSolve` validation guard (`:474-490`) | 17 → 1 | internal |
| `allowedDomains` plumbing (`HttpGateway.kt:70-71,:425,:709,:742`; `RequestQueue.kt:35,:119-132,:198`) | 22 | `RequestQueueTest.kt:51` |
| `RequestQueue.rewriteFollowerUrl` + `extractDomain` (`:290-304`) | 16 | internal |
| `BaseProvider.handleDomainDifference` (`:389-417`) | 29 | none (zero callers) |
| `ProviderConfig.trustedDomains` (`:25-26`) | 2 | none |
| `CfBypassEngine` `allowedDomains` param + `:186-192` | 14 | `HttpGateway.kt:742` |
| **Total deleted** | **≈ 414** | |
| Added: `DomainRules.kt` + `DomainManager` rules & persistence | ≈ 150 | |
| Added: three test classes | ≈ 230 | |

---

## 6. Test plan

Host: `FaselHDV2Provider/src/test` (JVM, JUnit 4, `mockwebserver` at
`FaselHDV2Provider/build.gradle.kts:24` — not needed by any of these; all three classes are pure).

New pure seam: `domain/DomainRules.kt` (`hostOf`, `adoptedHost`, `rewrite`,
`replaceHostComponent`) plus a `DomainStore` interface (`get/put` over the two prefs keys) so
`DomainManager`'s rules, history cap, migration and sync trigger are exercised with an in-memory map
and a `TestScope`. Only the `SharedPreferences` implementation of `DomainStore` touches Android.

| Class | Method | Asserts |
|---|---|---|
| `DomainAdoptionTest` | `adoptsOn2xxDocumentRequest` | `Success(200)` on a new host changes `currentDomain` and appends both the new and the outgoing host to `hostHistory` |
| | `doesNotAdoptOnCloudflareBlocked` | a `CloudflareBlocked` outcome whose `finalUrl` is on `cloudflare.com` leaves the domain unchanged; the test asserts no denylist is consulted by also passing a 200 challenge **body** on the same host and getting the same answer |
| | `doesNotAdoptOnNon2xx` | 403, 500, `Transport`, `Cancelled` and a `Success(302)` all leave it unchanged (the 302 case pins the 2xx tightening of §2.1) |
| | `doesNotAdoptFromExtractorOrImageRequest` | the same `Success` with `RequestKind.Extractor` / `Image` / `Subresource` changes nothing |
| | `adoptsMetaRefreshTargetOnlyAfterItPasses` | adoption is driven by the followed fetch's outcome: a CF-flagged follow-up leaves the domain on the origin host, a clean one moves it |
| | `adoptingTheSameHostTwiceKeepsOneHistoryEntry` | history is a set in content and a list in order; the cap evicts oldest-first |
| `HostHistoryRewriteTest` | `rewritesKnownOldHost` | host in history → rewritten to the current domain |
| | `leavesThirdPartyHostAlone` | a CDN host absent from history is untouched |
| | `wwwIsADistinctHost` | `www.x.com` is untouched unless `www.x.com` itself is in history |
| | `rewritesHostComponentNotQueryString` | `https://old.x/p?u=https://old.x/q` → `https://new.x/p?u=https://old.x/q`. **Closes the bug** at `HttpGateway.kt:958-961` |
| | `preservesPortAndScheme` | `http://old.x:8443/a?q=1#f` keeps scheme, `:8443`, path, query, fragment; percent-escapes in the query survive byte for byte |
| | `leavesCurrentDomainAndUnparseableUrlsAlone` | host == current, and a `lazy://name` style URL with no host, both pass through |
| `RemoteSyncRuleTest` | `noSyncAfterOneSuccess` | one adoption → zero pushes |
| | `syncAfterSecondSuccessWithOldHostFailed` | two adoptions + a `noteFailure` on the previous domain → exactly one push, with the expected payload |
| | `noSyncWhenOldHostNeverFailed` | two adoptions alone → zero pushes |
| | `remoteConfigValueIsNeverPushedBack` | the init-time remote domain does not count as an adoption |
| `SharedHasNoNameBasedDomainLogicTest` | `noAliasOrDenylistSymbolsSurvive` | acceptance criterion 1 as a test, not a manual grep: walks `shared/src/main` (and the provider modules) exactly as Wave 4c's `SharedHasNoSiteNamesTest` does (`FaselHDV2Provider/src/test/.../webview/SharedHasNoSiteNamesTest.kt:20-45`, same `repoRoot()` climb and same `assumeTrue` skip) and fails on `areDomainsRelated`, `domainAliases`, `MULTI_PART_TLDS`, `trustedDomains`, `allowedDomains`, `DENYLISTED_HOSTS`, `isValidProviderDomain`, `removePrefix("www.")` |
| `DomainMigrationTest` | `preWave6InstallSeedsHistoryAndRestartsFromFallback` | `domain` present, `host_history` absent → history contains the old value, current domain is the fallback, and the first clean document adopts the real host (acceptance 6, no name check anywhere) |

**Existing tests that change.**

- `core/UrlRewriteTest.kt:30-36` `alsoRewritesTheOldHostInsideTheQueryString_currentBehaviour`
  **flips** — it pins the substring behaviour this wave removes. It is deleted and replaced by
  `HostHistoryRewriteTest.rewritesHostComponentNotQueryString`, which asserts the opposite. The
  KDoc at `:23-28` (which names this wave) goes with it.
- `UrlRewriteTest`'s other `rewriteHost` cases (`:15-21,:38-57`) move to `HostHistoryRewriteTest`
  against `DomainRules.rewrite` with an explicit history; the `buildUrl` cases (`:59-75`) stay.
- `queue/RequestQueueTest.kt:39,:51,:58,:252`: the fake `Host` gains `adopt`, `noteFailure` and
  `rewrite`, loses `onDomainRedirect`, and `solveCloudflare` loses `allowedDomains`. The
  `adoptRedirectedDomain` switch becomes "the fake adopts on `adopt(outcome)`", which is what the
  leader/follower ordering test at `:252` actually needs to prove.

---

## 7. Commit split, risks, rollback

**6-1 — pure rules + tests, nothing wired.** Add `DomainRules.kt`, `DomainStore`, the new
`DomainManager` members (`hostHistory`, `adopt`, `rewrite`, `noteFailure`, `scope`), the four test
classes. Keep `isValidProviderDomain`, `updateDomain`, `syncToRemote`, `SessionProvider`,
`allowedDomains` and `trustedDomains` in place as thin adapters/no-ops so every current caller still
compiles and behaviour is unchanged on device. Green build, green tests, zero behaviour delta.

**6-2 — wire and delete.** Flip the decision sites of §2.5; delete `SessionProvider.kt`,
`SessionState.kt`, the denylist and validator, `trustedDomains`, `allowedDomains` (gateway + queue +
`CfBypassEngine`), `handleDomainDifference`, `rewriteUrlIfNeeded`, `checkAndUpdateDomain`, both
`extractDomain` copies and `rewriteHost`; add the migration; extend `RequestKind`; correct the
`SystemCookieJar` KDoc. This is the behavioural commit.

**6-3 — sync rule + docs.** Rule S on the injected scope, `RemoteSyncRuleTest`, and the doc updates:
`docs/README.md:41-49` fact 4 rewritten from "keep the denylist" to the behavioural rule,
`search-architecture.md:48-50` phases 1 and 3 marked superseded, waves doc Wave 6 Result block,
`shared-refactor-progress.md` acceptance greps. Worker file untouched, with a one-line comment
recording that no domain validation is added by design.

**Risks.**

| Risk | Mitigation |
|---|---|
| A site whose main page legitimately serves a 2xx page with CF markers in the body (a CF-hosted site with a `cf_chl` string in an inline script) can never be adopted | the marker list is `CloudflareDetector.CF_HTML_PATTERNS`, already the gate for CF-solve decisions everywhere; if a false positive shows up in a log the fix is that list, one place |
| History empty on a fresh install → nothing is ever rewritten until the first adoption | the migration/first-init seeds history with `fallbackDomain`, which is the host every provider starts on |
| Dead sibling hosts (§2.8) stop resolving | the one accepted regression; two options documented, neither shipped |
| Exact-host queue keying splits `www.x` / `x` leaders | at most one extra solve; correct under R3 |
| Migration drops a healthy install's newer adopted domain for one launch | it is re-adopted on the first redirect, and the old value stays in history so links keep working. Q4 |
| Wave 4c is moving `NavigationEngine` | this wave touches `NavigationEngine` in **no** commit; row 18 is deferred to Q5 |

**Rollback.** Ship 6-2 behind one boolean (`ProviderConfig`-level or a `shared` constant,
`ADOPT_BEHAVIOURAL = true`) whose false branch restores the old `checkAndUpdateDomain` adoption on
any host change. Rule H, the deletions and the sync rule are **not** behind the flag — only the
adoption rule is, because it is the only behavioural change with device-visible risk. Delete the flag
after one release, per the waves doc (`:618-620`). Do not ship in the same release as Wave 4.

---

## 8. Open questions for the owner

1. **History cap**: 8 hosts, oldest-evicted? A provider that moves domain monthly holds ~8 months of
   rewritable link history at ~30 bytes each. Larger is nearly free; smaller starts breaking old
   cached links.
2. **`www.` in history**: R3 says `www.x` and `x` are two hosts, so a link on `www.x` is rewritten
   only if `www.x` is itself in history. Confirm — this is the one place where the strict reading has
   a visible cost on a site that mixes both forms in its own HTML.
3. **Keep the syntactic host check** (`hostOf`: non-blank, ASCII, contains a dot)? It decides nothing
   about which site, and it stops `""`, a provider display name and a `lazy://name` URL from becoming
   a "domain". Recommend keep.
4. **Migration** (§3): drop the pre-Wave-6 persisted domain once — self-heals every poisoned install
   including providers with no `githubConfigUrl`, at the cost of one re-adoption on a healthy install
   — or keep it and rely on remote config, leaving a permanent hole for config-less providers?
5. **`NavigationEngine.allowedDomains`** (row 18): the waves doc schedules its deletion, but it has
   live `CimaNowProvider` callers passing literal host lists as a navigation lock, which reads like
   the worker's `configFile` allowlist (who may navigate) rather than a domain decision. Delete it as
   written, or keep exact-host membership and delete only the `endsWith(".$d")` subdomain widening?
