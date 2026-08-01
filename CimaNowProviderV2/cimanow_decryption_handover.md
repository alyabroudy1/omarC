# CimaNow Watch Page Decryption Handover & Cheatsheet

> ## ✅ VERIFIED WORKING — 2026-07-30 17:00, end to end, 1080p
>
> A real run, from the log, after the whole day's work:
>
> ```
> 17:00:14.102  🎯 CAPTURED EMBED  listeamed.net/e/…            ← user browsing servers
> 17:00:16.759  🎯 CAPTURED EMBED  searchresultsworld.com/sr/…
> 17:00:22.425  🎯 CAPTURED EMBED  bysetayico.com/e/utib6riq2s3t
> 17:00:23.556  🎯 CAPTURED EMBED  q8y5z.com/4f8/utib6riq2s3t
> 17:00:12–27   ⏳ Waiting for a stream | embeds=1…6, streamCaptures=0, sinks=3
> 17:00:29.673  🎬 CAPTURED VIDEO URL  edge1-vienna-sprintcdn.ow…   ← playback proves the server works
> 17:00:30.634  Step 2: Done | embeds=6, playableStreams=2
> 17:00:31.273  >>> EMBED LINK q=1080 Bysetayico 1080p            ← ByseExtractor, 0.24 s
> 17:00:34.757  ⏭ Links already delivered — cancelling the remaining embed(s) after 3483ms
> 17:00:34.765  loadOnlinePlayer ExtractorLink(name=Bysetayico…)
> 17:00:35.479  Rendered first frame
> ```
>
> **1080p, four seconds from stream to first frame, no modal, no white page, no decryption.** The user
> browsed servers in a real fullscreen WebView; we read the network; the registered extractor turned the
> chosen embed into a quality-labelled link.
>
> Every mechanism here earned its place against a specific observed failure. In order of how much they
> mattered:
>
> | # | Change | Without it |
> |---|---|---|
> | 1 | `injectSpoofingJs = false` (§1) | Decoy: blank page, `delta=47` |
> | 2 | `injectDocumentWriteHook = false`, rewrite **on** (§12, §13) | Decoy on some titles; blank page on others |
> | 3 | `registerSharedExtractors()` in the plugin (rule 25) | Every server falls through to a sniffed single rendition — this run's 1080p came from `ByseExtractor` |
> | 4 | Exit on a **stream**, not an embed (§6) | Window closes on a dead server, 49 s spinner, no way to pick another |
> | 5 | `isPlayableCapture()` rejection filter (§3) | VK's extension-less streams discarded — captures but zero links |
> | 6 | `getLinkType` default `VIDEO` (§4) | `Input does not start with the #EXTM3U header` |
> | 7 | Popup transport + 900 ms visibility dip (§2, §9) | "Allow the ads" / "allow redirection and popups" modal on every tap |
> | 8 | Navigation-only embed detection (§10) | 728 "embeds", 728 re-fetches, 30 s+ black screen |
> | 9 | Straggler grace + shrinking cap (§10, and the 15:40 log) | 15 s of spinning with every link already delivered |
>
> **Known-imperfect, deliberately left alone:** the captured-embed-HTML fast path (§7) still resolves
> nothing in practice — this run captured 1,605 / 1,605 / 72,258 chars and `html=-1` for the fourth, and
> all four parsed to nothing; the registered extractors did the work. It costs a few hundred ms, so it is
> not urgent, but "no request made" is aspiration rather than description. `dipPageVisibility` and the
> popup sink are also still in place; if the gate ever stops mattering they are dead weight.
>
> ---
>
> **⚠️⚠️ IMPLEMENTATION NOTES — WE NO LONGER DECRYPT.**
>
> Read “WHAT MADE IT WORK” below before changing anything in this flow.
>
> `loadLinks` → `resolveViaFullscreenSurf`. The watch page is **shown to the user in a fullscreen
> WebView**; they pick a server and press play, and we read the stream URL off the network. **We no
> longer try to read the decrypted server list at all** — no reader script, no exfil channel, no DOM
> scraping, and — as of 2026-07-30 — **not even `SPOOFING_JS`**, which turned out to be the last thing
> giving us away. The only JS we still run is the `title`/`bodyLength` diagnostic pair in
> `onPageFinished`, after the page has already decided about us.
>
> One thing we *do* still change: the served HTML is **rewritten** so `document.write('<script src=…')`
> becomes a plain tag (§13). That edits bytes before the page runs; it never wraps `document.write`, so
> rule 3 holds. The hook that does wrap it stays off (`injectDocumentWriteHook = false`).
>
> Why this replaces the sandbox: every entry in §0.1 fails for the same structural reason — the gate
> inspects **its own environment**, not its input. Any hook, bridge, marker or `evaluateJavascript`
> read is on the page's side of the boundary and therefore visible. `shouldInterceptRequest` is on
> **our** side and is not observable from the document at all. So we stopped fighting for the
> decrypted server list and took the stream instead. The gate can win its own argument; the video
> still has to travel over the wire.
>
> **It is the sandbox flow's navigation, unchanged, with `Mode.FULLSCREEN` instead of `HEADLESS`.**
> That is the whole design, and it is not an aesthetic choice — see §0.1 rule 16 for what happens when
> you open the watch link in a WebView of your own instead.
>
> **The load-bearing pieces:**
> 1. **Same engine, same interceptor, same headers.** What makes `/watching/` render at all is
>    `NavigationEngine.shouldInterceptRequest` re-issuing the main frame through `HttpURLConnection`
>    with **real-Chrome `sec-ch-ua`** (not `"Android WebView"`), an emptied `X-Requested-With`, the
>    CookieManager cookies and the original `Referer`. Reaching the page and watching the page must be
>    the same WebView for that reason.
> 2. **Steps 0–1 are the sandbox flow verbatim:** `LoadHtml(timerHtml, TIMER_PAGE_URL,
>    REDIRECTING_PAGE_URL)` then `NavigateToWatchingUrl` — the countdown mints the token, the
>    interceptor captures it, the engine navigates with the blog-post page as `Referer` (§0.1 rule 5:
>    without it the gate runs `location.replace('/home')`).
> 3. **Step 2 is the only addition:** `NavigationStep.WaitForCapturedVideo` holds the visible session
>    open while the user picks a server and presses play, then ends `graceMs` after the first hit —
>    `embedGraceMs` (600 ms) instead once an embed is in hand, since its extractor makes the variant
>    playlists redundant. Satisfied only by a **playable stream**: an embed is not proof that the server
>    works (§6), and segments do not count either, since a `.ts` proves playback started but plays
>    nothing on its own. Ends immediately if the user closes the window, and hands over any embeds it
>    has even when no stream arrives.
> 3b. **Resolution order: captured embed HTML → URL-based extractors → sniffed streams.**
>    `resolveEmbedFromHtml()` parses the bytes the iframe already received (§7 — a second request to the
>    embed host is what kept failing); `fallbackExtractIframe()` covers embeds whose HTML says nothing;
>    the sniffed stream is last, being one ABR-chosen rendition. See §5. **In practice the extractors do
>    all the work today** — the HTML path has yet to resolve a single embed in a real run — which is why
>    `registerSharedExtractors()` in `CimaNowPlugin` is load-bearing and not boilerplate (rule 25).
> 3c. **Newest-first, then stop.** Embeds resolve newest-first (the last iframe is the server the user
>    chose), capped at `MAX_EMBEDS_TO_RESOLVE`, bounded by `EMBED_PHASE_BUDGET_MS`, and — once any link
>    is delivered — cut off after `STRAGGLER_GRACE_MS` with the per-embed cap shrunk to the grace that
>    remains. A per-item timeout is not a phase bound; both are needed.
> 4. **`destinationLockPatterns = /(watch|watching)/`**, same as before, and it earns its keep here:
>    once on `/watching/` every main-frame navigation is refused silently, so the ad redirects a stray
>    tap fires cannot steal the screen. Servers switch by AJAX into an iframe, so nothing legitimate
>    needs a main-frame nav.
> 5. **Real input, via `TvMouseController`** (now attached to `NavigationEngine`'s fullscreen dialog).
>    The user or a D-pad remote clicks the server and play button and the controller dispatches real
>    `MotionEvent`s, so the page sees `isTrusted === true` touches. An injected `element.click()` is
>    both forbidden here and self-defeating. Back walks the history before it closes the dialog.
>
> **Files:** `NavigationEngine` (`WaitForCapturedVideo` handler, `capturedVideoRequests`, the mouse in
> `createDialog`), `CimaNowProvider.resolveViaFullscreenSurf()` / `buildSurfLink()` (captured request
> headers → `ExtractorLink` for ExoPlayer; the request's own `Referer`/`Origin`/cookies are kept
> because tokenised CDNs check them).
>
> ## 2026-07-30 — WHAT MADE IT WORK
>
> Two changes ended a ~40-commit arms race. Both are subtractions.
>
> ### 1. Stop injecting `SPOOFING_JS` — this is what killed the decoy
>
> First fullscreen run: the chain worked, `/watching/` loaded in full (`bodyLength=4249217` against a
> 4,249,170-char raw HTML) and the screen was **blank white**. `bodyLen - htmlLen == 47` — the
> decryptor's decoy `<div id="watch"></div><div id="download"></div>`. We were flagged, not broken.
> The viewport was real and fullscreen, so the size check was not it; what remained in the page was
> **our own** `NavigationEngine.SPOOFING_JS`, injected at `onPageStarted` ahead of every page script,
> defining `window.DisableDevtool` and reporting `navigator.plugins == [1,2,3,4,5]` where Android
> Chrome reports an empty `PluginArray`. §0.1 rules 6–7, written by our own hand, in Kotlin instead of
> in an injected reader.
>
> `execute(injectSpoofingJs = false)` and the very next run:
>
> | | before | after |
> |---|---|---|
> | `bodyLength` | 4,249,217 | 4,637,677 |
> | `bodyLen - htmlLen` | **47** (decoy) | **+383,720** (real server list) |
> | screen | blank white | the page, with servers |
>
> **The anti-bot was never beaten. It was stopped from having anything to find.** Every earlier
> approach added something to the page and then tried to hide it; this one removes the last thing we
> were adding. If the decoy ever returns, look first for something new in the page context — not for a
> decryption bug.
>
> Supporting change from the same log: the re-issued request no longer sends `X-Requested-With` **at
> all**. Setting it to `""` (to mask the package name WebView leaks) emitted `x-requested-with=` with
> an empty value — a header no real Chrome sends, i.e. the mask *was* the signature. Our own
> `HttpURLConnection` never adds it.
>
> ### 2. Honour `window.open` — this is what stopped the "allow the ads" modal
>
> With the page finally rendering, clicking play or switching server produced a SweetAlert2 modal
> demanding the user allow ads, and refused to do either. Nothing was blocking ads:
> `pagead2.googlesyndication.com`, `tpc.googlesyndication.com` and `fundingchoicesmessages.google.com`
> all loaded fine. What was missing was the **window**. The page loads an Adcash "iclick" popunder
> (`luugy.com/5/…?oo=1&js_build=iclick-…`) and gates playback on it, and `onCreateWindow` used to
> `return true` **without** filling `resultMsg`'s `WebView.WebViewTransport` — which does not allow a
> popup, it silently drops it, so `window.open()` evaluates to `null` and the page concludes the user
> is blocking ads.
>
> Now `onCreateWindow` hands over a detached sink WebView: the page gets a live window whose `closed`
> stays false, nothing is shown, the main frame is untouched, and the sinks are destroyed with the
> session. **Returning `true` from `onCreateWindow` is not "allow popups" — filling the transport is.**
> (The sink stays **blank**: §9 decoded the gate and it only requires the window to stay open past
> 800 ms, not to load. `loadPopupsInSink` exists for the alternative but is `false` everywhere.)
>
> ### 3. Filter sniffer captures by rejection, never by recognition
>
> The same run captured four VK streams and produced **zero** links. VK serves its ladder from
> `https://vk6-3.vkuser.net/?expires=…&type=1&…` — no extension, no `/hls/` path, nothing to
> recognise — so `VideoUrlClassifier.isVideoUrl()` is false for it and the filter discarded exactly
> the streams only a sniffer could have found. Symptom in the log: five `🎬 CAPTURED VIDEO URL` lines
> and no `Video captured — collecting variants` line.
>
> Captures now go through `VideoUrlClassifier.isPlayableCapture()`, which *rejects* (segments,
> thumbnails, trackers, DASH) instead of *recognising*. The sniffer already decided it was media; the
> filter's only job is to drop what cannot be played. Two corollaries: `iv.okcdn.ru/getVideoPreview…`
> is a JPEG on the stream's own host and is now excluded at capture time (`isPreviewAsset`), and VK
> quality comes from `type=N` (`vkQuality()`), since one server delivers the whole ladder at once and
> the URLs contain no resolution.
>
> ### 4. Default a sniffed link to progressive, never to HLS
>
> With links finally reaching the player, playback still failed instantly:
> `ParserException: Input does not start with the #EXTM3U header`, and on the way there
> `M3u8Helper2.hslLazy` tried to read the 5 MB video as text and threw. Cause: `getLinkType()` ended in
> `else -> ExtractorLinkType.M3U8`, so the extension-less VK URL was handed to ExoPlayer's HLS reader.
> Now `.m3u8` or a `/hls/` path (`isLikelyHlsManifest`) → M3U8, `.mpd` → DASH, **everything else →
> VIDEO**. The asymmetry is the argument: a mislabelled manifest costs one source, a mislabelled
> progressive stream costs every VK server in the provider.
>
> ### 5. Sniff the **embed**, not the stream — extractors give the full quality ladder
>
> With playback finally working, only 480p was ever available. The reason is structural, not a bug:
> what we sniff is whatever the embed's ABR decided to fetch, and VK starts at the bottom rung and
> steps up over tens of seconds. Worse, **each rendition is signed separately** (`type=1` →
> `sig=N71T2BJj-…`, `type=3` → `sig=5xRX3euizrE`), so a captured 480p URL cannot be rewritten into
> 1080p. Waiting longer is not a fix either — the ladder only appears if the player chooses to climb it.
>
> The embed URL, however, is already passing through the same interceptor, and it arrives **before** any
> video bytes:
>
> ```
> 10:46:06.664  URL: https://vkvideo.ru/video_ext.php?oid=-231591796&id=456240644   MAIN: false
> 10:46:09.132  🎬 CAPTURED VIDEO URL: …vkuser.net/?…type=1…      (2.47s later)
> ```
>
> Handed to `VKVideoEmbed`, that URL yields the whole ladder from the player's own parameters — `"hls"`
> (a master playlist, so ExoPlayer switches quality itself) or `"url240"…"url1080"`. So the flow now
> resolves **embeds first** via the existing `fallbackExtractIframe()` — the same dispatcher
> (CimaNowTVEmbed → VKVideoEmbed → `loadExtractor` → HTTP regex) that the sandbox path fed from the
> decrypted `<li data-index>` list — and only falls back to the sniffed stream when no extractor claims
> the host.
>
> That has a consequence worth stating plainly: **the surf replaces the decrypted server list
> entirely.** Those iframe documents *are* what `core.php` used to be resolved into, so every existing
> per-server extractor is reachable again without decrypting anything.
>
> Detection rule: a subframe request (`isForMainFrame == false`) whose `Accept` contains `text/html`,
> excluding ad/consent frames by host (`VideoUrlClassifier.isLikelyAdFrame`). Keyed on `Accept` because
> **WebView does not send `Sec-Fetch-Dest` here**; across a whole session that test matched exactly two
> requests — the watch page and the VK embed. It is deliberately *not* restricted to third-party hosts:
> some servers embed their player from the site's own domain, and ad frames are already excluded by host.
>
> Two smaller fixes shipped with it:
> - **Rendition identity, not URL identity** (`renditionKey()`). VK re-requests the same rung with
>   `ct=11` vs `ct=12`, `fromCache=1`, and a reordered query, so `distinctBy { url }` produced two
>   identical 480p entries in the picker. A VK rendition is `host|type|sig`; everything else falls back
>   to host + path + sorted query minus the volatile keys.
> - **Closing the surf window now ends the wait.** The dialog's dismiss listener sets a flag that
>   `WaitForCapturedVideo` polls; previously it only logged, so backing out left `loadLinks` polling for
>   up to 300 s with nothing on screen.
>
> ### 6. Wait for a **stream**, not an embed — an embed is not proof of playback
>
> The failure mode that taught this (2026-07-30, Sonic): the surf window vanished, a spinner ran for
> ~49 s, then "no links". What the log showed:
>
> ```
> Step 2: Done | embeds=3, playableStreams=0, totalStreamCaptures=0
> Dialog dismissed …                                    (27 ms later — our own cleanup)
> Resolving embed: ep2.adtrafficquality.google/sodar/…/runner.html
> Resolving embed: www.google.com/recaptcha/api2/aframe
> Resolving embed: vkvideo.ru/video_ext.php?oid=792310950&id=456242753
>   → st1-55.vkvideo.ru/dist/webl/video_embed_error.isolated…  +  images/icons/cry_dog.png
> No extractor produced links                            (11:18:52, 49 s after the window closed)
> ```
>
> `totalStreamCaptures=0` is the whole story: **the page never fetched one byte of video.** That VK
> embed was VK's own error page. We had exited the surf on the embed capture alone, so the user never
> saw the error and never got to pick another server — and `VKVideoEmbed`, finding no player params,
> fell into its 45 s headless sniffer while the screen showed a spinner with no WebView behind it.
>
> Three corrections, each aimed at one link in that chain:
> - **The exit condition is a playable stream again.** Embeds no longer satisfy the wait; they are still
>   resolved first (quality) and still handed over when no stream ever arrives, so nothing is lost. In
>   the healthy case the stream follows the embed within ~2 s, so this costs nothing — and when a server
>   is broken the window stays open and the user simply picks another.
> - **Captcha/verification frames are not embeds.** `adtrafficquality.google`, `/sodar`, `recaptcha`,
>   `/aframe`, `gstatic.com`, `hcaptcha`, `challenges.cloudflare.com`, `turnstile` added to
>   `isLikelyAdFrame`. They are third-party iframe documents, structurally identical to a player embed —
>   only the host tells them apart.
> - **`EMBED_RESOLVE_TIMEOUT_MS = 12 s` per embed.** This phase runs with the window already closed, so
>   every second is a blank spinner. Healthy resolution measured 0.7 s; 45 s of sniffer for a dead embed
>   is never worth waiting for.
>
> Also fixed: `cleanupWebView` dismissing the dialog no longer logs (or records) "dismissed by user" —
> it set `dialogDismissedByUser` 27 ms after the step ended, which was both untrue and left the flag set
> for whatever came next.
>
> ### 7. Keep the embed's HTML as it loads — never ask the embed host twice
>
> With the embed path live, the extractor still produced nothing (2026-07-30, 11:42):
>
> ```
> 11:42:33.674  Resolving embed via extractor: host=vkvideo.ru
> 11:42:43.680  [executeDirectRequest] Failed | …video_ext.php… | error=timeout        ← 10.0 s
> 11:42:44.407  st.vkvideo.ru/dist/webl/video_embed_error.isolated…js                  ← its WebView fallback
> 11:42:48.600  ⏱ Extractor for vkvideo.ru exceeded 12000ms — moving on
> ```
>
> Both attempts failed for the same underlying reason — **it was the second request**:
> - The HTTP fast path is rate-limited by VK after a session's worth of calls, and the rejection stalls
>   to timeout instead of erroring (the extractor's own comments describe the 302→429 behaviour).
> - Its WebView fallback loads `video_ext.php` as a **top-level document**, which is not an iframe
>   context, so VK serves `video_embed_error` no matter what headers are set.
>
> Ten seconds earlier the surf WebView had loaded that exact embed successfully, as a real iframe, and
> the HTML — player params included — had passed straight through our own interceptor. We threw it away.
>
> Now `fetchEmbedDocument()` serves the iframe itself: re-issues the request with the iframe's own
> headers plus `Sec-Fetch-Dest: iframe`, real-Chrome `sec-ch-ua` and the CookieManager cookies, keeps
> the body in `CapturedEmbedRequest.html`, and hands the same bytes back so the iframe still renders.
> On non-200 or any exception it returns null and Chromium fetches normally — a lost capture is
> recoverable, a broken iframe is not.
>
> The provider then calls `resolveEmbedFromHtml()` **before** any network extractor:
> `VKVideoEmbed.getUrlFromHtml()` for VK hosts, then a generic scan for direct `.m3u8`/`.mp4` URLs
> (unescaping `\/` first, since these pages carry sources inside JSON). The URL-based
> `fallbackExtractIframe()` remains as the fallback for embeds whose HTML says nothing.
>
> **The rule this establishes:** if the WebView has already fetched something we need, capture it in
> flight. A second request is not the same request — different context, different rate-limit budget,
> and on an embed-only endpoint a different answer.
>
> ### 8. The popunder has to actually load — `window.open() != null` is only half the gate
>
> The blank sink from §2 killed the "allow the ads" modal, but a second modal appeared on every server
> click: *"please allow redirection and popups to watch"*. The log (2026-07-30, 11:40) shows why:
>
> ```
> 11:40:19.574  Popup honoured into a blank sink window | sinks=1, isUserGesture=false   ← auto popunder
> 11:40:19.584  [popupSink] Swallowed popup navigation | luugy.com/?rb=e9Lhdf…
> 11:40:20.294  Popup honoured into a blank sink window | sinks=2, isUserGesture=true    ← the user's click
> 11:40:20.301  [popupSink] Swallowed popup navigation | viiukuhe.com/dc/?blockID=420548&tb=cimanow.cc
> 11:40:20.772  luugy.com/ct?rb=e9Lhdf…      ← ×12, same rb token, retrying
> ```
>
> `/ct` is the network's conversion ping and it re-fires until the popunder really loads. A live-but-blank
> window satisfies `window.open() != null` and nothing further. Note also that the second popup has
> `isUserGesture=true` — the site wires **server switching itself** to an ad open, which is why the modal
> only appeared on a click. And there were **zero** main-frame navigation attempts in the log (no
> `REDIRECT DETECTED`, no `DESTINATION LOCK BLOCK`), so "redirection" is the ad network's stock wording,
> not our destination lock.
>
> `execute(loadPopupsInSink = true)` now lets the popunder load for real in a hidden, detached WebView.
> **Opt-in per provider, off everywhere else** — CimaNow's call site is the only `true` in the repo,
> because the cost is real: the ad genuinely loads, with real requests and a real impression, in a
> WebView the user never sees. Contained by `MAX_POPUP_SINKS = 4`, `POPUP_SINK_TTL_MS = 15 s`, refusal of
> nested popups, and http(s) only — an `intent://` from an ad would otherwise throw the user out of the
> app. Nothing it loads can reach the main frame.
>
> If the modal persists, check `Loading popup for real (hidden)` appears and whether `/ct` stops
> retrying; if `/ct` still repeats, the network wants something more than a load (a dwell, or a click)
> and the honest options narrow to living with the modal.
>
> ### 9. The ad gate is a DWELL test — decoded from the page, not guessed
>
> Two changes were spent guessing at this modal from network traffic. The answer came from the
> **decrypted watch page** (saved from a desktop browser to `test/gate.js`), where the handler bound to
> the watch buttons `#xqeqjp, #xqeqjp3` deobfuscates to:
>
> ```js
> THRESHOLD = 0x320                      // 800 ms
> start = Date.now(); e.preventDefault();
> win = window.open(href, "_blank");
> setTimeout(() => { if (!win || win.closed || typeof win.closed === "undefined") MESSAGE() }, 100);
> setInterval(() => { if (win.closed && Date.now()-start < 800) MESSAGE() }, 100);
> setTimeout(() => { if (!closed && elapsed >= 800) { btn.hide(); setTimeout(()=>btn.show(), 900000) } }, 800);
> isMobile() ? (visibilitychange→visible | touchstart{once} | keydown | mousemove) → onReturn
>            : beforeunload → onReturn
> onReturn = () => { if (!closed) elapsed < 800 ? MESSAGE() : (btn.hide(), reshow in 15 min) }
> ```
>
> And the message itself: *"يرجى إيقاف مانع الإعلانات أو السماح للإعلان بالبقاء مفتوحًا لثوانٍ قليلة"* —
> "disable your ad blocker **or let the ad stay open for a few seconds**." It says what it checks.
>
> So the gate wants the popunder to survive **800 ms**, and on mobile (our UA matches its `Mobi|Android`
> test) the *first* of `visibilitychange→visible` / `touchstart` / `keydown` / `mousemove` after the
> click settles it: under 800 ms → modal, 800 ms or more → success, button hidden for 15 minutes.
>
> Our sink passed the window checks all along — a real WebView, `closed === false`, alive 15 s. What
> failed was the timing arm: the sink is detached, so the page never loses visibility, and the next
> input event (Chromium's compat `mousemove` after a tap, or a second tap because nothing visibly
> happened) landed inside 800 ms. `dipPageVisibility()` now hides the page for
> `POPUNDER_DWELL_MS = 1200` after a user-gesture popup and restores it — which is exactly what a real
> popunder does to a phone browser, expressed through an ordinary browser signal with nothing injected
> into the page.
>
> **`loadPopupsInSink` went back to `false` on this evidence.** The gate never required the ad to load,
> only to stay open, which a blank sink already satisfies. It had been turned on because `/ct?rb=…` kept
> re-firing — which the next log showed happens identically with the ad fully loaded, i.e. it is a
> heartbeat, not a retry. Two lessons, both cheap to reread: **`/ct` retries prove nothing**, and when a
> page-side condition is unclear, read the page instead of inferring from packets. The script that
> raises it can be dumped without touching the page — see the `📜 SCRIPT DUMP` line.
>
> ### 10. A prefetch looks exactly like an iframe — 728 "embeds" in one run
>
> Server switching worked, then the surf window closed and left a black screen spinning for 30 s+ before
> failing. Cause: `Step 2: Done | embeds=728`. Parsing every subframe request in that log:
>
> | | count | what it was |
> |---|---|---|
> | `Sec-Purpose: prefetch` | **208** | Chromium speculation-rules prefetches of static assets (`s5.teraboxcdn.com/fe-opera-static/…`) |
> | `Upgrade-Insecure-Requests: 1`, no `Sec-Purpose` | **1** | `uqload.is/embed-….html` — the real embed |
>
> `Accept: text/html` does not identify a document: a prefetch sends the full navigation-style Accept.
> The test is now `Sec-Fetch-Dest: iframe|document`, or `Accept: text/html` **plus
> `Upgrade-Insecure-Requests`** (navigations only), and never with `Sec-Purpose` (prefetch/prerender) or
> `X-Requested-With: XMLHttpRequest`. On that log it yields exactly 1 embed instead of 728.
>
> Three consequences fixed with it:
> - **728 captures meant 728 extra HTTP requests**, since `fetchEmbedDocument` runs per capture. Beyond
>   waste, re-issuing a request we have not established to be a document can repeat a side-effecting
>   call. Now gated behind the navigation test and capped at `MAX_CAPTURED_EMBEDS = 12`.
> - **The resolution phase needs its own bound.** A per-embed timeout is not one: 307 embeds × up to 12 s
>   ran from 13:39:35 past the end of the log. Now newest-first, `MAX_EMBEDS_TO_RESOLVE = 6`, whole phase
>   capped by `EMBED_PHASE_BUDGET_MS = 25 s`.
> - **uqload was captured and still produced nothing** (`html=10214`, no link). Two independent causes:
>   its sources live inside a Dean Edwards `eval(function(p,a,c,k,e,d){…})` block, so scanning for
>   `.mp4`/`.m3u8` finds nothing — `VideoUrlClassifier.unpackJs()` now expands those before the scan,
>   which is a generic win since many hosts pack the same way. And `loadExtractor` has no match for
>   `uqload.is` — **wrong, and worth recording as a wrong diagnosis.** `UqloadIs : Uqload()` with
>   `mainUrl = "https://uqload.is"` has existed in `SharedExtractors.kt` all along. The real reason was
>   that **`CimaNowPlugin` never called `registerSharedExtractors()`** — 19 other provider plugins do —
>   so inside this plugin `loadExtractor` saw an empty registry and *every* server fell through to the
>   sniffed stream: uqload, GoVid, Vidmoly, EarnVids, Byse, Savefiles, Videa, Mailru, the Sniffer itself.
>   One line in the plugin, and the whole extractor layer came back. The unpacker is still worth having
>   (it makes the captured-HTML path work without any extractor), but it was treating a symptom.
>
> ### 11. `/blockedone` is the site refusing a title — not a bot detection, and not a regression
>
> A white screen on *some* titles while others played fine (2026-07-30, The Walking Dead: Dead City):
>
> ```
> 14:58:13.107  INTERCEPTED …/watching/ (text/html)      ← full 4,711,549-char payload, served normally
> 14:58:13.787  onPageStarted + Spoofing JS NOT injected ← page context clean
> 14:58:13.860  REDIRECT DETECTED http://cimanow.cc/blockedone   ← 73 ms later, the page itself
> 14:58:13.860  DESTINATION LOCK BLOCK
> 14:58:13.865  bodyLength=4711548 → delta=-1            ← decryptor wrote nothing
> ```
>
> `delta=-1`, not 47, so this is **not** the bot decoy. The string `blockedone` does not appear anywhere
> in a working title's decrypted page, so it is a per-title branch: this payload's own code decided the
> title is not playable and navigated away 73 ms after the document started.
>
> **The bug on our side was the white screen, not the block.** The destination lock blocked that
> navigation — correctly, since it cannot tell it from an ad hijack — leaving the WebView on an empty
> document until the 300 s timeout, so the user stared at white while the site had answered in 73 ms.
> Now the lock distinguishes by site: a **cross-site** main-frame navigation is an ad and is still
> blocked silently; a **same-site** one is the site moving us off its own player page (`/blockedone`, or
> the documented `location.replace('/home')`), which sets `siteRejectedNavigationUrl` and ends the wait
> immediately with that URL in the failure reason.
>
> Look for **`🚫 SITE SENT US AWAY`**. It means try another title or check the referrer — not that the
> flow is broken.
>
> ### 12. THE white-page bug: we were still injecting the `document.write` hook
>
> White page on some titles, fine on others — and unlike §11 this one **was ours**. The log names it
> (2026-07-30, 15:18):
>
> ```
> 15:18:34.772  Injected document.write interceptor for cimanow.cc main-frame (5211 chars) — rewrote N call(s)
> 15:18:34.820  [CW] document.write hook active           ← our own injected script
> 15:18:35.176  [CW] Poll check #1: body.length=4320169 no data-index
> 15:18:35.190  bodyLength=4320169 → delta=-49 — decryptor wrote nothing
> ```
>
> That is `ANTI_ANTI_BOT_JS` — the wrapper §0.1 rule 3 forbids — running in the page. It fires **only
> when the payload contains `document.write('<script src=…')` calls to rewrite**, so on the titles we
> happened to test the log said *"No document.write on this page — skipping"* and everything worked,
> while titles whose payload does contain them went white. Exactly the "some movies work" pattern.
>
> The code even anticipated it and assumed it was unreachable: *"On this page the rewrite finds nothing
> to rewrite … so injecting it was pure tripwire for zero benefit."* That assumption held for one title
> and was never true in general.
>
> `execute(rewriteDocumentWrite = false)` for the surf flow: the main frame is now served **verbatim**,
> nothing rewritten, nothing injected. Both the rewrite and the hook existed for the sandbox, which
> needed those CDN scripts so it could scrape the server list out of the DOM. The surf flow scrapes no
> DOM at all, so the rewrite buys nothing and the hook costs everything.
>
> **The lesson is the same one as §1, and it took a second, worse form to learn it:** "we inject nothing"
> has to mean *nothing*, on every code path, including one that only arms itself on certain inputs. Grep
> for injection sites before believing the claim — `Serving cimanow.cc main-frame verbatim` is the line
> that proves it now.
>
> ### 13. The rewrite and the hook are two things — disable only the hook
>
> §12 turned off `rewriteDocumentWrite`, which controlled **both** the HTML rewrite and the
> `ANTI_ANTI_BOT_JS` injection. The decoy went away and a new failure took its place: tapping a second
> watch server produced a blank page with **no navigation and no network activity at all** — the 634-line
> log has one `onPageStarted`, no `REDIRECT DETECTED`, and nothing between the click and the dismissal
> but `luugy.com/ct` pings. Nothing loaded a blank page; the document wiped itself.
>
> Cause: with the payload served verbatim, its own `document.write('<script src=…')` calls run natively,
> and a `document.write` after load implicitly `document.open()`s — clearing the document. That is
> exactly what the rewrite existed to prevent ("bypass Chrome's cross-origin document.write
> intervention"). Only the **injection** wraps `document.write`; the rewrite just edits bytes and leaves
> the function native, so it never violated rule 3.
>
> Now split: `rewriteDocumentWrite = true`, `injectDocumentWriteHook = false`. Confirm with
> `Rewrote N document.write call(s) into direct tags; hook NOT injected` — and if `[CW]` ever reappears
> in a log, the hook is back and the decoy will follow.
>
> Same run, separate bug: the 2-second white flash after tapping a server was **`dipPageVisibility`
> hiding the WebView over a white dialog background**. `createDialog` painted its container white; it is
> black now (as `VideoSnifferEngine`'s already was) and the dwell is 900 ms rather than 1200, since the
> gate's threshold is 800.
>
> ### 14. The page turns its whole ad gate off for TV user agents
>
> After §13 the white page persisted, and the log (16:29, new build confirmed by `Page hidden for 900ms`)
> showed the tap producing **only** the popunder — no navigation, no iframe, no `core.php`, no `[CW]`. So
> the blanking is page-side, and the decrypted page says how:
>
> ```js
> $(document).ready(function(){
>   if (isTv()) return;                                     // ← everything below is skipped
>   if ($("#xqeqjp").length===0 && $("#xqeqjp3").length===0){
>       MESSAGE(null); $("main article ul.btns li").remove(); return }
>   $("#xqeqjp, #xqeqjp3").each(function(){
>       if (!$(this).attr("href") || $(this).attr("href").trim()===""){
>           MESSAGE($(this)); $("main article ul.btns li").remove() } });   // ← empties its own UI
>   $("#xqeqjp, #xqeqjp3").on("click", adGateHandler)
> })
>
> isTv = () => /smart-tv|smarttv|hbbtv|netcast|webos|tizen|viera|aquos|android tv|apple tv|roku|fire tv/
>              .test(navigator.userAgent.toLowerCase())
> ```
>
> `$("main article ul.btns li").remove()` is the site **emptying its own button list** when a button has
> no href — a blank action area with no navigation and no network activity, which is exactly the reported
> symptom. And every one of the things we have been fighting — the popunder, the 800 ms dwell (§9), the
> "allow the ads" modal, this UI removal — sits *after* `if (isTv()) return`.
>
> So `SURF_AS_TV_UA = true`: surf with an Android TV UA and the site skips its own gate. This is a path
> the page offers (a TV cannot show a popunder), not a hook or an injection, so rule 3/17 are untouched.
> `asTvUserAgent()` changes only the device descriptor and keeps the real Chrome build, since the regex
> needs just one token.
>
> **Untested risk, flip `SURF_AS_TV_UA` to false if it bites:** the `get-link.php` token chain runs
> through this same WebView session and has only ever been exercised with a phone UA. If
> `No watching URL captured` starts appearing, suspect this first. The site may also serve TVs a
> different layout. If the gate is genuinely gone, `dipPageVisibility` and the popup sink become dead
> weight for this provider and can go.
>
> ### Diagnostics — the markers to grep, in order
>
> Every one of these exists because its absence cost a debugging round:
>
> | marker | means |
> |---|---|
> | `🔧 REWRITE: N document.write call(s) → direct tags; hook NOT injected` | the §13 split is active. `0 matches` means the rewrite is a no-op for this title |
> | `[CW]` **anywhere** | the forbidden hook is back; the decoy will follow (§12) |
> | `Spoofing JS NOT injected` | page context clean (§1) |
> | `Decryptor produced content (delta=…)` / `🚩 DECOY SERVED` (47) / `delta=-1` | rendered / flagged / wrote nothing |
> | `📄 WATCH PAGE DUMP: …` | the exact bytes served — read `#xqeqjp` hrefs here; an empty one makes the site run `$("main article ul.btns li").remove()` and empty its own UI (§14) |
> | `💥 RENDER PROCESS GONE` | the blank page is a renderer crash, not a navigation |
> | `🚫 SITE SENT US AWAY` | same-site redirect off the player page — title blocked or session rejected (§11) |
> | `⏳ Waiting for a stream \| embeds=… streamCaptures=… url=…` | 5 s heartbeat; the `url` comes from the WebView, so a silent navigation shows up here |
> | `🎯 CAPTURED EMBED … html=N` | embed found; `html=-1` means the in-flight capture failed |
> | `🎬 CAPTURED VIDEO URL` | a stream on the wire — the only proof a server actually plays (§6) |
> | `>>> EMBED LINK` / `>>> LINK` | resolved via extractor / via sniffed stream (the fallback) |
> | `⏭ Links already delivered — cancelling` | straggler cut-off working |
> | `📄 EMBED HTML DUMP` / `📄 SCRIPT DUMP` / `📄 NO-SERVERS DUMP` | bytes on disk for the fast path, the gate script, and an empty parse |
>
> ### Diagnostics to read first, in this order
>
> 1. `Spoofing JS NOT injected (pristine page context)` — the page context is clean.
> 2. `onPageFinished` verdict — **`🚩 DECOY SERVED`** (delta 47) / "wrote nothing" (<200) / `Decryptor
>    produced content (delta=…)`. A blank screen and a decryption failure are indistinguishable
>    without this line.
> 3. `Popup honoured into a blank sink window` — the ad gate will not fire.
> 4. **`🎯 CAPTURED EMBED … html=N`** — the good path. `html=-1` means the in-flight capture failed and
>    we are about to re-request the embed, which is what fails (§7). Then `Resolved … from the captured
>    embed HTML — no request made` and `>>> EMBED LINK`, i.e. the full ladder from the player's params.
> 5. `🎬 CAPTURED VIDEO URL` then `Source captured — collecting`. Reaching `No extractor produced links
>    — falling back to the sniffed stream(s)` means you are back to single-rendition ABR quality: find
>    out why the embed was not captured or not claimed, rather than accepting 480p.
>
> **Still unproven, for the record:** whether `navigator.userAgentData.brands` (which reports
> `"Android WebView"` regardless of `userAgentString` and the spoofed `sec-ch-ua` headers) matters. It
> did not need to be touched. Do not pre-emptively spoof it — that would be adding something to the
> page again.
>
> **Reverting:** `USE_FULLSCREEN_SURF = false` in `CimaNowProvider` restores the sandbox path below,
> which is left fully intact.
>
> ### 15. The rewrite can break the page when `document.write` is inside multi-statement `<script>` blocks
>
> **2026-08-01.** White page, `delta=-57`, zero subresource loads, zero embeds, 2-minute timeout.
> The rewrite (§13) matched 2 `document.write('<script src=…')` calls and replaced them with
> direct `<script src="…"></script>` tags. The `</script>` in the replacement prematurely
> closed the enclosing inline `<script>` block, producing a syntax error and preventing ALL
> subsequent scripts — including the decryptor — from executing. The page "finished" in 239ms
> with zero CSS/JS/image requests, empty title, and body identical to the raw HTML.
>
> The rewrite was designed for standalone `<script>document.write(…)</script>` one-liners where
> the replacement harmlessly sits as text inside the (now-closed) script block. When the page
> restructured to put these calls inside larger script blocks, the replacement killed everything
> after the match point.
>
> **Diagnosis:** `delta` near 0, zero subresource requests after main frame, `onPageFinished` in
> <300ms, `REWRITE: N document.write call(s)` with N>0.
>
> **Fix:** `rewriteDocumentWrite = false`. Without the rewrite, `document.write` during initial
> parse is safe (it appends to the parser, doesn't wipe). If the "second server click wipes the
> doc" from §13 recurs, the replacement regex must be fixed to properly break out of the
> enclosing script block (`</script><script src="…"></script><script>` instead of
> `<script src="…"></script>`).
>
> | # | Do not | Why — and when we learned it |
> |---|---|---|
> | 28 | **Inject `</script>` into a replacement that will be placed inside an inline `<script>` block** | 2026-08-01. The HTML parser treats it as the end of the enclosing block regardless of context, killing the rest of the script and everything after it. |
>
> ---
>
> **⚠️ PREVIOUS IMPLEMENTATION (2026-07, superseded by the above) — history from here down.**
> **Sections 2–11 are HISTORICAL** and describe a three-layer strategy architecture that was
> **deleted** in `898efc74`. They are kept only for the format timeline (§9) and as a record of what
> was tried. Do not implement from them; §0 lists what they get wrong.
>
> We do **not** decrypt in Kotlin and we do **not** eval extracted scripts. The **WebView runs the
> page's own decryptor**; we only feed it the bytes and read the result. `loadLinks` →
> `resolveViaWebViewSandbox`:
> 1. HTTP-navigate the freex chain to the timer (blog-post) page (`navigateToTimerPageViaHttp`).
> 2. `NavigationEngine.execute(...)` follows countdown → `get-link.php` → the `/watching/` URL and
>    captures its raw (still-encrypted) HTTP response (`mainFrameHtml`).
> 3. `decryptViaSandbox` → `NavigationEngine.renderHtmlInSandbox` renders that HTML as a **real
>    navigation** to the `/watching/` URL (NOT `loadDataWithBaseURL`) with the blog-post page as
>    `Referer`, lets the inline decryptor run untouched, and reads the decrypted `<li data-index>`
>    server list back via an **in-page reader that streams it out as chunked `console.log`**
>    (`<tag>B` / `<tag>C<i>` / `<tag>E` → `onConsoleMessage`, `<tag>` random per run). Not
>    `addJavascriptInterface` (the gate sniffs `window.CS_BRIDGE`) and not `prompt()` (the gate
>    replaces `window.prompt` to swallow our messages).
>
> **The gate greps inline script source for our markers** (2026-07-25: it matches the literals
> `__CSX__` and `[RD]`, taken from earlier versions of this reader) and decrypts nothing on a hit.
> So the reader must contain **no fixed strings** — all tokens derive from a per-run random tag —
> and it **deletes its own `<script>` node** first thing, before the gate enumerates scripts.
> 4. Parse with **Jsoup**; resolve each server via `core.php` + extractors; parse `#download`.
>
> **Why it must be shaped exactly this way** — the watch page's anti-bot (decoded 2026-07):
> - Decryptor aborts if `Function.prototype.toString.call(document.write)` lacks `[native code]` →
>   **never hook `document.write`**.
> - Decryptor aborts if `location.hostname` is empty → must be a real navigation to `/watching/`.
> - An LZString-obfuscated gate runs `if(document.referrer.indexOf('rm.freex2line.online')===-1)
>   location.replace('/home')`, which aborts the `document.write` parse before the server list →
>   **must load with the freex blog-post page as Referer**.
> - After decrypting it installs `isBot()` = stack contains `evaluatejavascript`, or `<anonymous>`
>   with no `http` → sabotages `querySelectorAll`/`innerHTML`/etc. So DOM reads via
>   `evaluateJavascript` are defeated; only an **inline page-context script (http stack)** reads real
>   data. (Full mechanism also recorded in the agent memory `cimanow-antibot-mechanism`.)
>
> **2026-07-29 — two changes, both load-bearing:**
> - The reader is injected at **offset 0**, not after `<head>`. The payload script precedes `<head>`
>   and replaces the document, which used to discard the reader before it ever ran. See §0.1 rule 12.
> - The reader now reports on **two channels** — chunked `console.log` (status + bulk exfil) and
>   `document.title` → `onReceivedTitle` (status only). The offset-0 script tampers with console
>   methods, so a single channel cannot be trusted and a silent reader cannot be diagnosed.
>   `renderHtmlInSandbox` logs a `Reader channel report` verdict line; read it first. See §0.2.

---

## 0. IMPLEMENTATION RULES — read before changing anything

Every rule below is a **thing that was already built, shipped, and broke**. The site's gate is an
arms race with roughly 40 commits of history behind it; the cost of re-learning any of these is a
day. If you are about to do something on this list, the answer is already known.

### 0.1 Never do these

| # | Do not | Why — and when we learned it |
|---|---|---|
| 1 | Decrypt in Kotlin / replicate the cipher | Built, then deleted in `898efc74` (−806 lines, 2026-07-24). The cipher rotates **every few hours** (see the format timeline in §9 — five formats in six months), and worse, the plaintext is gated behind browser-context checks that have nothing to do with the maths. Replicating the cipher does not get you the plaintext. |
| 2 | `eval` extracted page scripts | Same reason. The gate checks its environment, not just its input. |
| 3 | Hook / wrap / polyfill `document.write` | Gate aborts if `Function.prototype.toString.call(document.write)` lacks `[native code]`. (Note §11's last answer contradicts this — §11 is historical and wrong.) |
| 4 | `loadDataWithBaseURL` | Gate aborts if `location.hostname` is empty. The document must be a **real navigation** to the `/watching/` URL, served from our own bytes via `shouldInterceptRequest`. |
| 5 | Navigate without a Referer | Gate runs `if (document.referrer.indexOf('rm.freex2line.online') === -1) location.replace('/home')`, aborting the parse before the server list. Pass the blog-post page as `Referer`. |
| 6 | `addJavascriptInterface` | Gate sniffs `window.CS_BRIDGE` / `window.__decryptedHtml` / `window.__captured`. Killed this channel. |
| 7 | Set anything on `window` | Same sniff. The reader must be a bare IIFE. |
| 8 | `prompt()` as an exfil channel | Gate replaces `window.prompt` to swallow our messages. Killed this channel. |
| 9 | `webView.evaluateJavascript` to read the decrypted DOM | Post-decrypt `isBot()` checks the call stack for `evaluatejavascript`, or `<anonymous>` with no `http`, and sabotages `querySelectorAll` / `innerHTML`. Reads must come from an **inline page script** (stack = the document's http URL). |
| 10 | Put any fixed string in the reader | Gate greps every inline script's `innerHTML` for known markers — as of 2026-07-25 the literals `__CSX__` and `[RD]`, both ours from earlier versions. All tokens must derive from a per-run random tag, and the reader must delete its own `<script>` node first thing. |
| 11 | Patch or freeze `Element.prototype.remove` | Gate tests it by appending an `<li data-index>`, removing it and checking `parentNode`. |
| 12 | **Inject the reader after `<head>`** | 2026-07-29. The payload script sits at **offset 0**, *ahead* of `<head>` (~966), and replaces the document via `document.open()`/`write()` — which resets the parser and discards every byte not yet parsed, the reader included. It never executed at all. Inject at offset 0 (after a `<!DOCTYPE` if present, or the page drops into quirks mode). |
| 13 | **Trust the console channel alone** | 2026-07-29. That same offset-0 script begins `!function(){try{for(var o=["lo…` — iterating what is almost certainly console method names. `console.log.bind(console)` only captures the genuine function if the reader runs **first**. Keep the second (title) channel. |
| 14 | **Chase subresource / MIME failures** | 2026-07-29. The sandbox deliberately serves no subresources, so jQuery, `owl.carousel`, the rotating-name anti-bot script and `animate.css` all come back as `text/html` and Chrome refuses them, giving `$ is not defined`. **Decryption works anyway** (`li=6` captured). This is noise, not a cause — do not spend time on it. |
| 15 | Add an Upnshare extractor | 2026-07-29. It was the one server that reliably burned its full 20s budget and returned nothing. Removed. When no extractor claims an iframe the sniffer takes over, so there is nothing to gain. |
| 16 | **Open the watch link in a WebView you built yourself** | 2026-07-29, and this one cost a full round trip. The token chain worked and the tokenised link was captured, but a fresh `WebView` pointed at it finished loading in **377 ms** and produced nothing at all — no video request, no console output. A hand-built WebView sends `sec-ch-ua: "…Android WebView";v="150"` (visible in the log3 request dumps) and cimanow bounces it. The page only renders because `NavigationEngine`'s interceptor re-issues the main frame through `HttpURLConnection` with real-Chrome `sec-ch-ua`, an emptied `X-Requested-With`, CookieManager cookies and the original Referer. **Reaching the page and watching it must be the same WebView.** Corollary: `Mode.FULLSCREEN` on the existing flow, not a new engine. |
| 17 | **Inject `SPOOFING_JS` (or anything else) into the watch page** | 2026-07-30, and this is *the* one. It defines `window.DisableDevtool` and claims `navigator.plugins == [1,2,3,4,5]` where Android Chrome reports an empty `PluginArray`, injected at `onPageStarted` ahead of every page script. With it: decoy (delta 47), blank white screen. Without it: `delta=+383,720`, the real server list. Pass `execute(injectSpoofingJs = false)`. Generalise: the page is not fooled by better disguises, it is defeated by an empty room. |
| 18 | **`return true` from `onCreateWindow` and call it popup support** | 2026-07-30. Without filling `resultMsg`'s `WebView.WebViewTransport` and calling `sendToTarget()`, no window is created and `window.open()` returns `null`. The page runs an Adcash "iclick" popunder (`luugy.com/5/…?oo=1`), gates play/server-switch on it, and shows a SweetAlert2 "allow the ads" modal when it comes back null. Ads themselves were loading fine the whole time. Hand over a blank sink WebView instead. |
| 19 | **Filter sniffer captures with `VideoUrlClassifier.isVideoUrl()`** | 2026-07-30. VK streams from `vk6-3.vkuser.net/?…&type=1&…` — no extension, no `/hls/` — so a *recognition* filter drops the very streams a sniffer exists to find (four captured, zero links, no `Video captured` line in the log). Use `isPlayableCapture()`, which rejects segments/thumbnails/trackers/DASH and passes everything else. |
| 20 | **Default an unrecognised URL to `ExtractorLinkType.M3U8`** | 2026-07-30. `getLinkType()`'s `else -> M3U8` sent the extension-less VK stream to ExoPlayer's HLS reader: `ParserException: Input does not start with the #EXTM3U header`, plus `M3u8Helper2.hslLazy` reading a 5 MB video as text. Sniffed extension-less URLs are progressive files behind a token — default to `VIDEO` and detect manifests positively (`.m3u8`, `/hls/`). |
| 21 | **Treat an embed capture as proof that the server works** | 2026-07-30. A captured iframe proves an iframe was inserted, nothing more. A VK embed that was VK's *error page* (`video_embed_error`, `cry_dog.png`) ended the surf with `totalStreamCaptures=0`, closed the window before the user could pick another server, then burned 49 s in `VKVideoEmbed`'s headless-sniffer fallback and failed. Exit on a **stream**; use the embed for quality. |
| 22 | **Re-request an embed page the WebView already loaded** | 2026-07-30. `video_ext.php` rate-limits the second caller (10 s stall to timeout), and its WebView fallback loads the embed as a *top-level document* — not an iframe — so VK answers `video_embed_error` regardless. Capture the HTML in flight (`fetchEmbedDocument`) and parse that (`getUrlFromHtml`). A second request is not the same request. |
| 23 | **Guess at a page-side gate from network traffic** | 2026-07-30, twice, before the decrypted page settled it in minutes. `luugy.com/ct?rb=…` re-firing looked like the ad network waiting for the popunder to load; it fires identically when the ad *has* loaded — a heartbeat. The real check was a **dwell test** (`window.open` must survive 800 ms; on mobile the first `touchstart`/`mousemove`/`visibilitychange` decides) plus a SweetAlert saying so in Arabic. Read the page (`test/gate.js`, or the `📜 SCRIPT DUMP`); do not infer from packets. |
| 24 | **Identify an iframe by `Accept: text/html`** | 2026-07-30. A Chromium speculation-rules **prefetch** sends the same navigation-style Accept: 208 of 209 candidates in one run were prefetched static assets, giving `embeds=728`, 728 needless re-fetches, and a 30 s+ black screen in the resolution loop. Require `Sec-Fetch-Dest: iframe\|document`, or `Accept: text/html` **with `Upgrade-Insecure-Requests`**; reject `Sec-Purpose` and `X-Requested-With: XMLHttpRequest`. Bound the phase too — a per-item timeout is not a phase budget. |
| 25 | **Assume a missing extractor means the extractor does not exist** | 2026-07-30. uqload produced no links and the conclusion was "CloudStream has no extractor for uqload.is". `UqloadIs` (mainUrl `https://uqload.is`) was already in `SharedExtractors.kt`; **`CimaNowPlugin` simply never called `registerSharedExtractors()`**, so `loadExtractor` had an empty registry and every server fell through to the sniffed stream. Check the plugin's registration before blaming the extractor. |
| 26 | **Believe "we inject nothing" without grepping every injection site** | 2026-07-30. `SPOOFING_JS` was switched off (§1) and the flow declared clean, while the interceptor still injected `ANTI_ANTI_BOT_JS` — the `document.write` wrapper of rule 3 — on any title whose payload contained `document.write('<script src=…')`. Dormant on the titles tested, fatal on the rest: white page, `delta=-49`, `[CW] document.write hook active` in our own log. Serve the main frame verbatim (`rewriteDocumentWrite = false`). |
| 27 | **Disable a flag that controls two behaviours** | 2026-07-30. `rewriteDocumentWrite` gated both the HTML rewrite (harmless — edits bytes, `document.write` stays native) and the `ANTI_ANTI_BOT_JS` injection (rule 3). Killing both cured the decoy and caused a blank page on the next server click, because the payload's own post-load `document.write` then wiped the document. Split them: rewrite on, hook off. |

### 0.2 Always do these

- **Keep both status channels.** The reader reports on chunked `console.log` *and* on `document.title`
  → `onReceivedTitle`. Two channels exist because a silent reader is otherwise undiagnosable: a
  wrapped `console.log` and a reader that never ran look identical from Kotlin.
- **Read `Reader channel report` first.** It prints the diagnosis rather than leaving it to inference:
  | verdict | meaning | action |
  |---|---|---|
  | both channels alive | reader ran; gate aborted upstream | read `li=` / `decoy=` / `dwNative=` in the `[rd]` diagnostics |
  | TITLE ONLY | `console.log` is being swallowed again | port the chunked exfil protocol to the title channel |
  | SILENT ON BOTH | the reader never executed | injection point / document serving — **not** the cipher |
  | console only | title writes blocked (gate now guards `Document.title`) | new channel needed |
- **Treat "no output" as the gate's designed behaviour, not as evidence of a decryption failure.**
  The pre-decrypt gate sets `_isB` and `return`s, emitting *nothing*. Absence of output tells you
  nothing about the cipher. Do not infer "the decryptor ran" from unrelated console warnings —
  Chrome's `document.write` intervention warnings about CDN scripts come from the page template and
  fire regardless.
- **Check the reader is still valid JS after editing it.** It is a Kotlin raw string, so a typo is a
  silent runtime failure that presents as SILENT ON BOTH — a false positive on our own diagnostic:
  ```bash
  # extract readerScript, substitute the $-interpolations, then:
  node --check /tmp/reader.js
  ```

### 0.3 Server-resolution phase (after decryption)

Decryption yields ~6 servers; each is resolved through `core.php` to an iframe, then handed to an
extractor or the sniffer. Two constraints here, both learned the hard way:

- **`SERVER_RESOLVE_TIMEOUT_MS` (20 s) is a per-server cap and is not sufficient on its own.** The
  resolution loop is a `coroutineScope`, i.e. a barrier — it waits for the slowest child even when
  every other server has already produced links. Measured 2026-07-29: five servers resolved in
  240 ms, then one straggler burned its full 20 s, which was **59 % of a 33.7 s `loadLinks`**.
- Hence **`STRAGGLER_GRACE_MS` (2.5 s)**: once the first playable link exists, the remaining servers
  get a short grace window and are then cancelled (`⏭ N link(s) ready — cancelling M …`). Do not
  remove this thinking the per-server cap covers it; it does not. Do not drop the grace window to
  zero either — a nearly-finished server should still be allowed to contribute an alternative source.
- `foundLinks` must stay a synchronized list: it is appended from all server coroutines concurrently
  and read by the watchdog.

### 0.4 Current known-good shape (verified 2026-07-29)

Sandbox decrypt **657 ms**, `captured li=6 fragLen=4805`, `hasLiDataIndex=true`, playback reached
first frame. `loadLinks` end-to-end ≈16 s, of which ~8.4 s is the site's own countdown guard — that
countdown is server-enforced and is the floor for this provider.

---

## 1. High-Level Flow Summary

To play videos on CimaNow, the client must traverse a multi-step redirection and token-generation pipeline:
1. **Movie/Episode Page:** Scraped for the `freex2line` URL (e.g., `https://rm.freex2line.online/loadon/?link=...`).
2. **Loadon Page:** Contains redirects to a `redirectingfree` page.
3. **Redirectingfree Page:** Sets cookies and redirects to a blog post template page (e.g., `https://rm.freex2line.online/2020/02/blog-post.html`).
4. **Blog Post Page:** Contains a configuration block (`window._0x_cfg`) with a dynamic token.
5. **countdown bypass:** The client must wait **11 seconds** before calling `get-link.php?token=...` to bypass the server-side countdown guard.
6. **Watch URL Retrieval:** `get-link.php` returns a JSON object containing the watch URL: `https://cimanow.cc/.../watching/?token=...`.
7. **Obfuscated Watch Page:** Fetching the watch page returns HTML containing an inline Javascript block that dynamically decrypts and writes the actual player DOM elements.

---

## 2. Architecture Overview — Three-Layer Defense

CimaNow changes encryption **every few hours** deliberately. Strategy-based detection (matching specific variable names/patterns) is fundamentally fragile — it tries to **replicate** decryption logic, which breaks every time the logic changes.

The truly format-agnostic approach: **execute the original decryption JavaScript in a sandbox** instead of replicating it. The JS sandbox is immune to all parameter/variable/operation changes because it runs the exact same code the site provides.

```
┌──────────────────────────────────────────────────────────────────┐
│                     decryptWatchHtml(html)                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─ Phase 1: KNOWN STRATEGIES (fast path, <50ms) ────────────┐  │
│  │  Tries to replicate known encryption formats by pattern    │  │
│  │  matching. BREAKS when format changes. Optimization only.  │  │
│  │                                                            │  │
│  │  AtobConfig  →  Version D  →  Version C  →  B  →  A       │  │
│  │                                                            │  │
│  │  Strategy pattern: canHandle(html)? → decrypt(html)        │  │
│  │  If any succeeds → Validator checks → return               │  │
│  └────────────────────────────────────────────────────────────┘  │
│                           ↓ (if null)                            │
│  ┌─ Phase 2: JS SANDBOX EXECUTION (format-agnostic, 1-5s) ───┐  │
│  │  Extracts the decryption script from the watch page and    │  │
│  │  executes it in a sandboxed WebView with polyfills.        │  │
│  │  IMMUNE to format changes — runs the original JS.          │  │
│  │                                                            │  │
│  │  polyfill: document.write → capture output                 │  │
│  │  polyfill: atob → Base64 decode                            │  │
│  │  polyfill: String.fromCharCode → identity                  │  │
│  │  Execute all scripts from watch page in order              │  │
│  │  Capture the decrypted HTML from document.write            │  │
│  └────────────────────────────────────────────────────────────┘  │
│                           ↓ (if null)                            │
│  ┌─ Phase 3: WEBVIEW NAVIGATION (existing fallback, 5-120s) ─┐  │
│  │  Full browser navigation flow: movie → freex2line → blog  │  │
│  │  → HMAC → get-link.php → watch page → extract servers     │  │
│  │  Works through Cloudflare, all JS, all anti-scraping.     │  │
│  └────────────────────────────────────────────────────────────┘  │
│                           ↓ (if null)                            │
│  return null → caller triggers error handling                    │
│                                                                  │
│  ┌─ Validator (runs after EVERY phase) ─────────────────────┐   │
│  │  Checks output for:                                       │   │
│  │  • data-index="..." attribute                             │   │
│  │  • data-id="..." attribute                                │   │
│  │  • HTML structural elements (<html>, <li>, <div>)         │   │
│  │  If invalid → try next phase                              │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### Why Phase 2 is truly immune to "every few hours" changes

| What CimaNow changes | Strategy (Phase 1) | Sandbox (Phase 2) |
|---|---|---|
| Variable names (e.g. `_ve0955` → `_x9k2m1`) | ❌ Breaks | ✅ Unaffected — JS doesn't care about names |
| Key formula (e.g. new math operation) | ❌ Breaks | ✅ Unaffected — JS computes it correctly |
| Delimiter (`*` → `#`) | ❌ Breaks | ✅ Unaffected — JS splits correctly |
| parseInt base (35 → 28) | ❌ Breaks | ✅ Unaffected — JS parses correctly |
| Array vs string payload | ❌ Breaks | ✅ Unaffected — JS handles both |
| New anti-tampering wrapper | ❌ Breaks | ✅ Unaffected — we run all scripts in order |
| `atob` → custom decoder | ❌ Breaks | ✅ Unaffected — runs the actual decoder |
| Completely novel algorithm | ❌ Breaks | ✅ Unaffected — runs the actual algorithm |

---

## 3. Phase 1: Known Strategies (Optimization Layer)

Each strategy implements:
```kotlin
interface DecryptionStrategy {
    val name: String
    fun canHandle(html: String): Boolean
    fun decrypt(html: String): String?
}
```

### 3.1 AtobConfigStrategy (Verified active — July 4, 2026)

**Detection:** `atob\s*\(\s*'([A-Za-z0-9+/=]+)'\s*\)` where decoded is `^\d+,\d+,\d+,\d+,[0-9a-f]+$`

**Example from live page:**
```javascript
var _cfg = atob('MjUwMDAsNjgwMDAsMjc0MCwzNSwxNmY4ZTk=').split(',');
// _cfg = ["25000", "68000", "2740", "35", "16f8e9"]
var _k = parseInt(_cfg[0]) + (parseInt(_cfg[4], 16) % parseInt(_cfg[1]));
// _k = 25000 + (0x16f8e9 % 68000) = 25000 + 9513 = 34513
var _pLoad = _ve0955.join('');   // ARRAY joined
var _chunks = _pLoad.split('*'); // delimiter = *
// each chunk:
//   atob(chunk) → split '-' → parseInt([1], 35) → (n - 2740) XOR 34513 → char
```

**Payload:** Find the large variable near the `atob()` call. Could be:
- Array `[...]` → extract all string literals, join (current)
- String `'...'` → extract string content directly

**Config fields:**
| Index | Field | Purpose | Example |
|-------|-------|---------|---------|
| 0 | base | Base value for key | 25000 |
| 1 | modulo | Modulo for hex hash | 68000 |
| 2 | subtract | Value to subtract from parsed int | 2740 |
| 3 | baseN | Radix for parseInt | 35 |
| 4 | hex | Hex fragment for key derivation | 16f8e9 |

### 3.2 Version D (July 2, 2026 — Page hash formula)

```javascript
var _pHsh = "0efb06ef";
var _kV = 50000 + (parseInt(_pHsh.substring(0,6), 16) % 100000);
// payload var name: "_" + _pHsh.substring(2, 7)
// delimiter: |
// each chunk: base36 parseInt, subtract 1337, XOR key
```

### 3.3 Version C (June 2026 — Dynamic local sum)

```javascript
var _x1 = 45132; var _x2 = 45132; // key = _x1 + _x2 + ... 
// delimiter: * or @
// each chunk: digits only, XOR key
```

### 3.4 Version B (Mid 2026 — Array sum)

```javascript
var _oArr = [39597, 39598, 39597]; // key = sum of elements
```

### 3.5 Version A (Early 2026 — Key difference)

```javascript
var _dk1 = 123456; var _dk2 = 100000; // key = _dk1 - _dk2
```

---

## 4. Phase 2: JS Sandbox Execution (Robustness Layer)

The core idea: **don't understand the encryption, just run it.**

### How it Works

1. **Extract** all `<script>` blocks from the watch page HTML (preserving order)
2. **Inject polyfills** before the scripts:
   - `document.write = function(html) { captured += html; }`
   - `document.open = function() {}` (no-op)
   - `document.close = function() {}` (no-op)
   - `atob = function(str) { return androidBase64Decode(str); }` — using Android's Base64
   - `decodeURIComponent(escape(str))` — identity function for ASCII output
   - `String.fromCharCode(n)` — pass through
3. **Execute** all scripts in a sandboxed WebView (about:blank, network disabled)
4. **Capture** the `document.write` output — this is the decrypted HTML
5. **Validate** with the shared Validator

### Why this works even with anti-tampering

The current anti-tampering script (Script 0) blocks `setAttribute('src', ...)` and `setAttribute('href', ...)` when devtools are detected. In our sandbox:
- No devtools → checks pass
- We don't set `src`/`href` attributes → even if checks triggered, no impact
- We only capture `document.write` output

### Implementation via NavigationEngine

The `NavigationEngine` (shared module) already has all the primitives:
```kotlin
// NavigationEngine.kt:835 — already exists
private suspend fun executeJsInWebView(webView: WebView, javascript: String): String?
```

We need to:
1. Expose a public method in `WebViewFlowHelper` or create a new `JsSandbox` utility
2. Provide the decryption scripts + polyfills as the JS to execute
3. Capture the result

```kotlin
// Proposed interface:
suspend fun decryptViaSandbox(watchHtml: String): String? {
    val scripts = extractAllScripts(watchHtml)
    val sandboxJs = buildPolyfill() + scripts.joinToString("\n") + "\nreturn window._captured;"
    return executeJsInWebView(sandboxJs)
}
```

### Timeout Budget
- JS execution: ~1-3 seconds for 3MB payload
- Total Phase 2 budget: 5 seconds max
- If timeout → fall through to Phase 3

---

## 5. Phase 3: WebView Navigation (Ultimate Fallback)

Already exists via `WebViewFlowHelper.navigateMovieToWatchPage()`. Full browser navigation:
- Loads movie page
- Waits for freex2line redirect
- Passes through Cloudflare challenges
- Follows redirect chain (loadon → redirectingfree → blog-post)
- Waits for countdown (11s + JS execution)
- Captures watching URL via interceptor
- Renders watch page and extracts servers via JS

**Time:** 20-120 seconds
**Resilience:** Works through ALL anti-scraping measures
**Cost:** Slow, user-facing browser window

---

## 6. Validator (Shared)

```kotlin
fun isValidDecryptedHtml(output: String): Boolean {
    if (output.isBlank()) return false
    // Must contain server list items
    val hasServerData = output.contains("data-index=") && output.contains("data-id=")
    // Must look like HTML
    val hasHtmlStructure = output.contains("<html", ignoreCase = true) ||
                           output.contains("<!DOCTYPE", ignoreCase = true) ||
                           output.contains("<li")
    return hasServerData && hasHtmlStructure
}
```

If Validator fails → log the output snippet and try next phase.

---

## 7. Adding a New Strategy (when format changes)

New strategies are OPTIMIZATIONS, not requirements. The sandbox (Phase 2) will still work.

To add a strategy for better performance:

1. **Capture a live watch page:**
   ```bash
   python3 CimaNowProviderV2/test/fetch_and_decrypt.py
   ```
   Saves raw HTML to `/tmp/cimanow_watch_raw.html`.

2. **Analyze** the script blocks for the new pattern — look at the JS that runs in the sandbox.

3. **Create the strategy:**
   ```kotlin
   class MyNewStrategy : DecryptionStrategy {
       override val name = "MyNewStrategy"
       override fun canHandle(html: String): Boolean = /* signature regex */
       override fun decrypt(html: String): String? = /* decryption logic */
   }
   ```

4. **Register** in the strategy list at appropriate priority.

5. **Update this document** with the new format details.

---

## 8. Troubleshooting

| Symptom | Root Cause | Action |
|---------|-----------|--------|
| Phase 1 all fail, Phase 2 succeeds | New encryption format | Optional: add new strategy for speed |
| Phase 1 and 2 both fail | Script structure changed (e.g., anti-tampering blocks execution) | Check polyfills; inspect raw scripts |
| Phase 2 timeouts | 3MB payload processing slow in WebView | Increase timeout; check for infinite loops |
| All phases fail | Network/redirect chain issue | Check blog-post.html HMAC flow; verify cookies |
| Validator rejects Phase 2 output | Decryption partially worked but format changed | Log the output for analysis |
| `N/A` | `N/A` | `N/A` |

### Diagnostic: When sandbox fails

Check what the JS sandbox captured:
```kotlin
// Log the captured output regardless of validation
Log.d(TAG, "Sandbox output (first 2000): ${sandboxOutput?.take(2000)}")
```

If output looks like HTML but missing `data-index`, the decryption worked but the page structure changed. If output is garbage, the JS execution failed.

---

## 9. Historical Format Timeline

| Date | Strategy | Key Source | Delimiter | parseInt Base | Math |
|------|----------|-----------|-----------|---------------|------|
| Early 2026 | A | `_dk1 - _dk2` | `@` | 10 | XOR key |
| Mid 2026 | B | `_oArr.sum()` | `@` / `*` | 10 | XOR key |
| June 2026 | C | `_x1 + _x2 + ...` | `*` | 10 | XOR key |
| July 2 | D | `_pHsh` hex hash | `\|` | 36 | `(n-1337) XOR key` |
| July 4 | AtobConfig | atob CSV config | `*` | 35 | `(n-2740) XOR key` |

---

## 10. File Reference

| File | Purpose |
|------|---------|
| `CimaNowProvider.kt` — `decryptWatchHtml()` | Three-phase dispatcher |
| `CimaNowProvider.kt` — strategy classes | Known format replicators (optimization) |
| `CimaNowProvider.kt` — `decryptViaSandbox()` | JS sandbox execution (robustness) |
| `shared/.../NavigationEngine.kt` — `executeJsInWebView()` | WebView JS evaluation primitive |
| `test/fetch_and_decrypt.py` | Full redirect chain + decryption test |
| `test/DECRYPTION_REPORT.md` | Detailed analysis notes |

---

## 11. Design Decision Record

### Why not just use WebView navigation for everything?
Speed. Phase 3 takes 20-120 seconds and shows a browser window. Phase 1 takes <50ms. Phase 2 takes 1-5s silently.

### Why not use Rhino/Duktape instead of WebView sandbox?
WebView is already available in CloudStream (NavigationEngine). Adding Rhino (~5MB) or Duktape (JNI) adds dependency complexity without benefit — the WebView sandbox achieves the same result.

### Why keep strategies at all if the sandbox handles everything?
Performance and debugging. Strategies provide fast paths for known formats and their logs help diagnose what format is currently active.

### What if the page has obfuscated `document.write`?
The JavaScript always calls `document.write(decodeURIComponent(escape(decrypted)))` in some form. Our polyfill captures ALL calls to `document.write`. Even if the method reference is obfuscated (e.g., `document[_add('0x3')]`), the actual `document.write` function is what gets invoked, and our polyfill intercepts it.
