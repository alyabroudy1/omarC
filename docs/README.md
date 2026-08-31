# omarC / CloudStream fork — engineering docs

Durable notes for work that is expensive to re-derive. Written 2026-08-31.

## Repo layout (easy to get wrong)
- **App**: `/Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2` — a customized fork of
  [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream), branch `develop`,
  versionName **4.6.2** / versionCode 69, based on upstream **v4.6.0**.
- **Plugins**: `omarC/` is a **separate nested git repo** inside the app repo (~40 provider modules
  + a `shared/` library compiled into each plugin).
- Consequence: `shared/` code (e.g. `ChromiumFetcher`) ships inside each **plugin `.cs3`**, NOT in the
  app APK. The APK's install date tells you nothing about whether a `shared/` fix is live on a device —
  check the plugin build/download date instead.

## Build commands
| What | Command | Notes |
|---|---|---|
| All plugins | `cd omarC && ./gradlew compileDebugKotlin -q` | silent = success; builds all provider modules |
| App | `./gradlew :app:compilePrereleaseDebugKotlin` | variants are `prerelease`/`stable`; plain `compileDebugKotlin` is **ambiguous** and fails |

## Documents
| Doc | Contents |
|---|---|
| [search-architecture.md](search-architecture.md) | Search subsystem: lazy/Cloudflare placeholder flow, domain poisoning root cause, ChromiumFetcher 403 investigation, search pagination design, football-provider search gating. Includes the original task list and all fix phases. |
| [upstream-upgrade-assessment.md](upstream-upgrade-assessment.md) | Whether/how to adopt the latest upstream pre-release without breaking fork customizations. Commit gap, collision set, dry-run conflict probe, plugin-ABI verdict, recommended strategy. |

## Load-bearing facts worth not re-deriving
1. **Plugin ABI is stable v4.6.0 → upstream pre-release (2026-08-28).** All plugin-facing `MainAPI`
   methods (`search(query,page)`, `search(query)`, `quickSearch`, `load`, `loadLinks`, `getMainPage`)
   are byte-identical — same signatures, same line numbers. Upgrading the app does not, by itself,
   break the omarC providers.
2. **Upstream has NOT fixed the `bundleSearch()` `hasNext` bug** (still
   `ExpandableSearchList(list, 1, false)` at `pre-release:SearchViewModel.kt:169`). Our fork's fix is
   ahead of upstream on that file and is a candidate to upstream as a PR. Expect a conflict there on
   any future merge — keep ours.
3. **Search has two independent UI modes** with different pagination paths — see
   search-architecture.md. Per-provider rows already paginated; the merged grid never did.
4. **A poisoned provider domain persists across restarts** and can be pushed to the remote config
   worker. `DomainManager.isValidProviderDomain()` now denylists `*.cloudflare.com` and self-heals on
   init; keep that guard in place through any refactor.
5. Recommended upstream strategy: **cherry-pick, not merge**. The 14 real conflicts are not the cost —
   validating 642 non-conflicting commits (including a live KMP/kotlinx.serialization migration)
   against a heavily customized player/result layer is.
