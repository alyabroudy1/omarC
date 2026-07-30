# CimaNow Watch Page Decryption Handover & Cheatsheet

> **⚠️⚠️ CURRENT IMPLEMENTATION (2026-07-30, WORKING) — WE NO LONGER DECRYPT.**
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
> 3b. **Resolution order: embeds → extractors, streams only as fallback.** `fallbackExtractIframe()` on
>    each captured iframe gives the full quality ladder; the sniffed stream is one ABR-chosen rendition.
>    See §5.
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
> Now `onCreateWindow` hands over a detached sink WebView that answers every request with an empty
> body: the page gets a live window whose `closed` stays false, no ad content is ever fetched, nothing
> is shown, the main frame is untouched, and the sinks are destroyed with the session. **Returning
> `true` from `onCreateWindow` is not "allow popups" — filling the transport is.**
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
> ### Diagnostics to read first, in this order
>
> 1. `Spoofing JS NOT injected (pristine page context)` — the page context is clean.
> 2. `onPageFinished` verdict — **`🚩 DECOY SERVED`** (delta 47) / "wrote nothing" (<200) / `Decryptor
>    produced content (delta=…)`. A blank screen and a decryption failure are indistinguishable
>    without this line.
> 3. `Popup honoured into a blank sink window` — the ad gate will not fire.
> 4. **`🎯 CAPTURED EMBED`** — the good path. Followed by `Resolving embed via extractor` and
>    `>>> EMBED LINK`, which means the full quality ladder came from the player's own params.
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
