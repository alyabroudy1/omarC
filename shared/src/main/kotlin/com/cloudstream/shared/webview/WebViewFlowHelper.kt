package com.cloudstream.shared.webview

import com.cloudstream.shared.logging.ProviderLogger
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener

class WebViewFlowHelper(
    private val navigationEngine: NavigationEngine
) {
    private val TAG = "WebViewFlowHelper"

    data class Config(
        val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        val overallTimeoutMs: Long = 120_000L,
        val allowedDomains: List<String> = emptyList(),
        val destinationLockPatterns: List<String> = emptyList(),
        val mode: Mode = Mode.FULLSCREEN
    )

    data class ServerInfo(
        val name: String,
        val index: String,
        val id: String,
        val iframeUrl: String = ""
    )

    data class DownloadInfo(
        val name: String,
        val url: String
    )

    data class FlowResult(
        val success: Boolean,
        val finalUrl: String,
        val servers: List<ServerInfo>,
        val downloads: List<DownloadInfo>,
        val watchPageHtml: String?,
        val error: String?
    )

    suspend fun navigateMovieToWatchPage(
        movieUrl: String,
        config: Config = Config()
    ): FlowResult {
        ProviderLogger.i(TAG, "navigateMovieToWatchPage", "Starting WebView navigation flow",
            "movieUrl" to movieUrl.take(100),
            "allowedDomains" to config.allowedDomains.joinToString(","),
            "destinationLockPatterns" to config.destinationLockPatterns.joinToString(","))

        val destinationLockRegexes = config.destinationLockPatterns.map { Regex(it) }

        this.movieUrl = movieUrl
        val steps = buildStepList()

        val navResult = navigationEngine.execute(
            steps = steps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = config.overallTimeoutMs,
            allowedDomains = config.allowedDomains.toSet(),
            destinationLockPatterns = destinationLockRegexes
        )

        ProviderLogger.i(TAG, "navigateMovieToWatchPage", "Navigation result",
            "success" to navResult.success.toString(),
            "finalUrl" to navResult.finalUrl.take(100),
            "completedSteps" to navResult.completedSteps.toString(),
            "failedAt" to (navResult.failedAtStep?.toString() ?: "none"),
            "error" to (navResult.error ?: "none"),
            "extractedKeys" to navResult.extractedHtml.keys.joinToString(","))

        if (!navResult.success) {
            return FlowResult(
                success = false,
                finalUrl = navResult.finalUrl,
                servers = emptyList(),
                downloads = emptyList(),
                watchPageHtml = navResult.extractedHtml["watch_page_raw"],
                error = "Navigation failed at step ${navResult.failedAtStep}: ${navResult.error}"
            )
        }

        val servers = parseServers(navResult)
        val iframeResults = fetchIframeUrls(navResult, config)
        val serversWithIframes = servers.mapIndexed { index, server ->
            server.copy(iframeUrl = iframeResults.getOrElse(index) { "" })
        }
        val downloads = parseDownloads(navResult)
        val watchPageHtml = navResult.extractedHtml["watch_page_raw"]

        ProviderLogger.i(TAG, "navigateMovieToWatchPage", "Extraction complete",
            "servers" to serversWithIframes.size.toString(),
            "serversWithIframes" to serversWithIframes.count { it.iframeUrl.isNotBlank() }.toString(),
            "downloads" to downloads.size.toString(),
            "watchHtmlLen" to (watchPageHtml?.length ?: 0).toString())

        serversWithIframes.forEach { s ->
            ProviderLogger.d(TAG, "navigateMovieToWatchPage", "  server: '${s.name}' idx=${s.index} id=${s.id} iframe=${s.iframeUrl.take(80)}")
        }
        downloads.forEach { d ->
            ProviderLogger.d(TAG, "navigateMovieToWatchPage", "  download: '${d.name}' url=${d.url.take(80)}")
        }

        return FlowResult(
            success = true,
            finalUrl = navResult.finalUrl,
            servers = serversWithIframes,
            downloads = downloads,
            watchPageHtml = watchPageHtml,
            error = null
        )
    }

    private var movieUrl: String = ""

    private fun buildStepList(): List<NavigationStep> {
        return listOf(
            // Step 0: Load the movie page
            // All navigation is controlled manually via the redirect confirmation dialog.
            // The engine shows a dialog for each main-frame redirect: user taps Allow/Block.
            NavigationStep.LoadUrl(movieUrl),

            // Step 1: Keep the WebView alive for up to 10 minutes so the user can
            // manually navigate through redirects (freex → href.li → redirectingfree →
            // blog-post → get-link.php → /watching/?token=...) using the confirmation dialogs.
            NavigationStep.WaitForDelay(600_000L),

            // Step 2: Debug DOM — log DOM state for diagnostics
            NavigationStep.ExecuteJs(javascript = JS_DEBUG_DOM, key = "debug_dom"),

            // Step 3: Extract server list (if on watching page)
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_SERVERS, key = "server_list"),

            // Step 4: Fetch iframes via core.php (if on watching page)
            NavigationStep.ExecuteJs(javascript = JS_FETCH_IFRAMES, key = "fetch_initiated"),

            // Step 5: Wait for fetches
            NavigationStep.WaitForDelay(8_000L),

            // Step 6: Collect iframe results
            NavigationStep.ExecuteJs(
                javascript = "(function(){ return window._serverResults || '[]'; })();",
                key = "iframe_results"
            ),

            // Step 7: Extract download links
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_DOWNLOADS, key = "download_links"),

            // Step 8: Save final watch page HTML for debugging
            NavigationStep.ExtractHtml(key = "watch_page_raw")
        )
    }

    private fun parseServers(result: NavigationResult): List<ServerInfo> {
        val raw = result.extractedHtml["server_list"] ?: return emptyList()
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val arr = JSONArray(cleaned)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                ServerInfo(
                    name = obj.optString("name", ""),
                    index = obj.optString("index", ""),
                    id = obj.optString("id", "")
                )
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "parseServers", "Failed: ${e.message}", "raw" to raw.take(200))
            emptyList()
        }
    }

    private fun fetchIframeUrls(result: NavigationResult, config: Config): Map<Int, String> {
        val raw = result.extractedHtml["iframe_results"] ?: return emptyMap()
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val arr = JSONArray(cleaned)
            (0 until arr.length()).associate { i ->
                i to arr.getJSONObject(i).optString("iframe", "")
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "fetchIframeUrls", "Failed: ${e.message}", "raw" to raw.take(200))
            emptyMap()
        }
    }

    private fun parseDownloads(result: NavigationResult): List<DownloadInfo> {
        val raw = result.extractedHtml["download_links"] ?: return emptyList()
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val arr = JSONArray(cleaned)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                DownloadInfo(
                    name = obj.optString("name", ""),
                    url = obj.optString("url", "")
                )
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "parseDownloads", "Failed: ${e.message}", "raw" to raw.take(200))
            emptyList()
        }
    }

    companion object {
        val JS_DISMISS_CONSENT = """
(function() {
    var log = [];
    var found = false;
    function clickEl(el, label) {
        try {
            el.click();
            log.push('clicked:' + label);
            return true;
        } catch(e) { log.push('err:' + label + ':' + e.message); return false; }
    }
    // 1. Try Swal API directly
    try {
        if (typeof window.Swal !== 'undefined' && typeof window.Swal.clickConfirm === 'function') {
            window.Swal.clickConfirm();
            log.push('swal_clickConfirm');
            found = true;
        }
    } catch(e) { log.push('swal_api_err:' + e.message); }
    // 2. Click any .swal2-confirm button regardless of text
    var swalBtns = document.querySelectorAll('.swal2-confirm');
    for (var s = 0; s < swalBtns.length; s++) {
        var rect = swalBtns[s].getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0 && swalBtns[s].offsetParent !== null) {
            if (clickEl(swalBtns[s], 'swal2-confirm')) found = true;
        }
    }
    // 3. Keyword-based fallback
    if (!found) {
        var candidates = document.querySelectorAll('button, a, .btn, [role="button"], .modal-footer a, .modal-footer button, .popup-content a, .popup-content button');
        var keywords = ['continue', 'accept', 'allow', 'agree', 'confirm', 'close', 'dismiss', 'ok', 'got it', 'أوافق', 'متابعة', 'موافق', 'موافقة', 'دخول', 'تأكيد'];
        for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            var text = (el.innerText || el.textContent || '').trim().toLowerCase();
            if (text.length === 0 || text.length > 40) continue;
            for (var k = 0; k < keywords.length; k++) {
                if (text.indexOf(keywords[k]) !== -1) {
                    var r2 = el.getBoundingClientRect();
                    if (r2.width > 0 && r2.height > 0 && el.offsetParent !== null) {
                        if (clickEl(el, text)) found = true;
                    }
                    break;
                }
            }
        }
    }
    // 4. Close any swal2 container via Escape key
    try {
        var cont = document.querySelector('.swal2-container');
        if (cont) {
            document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', keyCode: 27, bubbles: true}));
            log.push('escape_dispatched');
            found = true;
        }
    } catch(e) { log.push('escape_err:' + e.message); }
    return (found ? 'consent_dismissed' : 'no_consent') + '|' + log.join(',');
})();
        """.trimIndent()

        val JS_VISIBLE_SERVER_LINK_CONDITION = """
(function(){
    try {
        var links = document.querySelectorAll('a[href*="get-link"], a[href*="download"], a[href*="watch"], a.continue-btn, a[href*="server"], a[href*="link"], button[class*="btn"]');
        for (var i = 0; i < links.length; i++) {
            var r = links[i].getBoundingClientRect();
            var href = links[i].href || '';
            var isDisabled = links[i].disabled || links[i].classList.contains('disabled') || links[i].getAttribute('aria-disabled') === 'true' || (links[i].style.pointerEvents === 'none');
            
            if (r.width > 0 && r.height > 0 && links[i].offsetParent !== null && !isDisabled
                && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
                console.log('[Nav] found visible & enabled server link:', href);
                return true;
            }
        }
        return false;
    } catch(e) { return false; }
})()
        """.trimIndent()

        val JS_FIND_SERVER_LINK = """
(function() {
    var links = document.querySelectorAll('a[href*="get-link"], a[href*="download"], a[href*="watch"], a.continue-btn, a[href*="server"], a[href*="link"], button[class*="btn"]');
    for (var i = 0; i < links.length; i++) {
        var rect = links[i].getBoundingClientRect();
        var visible = rect.width > 0 && rect.height > 0 && links[i].offsetParent !== null;
        var isDisabled = links[i].disabled || links[i].classList.contains('disabled') || links[i].getAttribute('aria-disabled') === 'true' || (links[i].style.pointerEvents === 'none');
        
        console.log('[Nav] server link candidate:', links[i].href, 'visible=' + visible, 'disabled=' + isDisabled);
        
        if (visible && !isDisabled) {
            var href = links[i].href || '';
            if (href.indexOf('viiqkzqv') !== -1 || href.indexOf('wildsino') !== -1) {
                console.log('[Nav] skipping ad link:', href);
                continue;
            }
            console.log('[Nav] clicking server link:', href);
            try { links[i].click(); return 'clicked:' + href; } catch(e) { return 'error:' + e.message; }
        }
    }
    var allLinks = document.querySelectorAll('a[href]:not([href=""]):not([href^="javascript"])');
    for (var i = 0; i < allLinks.length; i++) {
        var rect = allLinks[i].getBoundingClientRect();
        var href = allLinks[i].href || '';
        var isDisabled = allLinks[i].disabled || allLinks[i].classList.contains('disabled') || (allLinks[i].style.pointerEvents === 'none');
        
        if (rect.width > 0 && rect.height > 0 && allLinks[i].offsetParent !== null && !isDisabled 
            && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
            console.log('[Nav] fallback clicking:', href);
            try { allLinks[i].click(); return 'clicked_fallback:' + href; } catch(e) {}
        }
    }
    return 'no_server_link';
})();
        """.trimIndent()

        val JS_DEBUG_DOM = """
(function(){
    var uls = document.querySelectorAll('ul');
    console.log('[Nav] UL count:', uls.length);
    for (var i = 0; i < uls.length; i++) {
        console.log('[Nav] UL #'+i+': id="'+uls[i].id+'" class="'+uls[i].className+'" children='+uls[i].children.length);
    }
    var iframes = document.querySelectorAll('iframe');
    console.log('[Nav] IFRAME count:', iframes.length);
    for (var i = 0; i < iframes.length; i++) {
        console.log('[Nav] IFRAME #'+i+': src="'+(iframes[i].src||'none')+'"');
    }
    var watchLis = document.querySelectorAll('[data-index], li[class*="server"], li[class*="watch"]');
    console.log('[Nav] Server LI count:', watchLis.length);
    for (var i = 0; i < watchLis.length; i++) {
        var a = watchLis[i].querySelector('a');
        console.log('[Nav] Server #'+i+': text="'+(watchLis[i].textContent||'').trim().slice(0,50)+'" href="'+(a?a.href:'none')+'"');
    }
    return 'debug_done';
})();
        """.trimIndent()

        val JS_EXTRACT_SERVERS = """
(function(){
    var items = document.querySelectorAll('li[aria-label="embed"], li.embed-item, ul.tabcontent#watch li, #watch li[aria-label="embed"], #watch li, li[data-index], [data-id], li[data-post]');
    var servers = [];
    for (var i = 0; i < items.length; i++) {
        var idx = items[i].getAttribute('data-index') || items[i].getAttribute('data-post') || items[i].getAttribute('data-server') || '';
        var id = items[i].getAttribute('data-id') || items[i].getAttribute('data-server-id') || '';
        var a = items[i].querySelector('a') || items[i];
        var href = a.href || items[i].getAttribute('data-url') || items[i].getAttribute('data-src') || '';
        var name = (a.textContent || items[i].textContent || items[i].getAttribute('aria-label') || '').trim().slice(0, 60);
        servers.push({index: idx, id: id, name: name, href: href});
        console.log('[Nav] Server #'+i+': idx='+idx+' id='+id+' name="'+name+'" href="'+href+'"');
    }
    if (servers.length === 0) {
        var allLinks = document.querySelectorAll('a[href*="get-link"], a[href*="download"], a[href*="watch"]');
        for (var i = 0; i < Math.min(allLinks.length, 5); i++) {
            var name = (allLinks[i].textContent || '').trim().slice(0, 60);
            var href = allLinks[i].href || '';
            servers.push({index: '', id: '', name: name, href: href});
            console.log('[Nav] Fallback link #'+i+': name="'+name+'" href="'+href+'"');
        }
    }
    return JSON.stringify(servers);
})();
        """.trimIndent()

        val JS_FETCH_IFRAMES = """
(function(){
    var items = document.querySelectorAll('li[aria-label="embed"], li.embed-item, ul.tabcontent#watch li, #watch li[aria-label="embed"], #watch li, li[data-index], [data-id], li[data-post]');
    var baseUrl = window.location.origin;
    var results = [];
    var done = 0;
    for (var i = 0; i < Math.min(items.length, 10); i++) {
        var idx = items[i].getAttribute('data-index') || items[i].getAttribute('data-post') || '';
        var id = items[i].getAttribute('data-id') || '';
        var a = items[i].querySelector('a') || items[i];
        var name = (a.textContent || items[i].textContent || items[i].getAttribute('aria-label') || '').trim().slice(0, 60);
        
        // First check if there's already an iframe inside the li (rendered by page JS)
        var iframe = items[i].querySelector('iframe');
        if (iframe) {
            var iframeSrc = iframe.getAttribute('data-src') || iframe.src || '';
            console.log('[Nav] Server ' + name + ' direct iframe: ' + iframeSrc);
            results.push({name: name, index: idx, iframe: iframeSrc, direct: true});
            done++;
            if (done === Math.min(items.length, 10)) { window._serverResults = JSON.stringify(results); }
            continue;
        }
        
        // Fall back to AJAX via core.php
        var ajaxUrl = baseUrl + '/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=' + idx + '&id=' + id;
        console.log('[Nav] Fetching server ' + name + ' via AJAX: ' + ajaxUrl);
        (function(srvName, srvIdx, url) {
            fetch(url, {credentials: 'include', headers: {'X-Requested-With': 'XMLHttpRequest', 'Referer': window.location.href}})
                .then(function(r) { return r.text(); })
                .then(function(html) {
                    var iframeSrc = '';
                    var match = html.match(/<iframe[^>]+src=["']([^"']+)["']/);
                    if (match) iframeSrc = match[1];
                    console.log('[Nav] Server ' + srvName + ' AJAX iframe: ' + iframeSrc);
                    results.push({name: srvName, index: srvIdx, iframe: iframeSrc, direct: false, responseLength: html.length});
                    done++;
                    if (done === Math.min(items.length, 10)) { window._serverResults = JSON.stringify(results); }
                })
                .catch(function(err) {
                    console.log('[Nav] Server ' + srvName + ' AJAX error: ' + err.message);
                    results.push({name: srvName, index: srvIdx, iframe: '', direct: false, error: err.message});
                    done++;
                });
        })(name, idx, ajaxUrl);
    }
    return 'fetching_' + Math.min(items.length, 10) + '_servers';
})();
        """.trimIndent()

        val JS_EXTRACT_DOWNLOADS = """
(function(){
    var links = document.querySelectorAll('li[aria-label="quality"] a[href], #download li a[href], a[href*="download"], a[href*="dl"], .download-links a[href]');
    var downloads = [];
    for (var i = 0; i < links.length; i++) {
        var name = (links[i].textContent || '').trim().slice(0, 60);
        var href = links[i].href || '';
        if (href && name) {
            downloads.push({name: name, url: href});
            console.log('[Nav] Download #'+i+': name="'+name+'" url="'+href+'"');
        }
    }
    return JSON.stringify(downloads);
})();
        """.trimIndent()

        /**
         * Extracts ALL iframes currently in the page DOM (including ones injected by the
         * page's own JS, e.g. the VK embed that auto-plays). This is the V1 fallback
         * `iframe` selector. Returns [{name, iframe, src}].
         */
        val JS_EXTRACT_DIRECT_IFRAMES = """
(function(){
    var iframes = document.querySelectorAll('iframe');
    var results = [];
    for (var i = 0; i < iframes.length; i++) {
        var src = iframes[i].getAttribute('data-src') || iframes[i].src || '';
        if (!src || src.indexOf('about:blank') !== -1) continue;
        var parent = iframes[i].parentElement || {};
        var name = (parent.getAttribute && parent.getAttribute('aria-label')) || parent.className || ('iframe#' + i);
        results.push({name: String(name).slice(0, 40), iframe: src, src: src});
        console.log('[Nav] Direct iframe #' + i + ': name="' + name + '" src="' + src + '"');
    }
    return JSON.stringify(results);
})();
        """.trimIndent()

        /**
         * Diagnostic snapshot of the watching page state. Logs (to console, captured by
         * NavEngineJS) the JS-visible userAgent, cookies, availability of consent libs
         * (Swal / jQuery / $.cookie), and any visible modal/dialog buttons — so we can tell
         * whether the page reached the consent/server-list stage or bounced to /home.
         */
        val JS_DIAGNOSE_WATCHING = """
(function(){
    try {
        var diag = {
            url: window.location.href,
            ua: navigator.userAgent,
            cookie: (document.cookie || '').slice(0, 300),
            hasSwal: typeof window.Swal !== 'undefined',
            hasJQuery: typeof window.jQuery !== 'undefined',
            hasJQueryCookie: (typeof window.jQuery !== 'undefined' && typeof window.jQuery.cookie !== 'undefined'),
            watchItems: document.querySelectorAll('li[aria-label="embed"], #watch li, li[data-index], [data-index], li[aria-label="quality"], a[href*="download"]').length,
            visibleDialogs: 0,
            dialogButtons: [],
            swal2Confirm: '',
            swal2Title: '',
            swal2Html: '',
            iframes: []
        };
        var modals = document.querySelectorAll('.swal2-container, .swal2-modal, .swal2-popup, .modal, .popup, .consent, [role="dialog"]');
        for (var m = 0; m < modals.length; m++) {
            var mr = modals[m].getBoundingClientRect();
            if (mr.width > 0 && mr.height > 0 && modals[m].offsetParent !== null) {
                diag.visibleDialogs++;
                var conf = modals[m].querySelector('.swal2-confirm');
                if (conf) diag.swal2Confirm = (conf.innerText || conf.textContent || '').trim().slice(0, 30);
                var title = modals[m].querySelector('.swal2-title');
                if (title) diag.swal2Title = (title.innerText || title.textContent || '').trim().slice(0, 60);
                var htmlC = modals[m].querySelector('.swal2-html-container');
                if (htmlC) diag.swal2Html = (htmlC.innerText || htmlC.textContent || '').trim().slice(0, 120);
                var btns = modals[m].querySelectorAll('button, a, [role="button"], .swal2-confirm');
                for (var b = 0; b < btns.length; b++) {
                    var t = (btns[b].innerText || btns[b].textContent || '').trim();
                    if (t.length > 0 && t.length < 40) diag.dialogButtons.push(t);
                }
            }
        }
        diag.allSpans = document.querySelectorAll('#watch li span, #watch li a, #watch li').length;
        diag.watchHtml = (document.querySelector('#watch') || {}).innerHTML ? (document.querySelector('#watch').innerHTML.slice(0, 1200) || 'no_watch') : 'no_watch';
        var ifr = document.querySelectorAll('iframe');
        for (var i = 0; i < Math.min(ifr.length, 8); i++) {
            diag.iframes.push((ifr[i].getAttribute('data-src') || ifr[i].src || 'none').slice(0, 120));
        }
        return 'DIAG_JSON:' + JSON.stringify(diag);
    } catch(e) {
        return 'diag_error:' + e.message;
    }
})();
        """.trimIndent()
    }
}