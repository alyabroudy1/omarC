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
            NavigationStep.LoadUrl(movieUrl),

            // Step 1: Wait for the JS on the movie page to auto-navigate to freex2line.online
            // (v6 auto-redirects; no manual button click needed)
            NavigationStep.WaitForUrl("freex2line\\.online", timeoutMs = 45_000L, abortOnFailure = false),

            // Step 2: If auto-navigation didn't happen, try clicking the button manually
            NavigationStep.WaitForSelector("a[href*='freex2line']", timeoutMs = 5_000L, abortOnFailure = false),
            NavigationStep.ClickElement("a[href*='freex2line']", timeoutMs = 3_000L, abortOnFailure = false),

            // Step 3: Wait for freex2line.online (should be navigating now)
            NavigationStep.WaitForUrl("freex2line\\.online", timeoutMs = 15_000L, abortOnFailure = false),

            // Step 4: Let Cloudflare challenge solve + full redirect chain play out:
            //   freex2line.online → CF challenge → href.li → redirectingfree → blog-post.html
            //   (CF JS challenge takes 5-15s, then redirects happen)
            NavigationStep.WaitForDelay(20_000L),

            // Step 5: Wait for blog-post.html (the final landing before get-link.php)
            NavigationStep.WaitForUrl("blog-post\\.html", timeoutMs = 60_000L, abortOnFailure = false),

            // Step 6: Give time for blog-post.html's JS to:
            //   - Gather browser fingerprint
            //   - Call get-link.php?request_id=...&hmac_token=...&ch=...&fp=...
            //   - Navigate to /watching/?token=...
            NavigationStep.WaitForDelay(10_000L),

            // Step 7: Dismiss any consent popups
            NavigationStep.ExecuteJs(javascript = JS_DISMISS_CONSENT, key = "consent"),

            // Step 8: Navigate to the watching URL captured from get-link.php
            // (get-link.php returns the watching page URL; the interceptor captures and stores it)
            NavigationStep.NavigateToWatchingUrl(abortOnFailure = true),

            // Step 9: Let the watching page render
            NavigationStep.WaitForDelay(8_000L),

            // Step 10: Debug DOM
            NavigationStep.ExecuteJs(javascript = JS_DEBUG_DOM, key = "debug_dom"),

            // Step 11: Extract server list
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_SERVERS, key = "server_list"),

            // Step 12: Fetch iframes via core.php
            NavigationStep.ExecuteJs(javascript = JS_FETCH_IFRAMES, key = "fetch_initiated"),

            // Step 13: Wait for fetches
            NavigationStep.WaitForDelay(5_000L),

            // Step 14: Collect iframe results
            NavigationStep.ExecuteJs(
                javascript = "(function(){ return window._serverResults || '[]'; })();",
                key = "iframe_results"
            ),

            // Step 15: Extract download links
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_DOWNLOADS, key = "download_links"),

            // Step 16: Save watch page HTML
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
    var found = false;
    var candidates = document.querySelectorAll('button, a, .btn, [role="button"], .modal-footer a, .modal-footer button, .popup-content a, .popup-content button');
    var keywords = ['continue', 'accept', 'allow', 'agree', 'confirm', 'close', 'dismiss', 'ok', 'got it', 'أوافق', 'متابعة', 'موافق'];
    for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        var text = (el.innerText || el.textContent || '').trim().toLowerCase();
        if (text.length === 0 || text.length > 30) continue;
        for (var k = 0; k < keywords.length; k++) {
            if (text.indexOf(keywords[k]) !== -1) {
                var rect = el.getBoundingClientRect();
                var visible = rect.width > 0 && rect.height > 0 && el.offsetParent !== null;
                console.log('[Nav] consent candidate:', el.tagName, 'text="' + text + '" visible=' + visible);
                if (visible) {
                    try { el.click(); console.log('[Nav] clicked consent:', text); found = true; } catch(e) {}
                }
                break;
            }
        }
    }
    if (!found) {
        var closeSelectors = ['.close', '.close-btn', '.modal-close', '.popup-close', '[onclick*="close"]', '.fc-cta-consent', '.fc-button', '.cc-btn', '.agree-btn', '#continue', '.continue', '.accept-btn'];
        for (var i = 0; i < closeSelectors.length; i++) {
            var els = document.querySelectorAll(closeSelectors[i]);
            for (var j = 0; j < els.length; j++) {
                var rect = els[j].getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0 && els[j].offsetParent !== null) {
                    try { els[j].click(); console.log('[Nav] clicked close btn:', closeSelectors[i]); found = true; } catch(e) {}
                    break;
                }
            }
            if (found) break;
        }
    }
    return found ? 'consent_dismissed' : 'no_consent';
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
    var items = document.querySelectorAll('#watch li, li[data-index], [data-index]');
    var servers = [];
    for (var i = 0; i < items.length; i++) {
        var idx = items[i].getAttribute('data-index') || '';
        var id = items[i].getAttribute('data-id') || '';
        var name = (items[i].textContent || '').trim().slice(0, 60);
        servers.push({index: idx, id: id, name: name});
        console.log('[Nav] Server #'+i+': idx='+idx+' id='+id+' name="'+name+'"');
    }
    return JSON.stringify(servers);
})();
        """.trimIndent()

        val JS_FETCH_IFRAMES = """
(function(){
    var items = document.querySelectorAll('#watch li, li[data-index], [data-index]');
    var baseUrl = window.location.origin;
    var results = [];
    var done = 0;
    for (var i = 0; i < Math.min(items.length, 10); i++) {
        var idx = items[i].getAttribute('data-index') || '';
        var id = items[i].getAttribute('data-id') || '';
        var name = (items[i].textContent || '').trim().slice(0, 60);
        var ajaxUrl = baseUrl + '/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=' + idx + '&id=' + id;
        console.log('[Nav] Fetching server ' + name + ': ' + ajaxUrl);
        (function(srvName, srvIdx, url) {
            fetch(url, {credentials: 'include', headers: {'X-Requested-With': 'XMLHttpRequest', 'Referer': window.location.href}})
                .then(function(r) { return r.text(); })
                .then(function(html) {
                    var iframeSrc = '';
                    var match = html.match(/<iframe[^>]+src=["']([^"']+)["']/);
                    if (match) iframeSrc = match[1];
                    console.log('[Nav] Server ' + srvName + ' iframe: ' + iframeSrc);
                    results.push({name: srvName, index: srvIdx, iframe: iframeSrc, responseLength: html.length});
                    done++;
                    if (done === Math.min(document.querySelectorAll('#watch li, li[data-index], [data-index]').length, 10)) {
                        window._serverResults = JSON.stringify(results);
                    }
                })
                .catch(function(err) {
                    console.log('[Nav] Server ' + srvName + ' error: ' + err.message);
                    results.push({name: srvName, index: srvIdx, iframe: '', error: err.message});
                    done++;
                });
        })(name, idx, ajaxUrl);
    }
    return 'fetching_' + Math.min(items.length, 10) + '_servers';
})();
        """.trimIndent()

        val JS_EXTRACT_DOWNLOADS = """
(function(){
    var links = document.querySelectorAll('#download li a[href], a[href*="download"], a[href*="dl"], .download-links a[href]');
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
    }
}