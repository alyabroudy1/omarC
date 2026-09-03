# Wave 0 review (commit `35753f59`)

Reviewer: independent third eye. Read-only review of the commit against `docs/shared-refactor-waves.md`
section "Wave 0" as committed in `35753f59`, plus owner rules R1 (unified UA), R2 (unified HTTP
fingerprint), R3 (no domain-name heuristics), no overengineering, no guessing. Every line reference
below is to the file as it exists in `35753f59` (`git show 35753f59:<path>`). Nothing was executed;
the working tree carries uncommitted Wave 1 work, so a build or test run would not describe this commit.

## Verdict

**Accept with fixes.** No blocker. Three small worker fixes and one one-line provider fix; the rest is
notes to carry forward.

## Findings

| # | Severity | File:line | Issue | Proposed minimal fix |
|---|---|---|---|---|
| 1 | Medium | `configs/domain-sync-worker/index.js:56` and `shared/.../domain/DomainManager.kt:123-128` | The secret is opt-in server-side, but the client never sends `X-Sync-Secret` (the payload at `DomainManager.kt:123-128` has `provider`, `configFile`, `newDomain`, `currentVersion` only). Setting `SYNC_SECRET` on the Worker silently breaks domain sync for every installed plugin; the client logs the failure at `DomainManager.kt:139` and moves on. Spec said "require a shared-secret header"; acceptance criterion 5 says a missing secret returns 4xx. As shipped, criterion 5 is only true when an operator sets the variable, and doing so is destructive. | Keep the code. Change the Wave 0 acceptance text to "when `SYNC_SECRET` is set", and add one line to the worker header comment: "Do not set `SYNC_SECRET` until the client sends the header (tracked for Wave 6)." |
| 2 | Low | `configs/domain-sync-worker/index.js:76` | `configFile.includes(...)` throws `TypeError` when the field is a non-string (a number, object, or array passes the truthiness check at `:67`). The `catch` at `:180` turns it into a 500 `Internal error` with `details: error.message`. Should be a 400. | Prepend `typeof configFile !== 'string' \|\|` to the condition at `:76`. |
| 3 | Low | `configs/domain-sync-worker/index.js:76` | `includes('/')` and `includes('..')` are redundant: `KNOWN_CONFIG_FILES.has(configFile)` already rejects anything not in the 22-name set. Harmless, but two dead checks are the kind of belt-and-braces the "no overengineering" rule targets. | Drop the two `includes` calls; keep the `Set` check and the comment. |
| 4 | Low | `configs/domain-sync-worker/index.js:87-89`, `:111`, `:161`, `:183` | Residual info in error bodies: the env-var 500 echoes `hasToken`/`hasOwner`/`hasRepo` booleans; GitHub fetch/update failures echo the raw GitHub API body; the generic 500 echoes `error.message`. Pre-existing, not introduced by this commit, and much smaller than the deleted debug GET. | Out of Wave 0 scope. Replace the three `details` fields with a fixed string when the worker is next touched. |
| 5 | Low | `Animewitcher/src/main/kotlin/com/animewitcher/animewitcher.kt:148` | `page=${page - 1}` with no clamp. `getMainPage` in the same file clamps: `(page - 1).coerceAtLeast(0)` at `:111`. If a caller ever passes `page = 0`, Algolia gets `page=-1`. Page 1 gives `page=0`, byte-identical to the old literal at old `:148`. | Use `(page - 1).coerceAtLeast(0)` at `:148`, matching `:111`. |
| 6 | Info | `Animeiat/src/main/kotlin/com/animeiat/Animeiat.kt:34,84` | `hasNext = items.isNotEmpty()` costs one extra empty-page request at the end. The API already returns `Pagination(current_page, last_page)` (`:34`), so an exact `hasNext` is available. Not required by the spec; the spec's own rule is "page until empty" (`BaseProvider.kt:181-184`). | Optional. Leave for a provider-level pass. |
| 7 | Info | `3isk/.../eishk.kt:46`, `Anim3rb/.../anim3rbProvider.kt:140`, `Animerco/.../AnimercoProvider.kt:67`, `ArabSeedProviderV4/.../ArabseedV4.kt:44`, `FaselHDV2Provider/.../FaselHDV2.kt:168`, `WecimaProvider/.../Wecima.kt:75` | `if (page > 1) return empty` inside `searchLazy` overrides is unreachable: `BaseProvider.search` only calls `searchLazy` when `page <= 1` (`BaseProvider.kt:213`). Harmless and self-documenting. | None. |
| 8 | Info | `FaselHDV2Provider/build.gradle.kts:26-30` | The mirror relies on the cloudstream Gradle plugin having already added its `files(jarFile)` to `compileOnly` when this `afterEvaluate` runs. The plugin source is not in the repo, so ordering is unverifiable by reading. If the order is wrong the symptom is a test-only `NoClassDefFoundError`, never a change to the shipped `.cs3` (nothing is added to `implementation` or `compileOnly`). | None now. If CI ever fails with `NoClassDefFoundError: MainAPI`, that is this. |
| 9 | Info | `.github/workflows/build.yml:15-16` | `paths-ignore: '*.md'` matches root-level `.md` only, so `docs/**` commits run both jobs. `configs/**` commits run neither, as before. Pre-existing. | None. |

## Spec compliance checklist

Acceptance criteria from `docs/shared-refactor-waves.md` (Wave 0, "Acceptance criteria").

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | `:FaselHDV2Provider:testDebugUnitTest` passes in CI on every push | Unverifiable (structure correct) | `build.yml:22-46` adds a `test` job at the repo root (no `path:`), `chmod +x gradlew` at `:43`, runs the task at `:44` and `check-injected-js.js` at `:47`; `build` has `needs: test` at `:51`. Not run here (see header). Runs only on `push` to `master`/`main` (`:8-13`), same trigger as before. |
| 2 | `grep -rn "postman-echo\|ENABLE_WEBVIEW_REMOTE_DEBUGGING = true" shared/` empty | Met | `git grep -i postman 35753f59 -- shared/` returns nothing. `NavigationEngine.kt:4145-4146` reads `ENABLE_WEBVIEW_REMOTE_DEBUGGING = DebugFlags.WEBVIEW_REMOTE_DEBUGGING`; `DebugFlags.kt:18` is `const val WEBVIEW_REMOTE_DEBUGGING = false`. The only call site is `NavigationEngine.kt:1134-1136`, guarded by that constant. No other path sets it. |
| 3 | No `open fun searchNormal(query: String)`; all 41 modules compile | Met / compile unverifiable | `BaseProvider.kt` diff deletes both one-arg overloads (old `:166`, `:257`). `git grep` over `35753f59` for `searchNormal(query)` / `searchLazy(query)` in `*.kt` finds only the test's doc comment. All 22 overrides now take `(query, page)` and return `SearchResponseList`. Compilation not run here. |
| 4 | Custom-search providers return results; page 2 differs from page 1 | Met for 4, by design for 18 | Paging threaded: Animewitcher `:148`, Animeiat `:78`, Cimalight `:175-176`, Cimawbas `:66,91` via `CimawbasParser.getSearchUrl` + `searchPaginationFormat = "&page=%d"` (`CimawbasParser.kt:75-80`). The other 18 return empty for `page > 1` before any network call, which the spec allows ("page > 1 returns empty where the site has no paging"). |
| 5 | Unknown `configFile` or missing secret returns 4xx and commits nothing | Met for `configFile`; conditional for secret | `index.js:76-81` returns 400 before the env check and the GitHub calls. Secret: `index.js:56-61` returns 401, but only when `SYNC_SECRET` is set (finding 1). |

Other spec items:

| Item | Status | Evidence |
|---|---|---|
| 22 provider files migrated | Met | 22 provider `.kt` files in `--stat`; each diff reviewed. |
| Page-1 behaviour byte-identical to the old override | Met for all 22 | Every diff keeps the same URL, same fetch call, same selector, same mapping, wrapping the list in `newSearchResponseList(list, hasNext)`. Paged four: Animeiat `:78` `if (page <= 1) "$mainUrl/anime?q=$query"` equals old line; Cimalight `:175` equals old line; Animewitcher `page=${page-1}` gives `page=0` at page 1, equal to old literal; Cimawbas `getSearchUrl(d, q, 1)` returns the 2-arg URL verbatim (`ParserInterface.kt:72-74`). Exception paths keep the old empty-list result (e.g. FaselHDV2 `:163`, Lodynet `:106`, Shahid4u `:226`, Wecima `:70`). |
| `page > 1` short-circuits before network where no paging | Met | Guard is the first statement in each non-paged override: 3isk `:46`, Anim3rb `:128,140`, Animerco `:62,67`, ArabseedV4 `:44,71`, CimaNow `:823`, FaselHDV2 `:115,168`, IPTV `:205`, Lodynet `:72`, Ohatv `:88`, Shahid4u `:202`, TVgarden `:27`, Topcinema `:131`, Tuniflix `:69`, Watanflix `:61`, Wecima `:64,75`, Witanime `:101`, Youtube `:62`, eseek `:71`. The providers whose `searchLazy` delegates to `searchNormal(query, page)` inherit the guard. |
| `hasNext` never true when items empty | Met | Non-paged: always `false`. Paged: `items.isNotEmpty()` at Animewitcher `:168`, Animeiat `:84`, Cimalight `:181`, Cimawbas `:79,100`. |
| Lazy-search contract (`searchLazy` throws `CloudflareBlockedSearchException`, does not swallow) | Met, unchanged | 3isk `:51` and eseek `:76` throw on null doc (unchanged lines). Anim3rb `standardSearch` rethrows at `:156-158`. ArabseedV4 `searchLazy` rethrows at `:58`. FaselHDV2 rethrows at `:213-214`. Cimawbas `:92` uses `getDocumentNoFallback`, which throws from `ProviderHttpService.kt:787`. Providers whose lazy path delegates to an `app.get`-based `searchNormal` (Animeiat, Animewitcher, Cimalight, Lodynet, Ohatv, Shahid4u, TVgarden, Topcinema, Tuniflix, Watanflix, Witanime, Youtube, IPTV, CimaNow) never threw before either. |
| Nothing still calls the deleted functions | Met | See criterion 3. `BaseProvider.kt:215` and `:244` call the paged overloads. |
| `search(query)` MainAPI override intact | Met | `BaseProvider.kt:201` `override suspend fun search(query: String) = search(query, 1).items`; `:203` paged override. |
| `DebugFlags` minimal | Met, one addition | `DebugFlags.kt` has two `const val`s. Spec asked for one (`DEBUG_DUMPS`). The second, `WEBVIEW_REMOTE_DEBUGGING`, exists so a JVM test can assert it without loading `NavigationEngine` (`DebugFlagsTest.kt:9-10`). Justified. |
| All disk writes gated by `DebugFlags.DUMPS` | Met | `git grep` over `35753f59 -- shared/` for `cacheDir|externalCacheDir|FileOutputStream|writeText|writeBytes` finds writes at `BaseProvider.kt:479-482` (inside `if (ctx != null && DebugFlags.DUMPS)` at `:478`) and `NavigationEngine.kt:513-523` (`:511`), `:808-811` (`:801`), `:1019-1026` (`:1018`), `:1063-1070` (`:1062`), `:2234-2237` (`:2233`), `:2349-2352` (`:2347`). Every `dlDir` secondary write sits inside the same gated `let` block. The freex rendered dump (`:801`) and the script dump (`:2347`) were not in the spec's line list and are gated anyway. |
| Header echo removed without dead code | Met | Field `echoDiagnosticDone` (old `:132-134`), its reset (old `:355`), the call block (old `:2399-2423`) and `HEADER_ECHO_URL` (old `:4220-4234`) are all deleted. `NavigationEngine` uses fully qualified `java.net.*` names, so no import is orphaned. `userAgent` remains used at `:1104`, `:1460`. |
| Worker allowlist equals `configs/*.json` | Met | `git ls-tree 35753f59 configs/` lists 22 `.json` files; `index.js:11-34` lists the same 22 names. |
| Worker: no domain validation (R3) | Met | The only new check is on `configFile`, a file name (`index.js:73-81`). `newDomain` is passed through untouched. |
| Worker: 405 for GET | Met | Debug GET deleted; `index.js:49-51` returns 405 for anything not POST after the OPTIONS branch. |
| Worker: secret not bypassable when set | Met | `request.headers.get('X-Sync-Secret') !== env.SYNC_SECRET` at `:56`; a missing header yields `null !== string`, so 401. Header lookup is case-insensitive per the Fetch spec. |
| CI `test` job wiring | Met | Root checkout (no `path`), JDK 17 and Android SDK actions mirror the build job, Node 20 for the JS check, `needs: test` at `:51`. `build` steps `:52-92` are unchanged apart from the `needs` line, so builds push as before. |
| `SearchPagingTest` tests behaviour | Met | `pagelessOverloadIsGone` (`:37-56`) reflects over `BaseProvider` for `(String, Continuation)` shapes and also asserts the 3-param shape exists (`:42-45`), so it cannot pass vacuously. `pageTwoUsesPaginationFormat` (`:64-83`) drives `ParserInterface.getSearchUrl` with and without a format and checks identity at page 1, difference at page 2, and the exact URL. Note it exercises `ParserInterface.kt:72-77`, which this commit did not change; it is a guard, not a test of new code. |
| `DebugFlagsTest` | Met, tautological by design | Asserts two `const val`s are `false` (`:15-24`). That is exactly the spec's row ("the `DEBUG_DUMPS` constant is `false`"). Its value is as a CI tripwire, not as logic coverage. |
| `afterEvaluate` testRuntimeOnly mirror | Met, simplest available | `build.gradle.kts:26-30` copies only `FileCollectionDependency` entries from `compileOnly` onto `testRuntimeOnly`. `testRuntimeOnly` is never packaged, so the shipped plugin is unchanged. The alternative (hard-coding the stub jar path) would break when the plugin's cache location changes. |
| R1 / R2: no new UA literal or header block | Met | The commit adds no `User-Agent` string in Kotlin. The only UA in the diff context is the pre-existing Witanime `headers = mapOf("User-Agent" to userAgent)` at `WitanimeProvider.kt:104` (unchanged line) and the pre-existing worker `'User-Agent': 'CloudStream-DomainSync-Worker'` at `index.js:104,148` (server side, unchanged). The `Access-Control-Allow-Headers` change at `index.js:44` is a CORS response header, not a request fingerprint. |
| R3: no domain-name logic touched | Met | `DomainManager.kt` is not in the commit. The `docs/README.md:44-50` edit records the D2 reversal as a note only. |

## Out-of-scope and over-engineering notes

- Docs: `docs/shared-architecture-review.md` (422 lines), `docs/shared-architecture-review-third-eye.md` (205), `docs/shared-refactor-waves.md` (663), `docs/README.md` (+10). Documentation only; the commit message declares it. No code impact.
- `DebugFlags.WEBVIEW_REMOTE_DEBUGGING` is one constant beyond the spec's single flag. Justified for testability (see checklist). Not over-engineering.
- Worker: the two `includes` checks at `index.js:76` are redundant with the `Set` (finding 3). Trivial.
- Redundant `page > 1` guards in `searchLazy` overrides (finding 7). Trivial and readable; leave.
- Cimalight `&page=` (`cimalight.kt:176`) and Animeiat `&page=` (`Animeiat.kt:78`) are the correct parameter names only if the sites accept them. Evidence in repo: Animeiat's own `getMainPage` uses `anime?page=$page` against the same API (`Animeiat.kt:65`); Cimalight shares the `search.php?keywords=` engine that `CimawbasParser.kt:79` documents as paging with `&page=N`. Plausible, not proven from the repo. Manual check on device is the spec's own verification step.
- CI runs JDK and Android SDK setup twice (once per job). Acceptable cost for job isolation; a single job with the test step first would be the leaner shape if CI minutes matter.

## Carry into Wave 1

1. Apply findings 2, 3 and 5 (three one-line edits). Reword acceptance criterion 5 per finding 1.
2. Decide the secret story before anyone sets `SYNC_SECRET`: either the client sends `X-Sync-Secret` (which an open-source plugin cannot keep secret, as the worker comment at `index.js:53-55` says) or the criterion is dropped and the allowlist is the whole defence. Ties to Wave 6 (behavioural domain adoption).
3. Animeiat exact `hasNext` from `Pagination.last_page` (finding 6) when that provider is next touched.
4. Manual verification from the spec is still owed: a FaselHD search showing the AJAX fallback log line, no `setWebContentsDebuggingEnabled` line, empty `externalCacheDir` after a failed load. This review could not run them.
5. `SearchPagingTest.pageTwoUsesPaginationFormat` guards `ParserInterface`, not `BaseProvider.searchNormal`. If Wave 1 adds a fake `ProviderHttpService` seam, a test that drives `BaseProvider.searchNormal(q, 2)` end to end would cover the actual regression class this wave fixed.
