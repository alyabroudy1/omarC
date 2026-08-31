# Upstream Upgrade Assessment — cloudstream-standard-v2 vs recloudstream/cloudstream

Date: 2026-08-31. Repo: `/Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2`, branch `develop` (versionName 4.6.2, versionCode 69).
All work below is read-only: `git remote add upstream`, `git fetch`, `git show/diff/log`, `git merge-base`,
`git commit-tree` (writes objects only, no refs/working-tree touched), `git merge-tree --write-tree` (dry-run, no commit/checkout).
**No branch switch, no working-tree edits, no commits were made by this assessment.**

## 0. Setup notes

- Added `upstream = https://github.com/recloudstream/cloudstream.git` (was absent) and ran `git fetch upstream --tags`.
- The fork's local tags `pre-release` and `v4.6.0` already existed (pointing at fork history, not upstream's), so upstream's tags of the same name were fetched into private refs to avoid clobbering anything: `refs/upstream-tags/pre-release`, `refs/upstream-tags/v4.6.0`, `refs/upstream-branches/master`.
- **Important discovery**: `develop`'s history is a squashed/imported history (33 commits total, first commit `14396eb7 "base app"`), not a real fork with shared ancestry with upstream. `git merge-base develop upstream/master` returns nothing (unrelated histories). However `14396eb7`'s tree is content-identical to upstream tag `v4.6.0` for `app/`+`library/` (diff is 7 stray `.idea/` files only) — confirming the fork was created by importing a snapshot of v4.6.0, not by `git clone`+branch.
- Because of this, a synthetic ancestor was constructed purely with `git commit-tree` (no ref changes) — one commit whose tree = `14396eb7`'s tree and parent = the real upstream `v4.6.0` commit, then a synthetic commit whose tree = `develop`'s tree and parent = that — solely so `git merge-tree` could compute a real 3-way merge against the correct base. This produced a genuine dry-run conflict list (section 5).

## 1. Customization surface (base `14396eb7` → `develop`, `app/`+`library/`, excluding `build/`)

**69 files changed, 7206 insertions(+), 101 deletions(-)** (`git diff --stat 14396eb7 develop -- app/ library/`).

By area:

| Area | Files | Notes |
|---|---|---|
| **Chromecast / DLNA / cast-to-app** | 22 new files under `app/src/main/java/com/lagradost/cloudstream3/cast/**` (~4,700 lines) | Entirely new subsystem: `CastSessionManager.kt` (273), `StreamRelayServer.kt` (877), `DlnaCastSession.kt` (482), `DlnaDeviceDiscovery.kt` (351), `CloudStreamCastSession.kt` (290), `CloudStreamCastReceiver.kt` (283), `GoogleCastSession.kt` (378), `CastControllerFragment.kt` (359), plus discovery/actions/UI helpers. This is additive — no upstream files exist at these paths, so **zero merge risk** from upstream, but upstream will not know about the new `cast/` package either. |
| **YouTube in-app player** | `YouTubePlayerActivity.kt` (472 new lines) + `youtube_player_activity.xml` layout (242) | New, additive. |
| **Search** | `SearchViewModel.kt` (+69), `SearchHelper.kt` (+85/-heavy), `SearchFragment.kt` (currently mid-edit, uncommitted — see §6) | Lazy/Cloudflare-bypass search placeholder feature (`resolveLazySearch`, `LAZY_SEARCH_PREFIX`) plus in-progress `hasNext`/pagination fix. |
| **Player** | `CS3IPlayer.kt` (+61/-...), `FullScreenPlayer.kt` (+20) | Cast integration hooks into the player. |
| **Result screen** | `ResultViewModel2.kt` (+229), `ResultFragmentPhone.kt` (+76), `ResultFragmentTv.kt` (+126), `EpisodeAdapter.kt` (+24), `LinearListLayout.kt` (+45) | Cast button/state wiring, layout tweaks. |
| **Home** | `HomeParentItemAdapter.kt`, `HomeChildItemAdapter.kt`, `HomeScrollAdapter.kt`, `HomeParentItemAdapterPreview.kt` | Small (4-8 line) hooks, likely for cast affordance. |
| **App shell** | `MainActivity.kt` (+81/-...), `AndroidManifest.xml` (+12), `CastOptionsProvider.kt` (new-ish, +9), `settings_general.xml` | Cast session lifecycle wiring into the activity. |
| **Plugins/updater** | `RepositoryManager.kt` (+11, additive `addPrebuiltRepository`), `PackageInstaller.kt` (+31), `InAppUpdater.kt` (+4), `ExtensionsFragment.kt` (+4) | Custom repo/updater config pointing at the fork's own release feed. |
| **Build config** | `app/build.gradle.kts` (versionCode/Name, debug signingConfig on release, `cronet.embedded` dep, dokka `remoteUrl`), `library/build.gradle.kts` (dokka `remoteUrl` only) | Small, isolated edits. |
| **Utils** | `AppContextUtils.kt` (+96), `VideoClickAction.kt` (+6) | Helper additions (search filtering, cast-related). |

**Heaviest files by fork-side line count**: `StreamRelayServer.kt` (877, new), `DlnaCastSession.kt` (482, new), `YouTubePlayerActivity.kt` (472, new), `GoogleCastSession.kt` (378, new), `CastControllerFragment.kt` (359, new), `DlnaDeviceDiscovery.kt` (351, new), `ResultViewModel2.kt` (229, modified existing file), `CloudStreamCastReceiver.kt` (283, new), `CloudStreamCastSession.kt` (290, new).

**Bottom line**: the customization surface is dominated by an all-new Cast/DLNA subsystem (additive, no upstream collision) plus surgical hooks into ~15 existing upstream files (search, player, result, home, app shell, plugin/updater config).

## 2. Latest upstream releases

Via `gh release list --repo recloudstream/cloudstream --limit 15` (authenticated as `alyabroudy1`):

| Tag | Label | Date | Type |
|---|---|---|---|
| **`pre-release`** | Pre-release Build | **2026-08-28** | **Latest pre-release** (rolling tag, tip of `master`, commit `efc1915f` "Merge PR #3111 weblate") |
| `v4.8.0` | July Update / Latest | 2026-07-10 | Latest **stable** |
| `v4.7.0` | March Update | 2026-03-29 | Stable |
| `v4.6.0` | October Update | 2025-10-15 | **Fork's base** |

The fork is **not just behind pre-release — it's missed two full stable releases (4.7.0, 4.8.0)** in addition to everything since.

## 3. Gap quantification (`v4.6.0` → `pre-release`)

- Commits: **739 total**, **642 touching `app/`+`library/`**.
- Diff: **561 files changed, 49,621 insertions(+), 22,553 deletions(-)** in `app/`+`library/` (excluding `build/`).
- Fork customized **69** files in the same trees.

### Collision set (files touched by BOTH the fork and upstream since v4.6.0) — 31 files, ranked by combined churn

| Fork Δlines | Upstream Δlines | File |
|---:|---:|---|
| 20 | 1668 | `ui/player/FullScreenPlayer.kt` |
| 61 | 765 | `ui/player/CS3IPlayer.kt` |
| 229 | 482 | `ui/result/ResultViewModel2.kt` |
| 24 | 641 | `ui/result/EpisodeAdapter.kt` |
| 4 | 616 | `utils/InAppUpdater.kt` |
| 76 | 475 | `ui/result/ResultFragmentPhone.kt` |
| 126 | 225 | `ui/result/ResultFragmentTv.kt` |
| 96 | 189 | `utils/AppContextUtils.kt` |
| 8 | 236 | `res/values/strings.xml` |
| 4 | 226 | `ui/settings/extensions/ExtensionsFragment.kt` |
| 8 | 221 | `app/build.gradle.kts` |
| 11 | 201 | `plugins/RepositoryManager.kt` |
| 2 | 126 | `library/build.gradle.kts` |
| 8 | 116 | `ui/home/HomeParentItemAdapterPreview.kt` |
| 69 | 55 | `ui/search/SearchViewModel.kt` |
| 81 | 36 | `MainActivity.kt` |
| 12 | 93 | `AndroidManifest.xml` |
| 85 | 4 | `ui/search/SearchHelper.kt` |
| 32 | 49 | `res/layout/fragment_result_tv.xml` |
| (+13 more, smaller) | | layouts, `HomeScrollAdapter.kt`, `HomeChildItemAdapter.kt`, `HomeParentItemAdapter.kt`, `PackageInstaller.kt`, `VideoClickAction.kt`, small layout files |

"Touched by both" ≠ "textual conflict" — see §5 for the real conflict count.

## 4. Plugin-facing API risk (`MainAPI.kt`, plugin loader)

- `library/.../MainAPI.kt`: **802 lines touched (341+/461-)** between v4.6.0 and pre-release — the single largest churn in the plugin-facing surface. Cause: **the library module has become a full Kotlin Multiplatform module** (`commonMain` source set now exists as the canonical location — it didn't structurally change path here but internals did), migrating away from `java.util.Date`/`SimpleDateFormat` to `kotlinx-datetime` (`LocalDate`, `Instant`), and beginning a **Jackson → kotlinx.serialization migration** (new `@InternalAPI`, `@UnsafeSSL`, `@SkipSerializationTest` annotations; `Coroutines.threadSafeListOf` → `Coroutines.atomicListOf`).
- **Backward compatibility is deliberately preserved for the load-bearing bits**: all 9 `abstract`/`open fun` signatures on `MainAPI` (the ones plugins override — `load`, `search`, `quickSearch`, `getMainPage`, etc.) are **byte-for-byte identical** in v4.6.0 vs pre-release (diff of extracted signatures is empty).
- Old helper `fun Episode.addDate(date: Date?)` is kept as a `@Deprecated(level = WARNING)` overload alongside new `addDate(LocalDate?)` / `addDate(Instant?)` — plugins compiled against v4.6.0 keep compiling, just with a deprecation warning.
- One notable type change: `MainAPI.apis` public var changed from `List<MainAPI>` to `AtomicList<MainAPI>` — low risk unless a plugin assigns to it directly (uncommon).
- **Verdict: MainAPI ABI is not broken for the omarC providers.** The overload surface plugins compile against is unchanged; only internal implementation and a few peripheral/deprecated helpers moved. Still recommend compiling 2-3 representative omarC providers against the new `MainAPI.kt`/library jar before committing to an upgrade, given the scale of internal churn (serialization migration in particular could subtly change JSON (de)serialization defaults used by `parsedSafe`/`AppUtils.toJson` helpers plugins rely on).
- Plugin loader `plugins/PluginManager.kt`: heavily changed upstream (197 lines, 105+/92-) but **the fork never modified this file** (clean diff, zero collision) — so it merges from upstream cleanly with no fork logic to reconcile.
- `APIRepository.kt`: upstream touched it lightly (20 lines) and the fork never touched it — clean.

## 5. Dry-run merge conflict probe (`git merge-tree --write-tree`, real 3-way merge, aborted — no commit made)

Using the synthetic-ancestor technique in §0, computed merge-base correctly resolved to the real upstream `v4.6.0` commit. Result: **29 total files with textual conflicts** (28 in `app/`+`library/`, 1 in `gradle/libs.versions.toml`).

Of the 28 in `app/`+`library/`, splitting by whether the fork actually customized the file:

**Real conflicts — fork customized these AND upstream also changed overlapping regions (14 files):**
```
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeScrollAdapter.kt
app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt
app/src/main/java/com/lagradost/cloudstream3/ui/player/FullScreenPlayer.kt
app/src/main/java/com/lagradost/cloudstream3/ui/result/EpisodeAdapter.kt
app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragmentPhone.kt
app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragmentTv.kt
app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchHelper.kt
app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt
app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt
app/src/main/res/layout/fragment_search_tv.xml
app/src/main/res/navigation/mobile_navigation.xml
app/src/main/res/values/strings.xml
```
Plus `gradle/libs.versions.toml` — expected: fork hardcodes `versionCode = 69` / `versionName = "4.6.2"` while upstream pre-release moved these into the version catalog and added new plugin aliases for the KMP/Android-library-plugin rework — trivial but mandatory manual resolution.

**Spurious conflicts — the fork never touched these files; the conflict is a pure CRLF/LF artifact (14 files):**
```
app/src/main/java/com/lagradost/cloudstream3/actions/temp/CloudStreamPackage.kt
app/src/main/java/com/lagradost/cloudstream3/syncproviders/{AccountManager,AuthRepo,SubtitleRepo,SyncAPI}.kt
app/src/main/java/com/lagradost/cloudstream3/ui/download/DownloadAdapter.kt
app/src/main/java/com/lagradost/cloudstream3/ui/player/{RoundedBackgroundColorSpan,UpdatedDefaultExtractorsFactory,UpdatedMatroskaExtractor}.kt
app/src/main/java/com/lagradost/cloudstream3/ui/settings/LogcatAdapter.kt
app/src/main/java/com/lagradost/cloudstream3/utils/{BackPressedCallbackHelper,ImageUtil,SubtitleUtils}.kt
library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/HlsPlaylistParser.kt
```
Root cause confirmed: `git show <v4.6.0>:<file> | file -` reports `CRLF line terminators` for the real upstream commit and pre-release, but the fork's imported base (`14396eb7`) normalized these to LF during the snapshot-import — so on files the fork never edited, git still sees "ours changed" (line endings) vs "theirs changed" (real content) and flags a conflict, even though there's no actual semantic disagreement. **These 14 are not real risk** — they resolve by taking upstream's content and re-normalizing line endings (or by running the real merge with `-Xrenormalize`/a `.gitattributes` `text=auto` pass first).

Also worth noting: several files in the §3 collision table (`ResultViewModel2.kt`, `MainActivity.kt`, `PackageInstaller.kt`, `SearchViewModel.kt`) were touched by **both** sides but merged **cleanly** in the dry run (non-overlapping hunks) — "collision" in the file-list sense overstates true conflict risk; the §5 conflict list is the ground truth.

## 6. In-flight local work (found, not caused by this assessment)

At the time of this probe, `git status --short -- app/ library/` showed **uncommitted local edits** to:
- `app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchFragment.kt`
- `app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchViewModel.kt`

These are **not from this assessment** — every git operation used here (`fetch`, `show`, `diff`, `merge-base`, `commit-tree`, `merge-tree --write-tree`) is read-only w.r.t. the working tree and index. The diff content (adding an `RecyclerView.OnScrollListener` that calls `searchViewModel.expandAllAndReturn()` when `hasNext` is true and the merged grid isn't scrollable) is exactly the locally-in-progress `bundleSearch()`/multi-provider-pagination fix referenced in the task — apparently edited concurrently (by the user or another process) while this assessment ran. It is left untouched.

## 7. Does upstream's pre-release already fix the `bundleSearch()` hasNext limitation?

**Partially/architecturally, not by fixing the function itself.** In upstream pre-release's `SearchViewModel.kt`:

```kotlin
private fun bundleSearch(lists: MutableMap<String, ExpandableSearchList>): ExpandableSearchList {
    if (lists.size == 1) {
        return lists.values.first()   // real hasNext preserved only for a single active provider
    }
    ...
    return ExpandableSearchList(list, 1, false)   // hasNext hardcoded false when merging >1 provider
}
```
This is **the same limitation the fork is fixing** — `bundleSearch` still can't page a merged multi-provider list. What upstream added *instead* is a **per-provider expandable-section model**, mirrored from the home screen:

```kotlin
suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
    ...
    val next = repo.search(query, nextPage)
    ...
    return HomeViewModel.ExpandableHomepageList(HomePageList(name, item.list), item.currentPage, item.hasNext)
}
```
i.e., upstream's UI now (or is moving toward) showing search results as **per-provider expandable rows** (like the home page's "load more" sections) rather than relying on one global merged/paginated list — sidestepping the hasNext-of-a-merge problem architecturally instead of solving it in `bundleSearch`. The fork's in-progress fix (§6, `expandAllAndReturn()` + `SearchAdapter.hasNext`) is a **different, more minimal approach**: keep the single merged grid but track `hasNext` on the adapter and trigger expansion on scroll. **Worth reviewing upstream's `expandAndReturn`/`ExpandableHomepageList` pattern before finishing the local fix** — adopting it would make a future upstream merge on `SearchViewModel.kt`/`SearchFragment.kt` much less painful, since it would align structurally with where upstream is headed, but it's a larger UI change (per-provider rows vs. a single grid) than the local scroll-listener fix.

## 8. Recommendation

**Recommended: (b) Cherry-pick / selective adoption, not a full merge — at least for now.**

| Option | Effort | Risk | Notes |
|---|---|---|---|
| (a) Full merge of `pre-release` | High (3-6 days) | Medium-high | Real work is 14 genuine conflicts (manageable, mostly small/localized) + 14 spurious line-ending conflicts (mechanical) + `libs.versions.toml` (trivial). The **actual** cost isn't conflict count, it's **validating 642 non-conflicting upstream commits touching `app/`+`library/`** including a library-module KMP/serialization migration, before trusting the merged tree compiles and the 100+ omarC provider plugins still load. Also inherits two full upstream release cycles (4.7.0, 4.8.0) of behavior change at once. |
| (b) Cherry-pick selected fixes | Medium (1-3 days per batch) | Low-medium | Pull only what's wanted (bug fixes, the `expandAndReturn` search pattern, specific player/result fixes) onto current `develop`, skip the KMP/serialization rework entirely until it's unavoidable. Keeps the Cast/DLNA subsystem and all other customizations completely undisturbed since those files have zero upstream overlap. |
| (c) Stay put | None | Rising | Gap keeps growing (already 2 stable releases + 739 commits behind); eventually a security/API fix will be needed and the eventual merge only gets harder. |

**Ordered plan for (b):**
1. Fix `libs.versions.toml`/`app/build.gradle.kts` versioning conflict manually (5 min) — decide once whether to keep hardcoded versionCode/Name or adopt the catalog pattern.
2. Cherry-pick or manually port the upstream `SearchViewModel.kt` `expandAndReturn`/`ExpandableHomepageList` pattern into the search fix already in flight (§6/§7) rather than finishing the current scroll-listener approach in isolation — reduces future merge pain on this exact file.
3. For the 14 "real conflict" files (§5), pull upstream's version and manually re-apply the fork's small hooks (all are additive/localized: cast wiring, lazy-search, updater config) — re-diff each file's fork-only hunk against upstream's new version rather than a blind 3-way merge.
4. Skip/defer the library module's KMP + Jackson→kotlinx.serialization migration (all of upstream's `MainAPI.kt`/`library/` churn) unless/until a specific fix in it is needed — it's internal-only for plugins (§4) so there's no urgency, and it's the highest-blast-radius part of the gap.
5. Verify: (i) `./gradlew :app:assembleDebug` builds; (ii) build `:library` and confirm 2-3 representative omarC provider plugins still compile/load against the resulting `MainAPI`/library jar (the ABI surface plugins touch); (iii) smoke-test search, cast, and result screens manually since those are the customized+colliding areas.
6. Re-run this same gap assessment against the *next* stable/pre-release before it grows further — treat it as a recurring quarterly task rather than a one-time catch-up.

Full merge (a) should be revisited once the KMP/serialization migration lands in a stable release and stabilizes — merging into a moving migration target now maximizes rework.
