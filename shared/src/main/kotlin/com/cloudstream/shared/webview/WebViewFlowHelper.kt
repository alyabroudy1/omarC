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
        val error: String?,
        val postId: String = ""
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

        var servers = parseServers(navResult)
        val iframeResults = fetchIframeUrls(navResult, config)
        var serversWithIframes = servers.mapIndexed { index, server ->
            server.copy(iframeUrl = iframeResults.getOrElse(index) { "" })
        }
        var downloads = parseDownloads(navResult)
        if (servers.isEmpty() && downloads.isEmpty()) {
            val combinedRaw = navResult.extractedHtml["extracted_all"] ?: ""
            if (combinedRaw.isNotBlank()) {
                ProviderLogger.d(TAG, "navigateMovieToWatchPage", "Trying combined extraction fallback")
                val combinedParsed = parseCombinedExtraction(combinedRaw)
                if (combinedParsed != null) {
                    servers = combinedParsed.servers
                    serversWithIframes = servers
                    downloads = combinedParsed.downloads
                }
            }
        }
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

        val rawPostId = navResult.extractedHtml["post_id"] ?: ""
        val postId = if (rawPostId.startsWith("POSTID=")) rawPostId.removePrefix("POSTID=") else ""
        if (postId.isNotBlank() && postId != "NOT_FOUND") {
            ProviderLogger.d(TAG, "navigateMovieToWatchPage", "Extracted postId: $postId")
        }

        return FlowResult(
            success = true,
            finalUrl = navResult.finalUrl,
            servers = serversWithIframes,
            downloads = downloads,
            watchPageHtml = watchPageHtml,
            error = null,
            postId = postId
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

            // Step 2: Extract post id from watch page
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_POST_ID, key = "post_id"),

            // Step 3: Debug DOM — log DOM state for diagnostics
            NavigationStep.ExecuteJs(javascript = JS_DEBUG_DOM, key = "debug_dom"),

            // Step 4: Extract server list (if on watching page)
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_SERVERS, key = "server_list"),

            // Step 5: Extract combined (servers, downloads, iframes)
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_ALL, key = "extracted_all"),

            // Step 6: Fetch iframes via core.php (if on watching page)
            NavigationStep.ExecuteJs(javascript = JS_FETCH_IFRAMES, key = "fetch_initiated"),

            // Step 7: Wait for fetches
            NavigationStep.WaitForDelay(8_000L),

            // Step 8: Collect iframe results
            NavigationStep.ExecuteJs(
                javascript = "(function(){ return window.__cimaIframeResults || '[]'; })();",
                key = "iframe_results"
            ),

            // Step 9: Extract download links
            NavigationStep.ExecuteJs(javascript = JS_EXTRACT_DOWNLOADS, key = "download_links"),

            // Step 10: Save final watch page HTML for debugging
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

    private data class CombinedResult(
        val servers: List<ServerInfo>,
        val downloads: List<DownloadInfo>
    )

    private fun parseCombinedExtraction(raw: String): CombinedResult? {
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val obj = JSONObject(cleaned)
            val servers = mutableListOf<ServerInfo>()
            val sArr = obj.optJSONArray("servers")
            if (sArr != null) {
                for (i in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(i)
                    servers.add(ServerInfo(
                        name = s.optString("name", ""),
                        index = s.optString("index", ""),
                        id = s.optString("id", "")
                    ))
                }
            }
            val downloads = mutableListOf<DownloadInfo>()
            val dArr = obj.optJSONArray("downloads")
            if (dArr != null) {
                for (i in 0 until dArr.length()) {
                    val d = dArr.getJSONObject(i)
                    downloads.add(DownloadInfo(
                        name = d.optString("name", ""),
                        url = d.optString("url", "")
                    ))
                }
            }
            CombinedResult(servers, downloads)
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "parseCombinedExtraction", "Failed: ${e.message}", "raw" to raw.take(200))
            null
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
        var allLinks = document.getElementsByTagName('a');
        for (var i = 0; i < allLinks.length; i++) {
            var href = allLinks[i].getAttribute('href') || '';
            if (href.indexOf('get-link') === -1 && href.indexOf('watch') === -1) continue;
            var r = allLinks[i].getBoundingClientRect();
            var isDisabled = allLinks[i].disabled || allLinks[i].classList.contains('disabled') || allLinks[i].getAttribute('aria-disabled') === 'true' || (allLinks[i].style.pointerEvents === 'none');
            if (r.width > 0 && r.height > 0 && allLinks[i].offsetParent !== null && !isDisabled
                && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
                return true;
            }
        }
        return false;
    } catch(e) { return false; }
})()
        """.trimIndent()

        val JS_FIND_SERVER_LINK = """
(function() {
    var allLinks = document.getElementsByTagName('a');
    for (var i = 0; i < allLinks.length; i++) {
        var href = allLinks[i].getAttribute('href') || '';
        var rect = allLinks[i].getBoundingClientRect();
        var visible = rect.width > 0 && rect.height > 0 && allLinks[i].offsetParent !== null;
        var isDisabled = allLinks[i].disabled || allLinks[i].classList.contains('disabled') || allLinks[i].getAttribute('aria-disabled') === 'true' || (allLinks[i].style.pointerEvents === 'none');
        
        if (!visible || isDisabled) continue;
        if (href.indexOf('viiqkzqv') !== -1 || href.indexOf('wildsino') !== -1) continue;
        if (href.indexOf('get-link') !== -1 || href.indexOf('watch') !== -1) {
            try { allLinks[i].click(); return 'clicked:' + href; } catch(e) { return 'error:' + e.message; }
        }
    }
    for (var i = 0; i < allLinks.length; i++) {
        var href = allLinks[i].getAttribute('href') || '';
        if (!href || href === '#' || href.indexOf('http') !== 0) continue;
        var rect = allLinks[i].getBoundingClientRect();
        var isDisabled = allLinks[i].disabled || allLinks[i].classList.contains('disabled') || (allLinks[i].style.pointerEvents === 'none');
        if (rect.width > 0 && rect.height > 0 && allLinks[i].offsetParent !== null && !isDisabled
            && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
            try { allLinks[i].click(); return 'clicked_fallback:' + href; } catch(e) {}
        }
    }
    return 'no_server_link';
})();
        """.trimIndent()

        val JS_DEBUG_DOM = """
(function(){
    var uls = document.querySelectorAll('ul');
    for (var i = 0; i < uls.length; i++) {
        console.log('[Nav] UL #'+i+': id="'+uls[i].id+'" class="'+uls[i].className+'" children='+uls[i].children.length);
    }
    var iframes = document.getElementsByTagName('iframe');
    for (var i = 0; i < iframes.length; i++) {
        console.log('[Nav] IFRAME #'+i+': src="'+(iframes[i].src||'none')+'"');
    }
    var watchLis = document.querySelectorAll('#watch li');
    for (var i = 0; i < watchLis.length; i++) {
        var li = watchLis[i];
        console.log('[Nav] Server #'+i+': text="'+(li.textContent||'').trim().slice(0,50)+'" data-index="'+(li.getAttribute('data-index')||'')+'" data-id="'+(li.getAttribute('data-id')||'')+'"');
    }
    return 'debug_done';
})();
        """.trimIndent()

        val JS_EXTRACT_POST_ID = """
(function(){
    var lis=document.querySelectorAll('#watch li');
    for(var i=0;i<lis.length;i++){var d=lis[i].getAttribute('data-id');if(d){console.log('[POSTID] found via watch li:',d);return 'POSTID='+d;}}
    var sl=document.querySelector('link[rel="shortlink"]'); if(sl&&sl.href){var m=sl.href.match(/[?&]p=(\d+)/);if(m){console.log('[POSTID] found via shortlink:',m[1]);return 'POSTID='+m[1];}}
    console.log('[POSTID] not found');
    return 'POSTID=NOT_FOUND';
})();
        """.trimIndent()

        val JS_EXTRACT_SERVERS = """
(function(){
    var servers=[],items=document.querySelectorAll('#watch li');
    for(var i=0;i<items.length;i++)servers.push({index:items[i].getAttribute('data-index')||items[i].getAttribute('data-idx')||'',id:items[i].getAttribute('data-id')||'',name:(items[i].textContent||'').trim().slice(0,50)});
    console.log('[EXTRACT] servers count:',servers.length);
    return (function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})()(servers);
})();
        """.trimIndent()

        val JS_EXTRACT_ALL = """
(function(){
    var servers=[],items=document.querySelectorAll('#watch li');
    for(var i=0;i<items.length;i++)servers.push({index:items[i].getAttribute('data-index')||items[i].getAttribute('data-idx')||'',id:items[i].getAttribute('data-id')||'',name:(items[i].textContent||'').trim().slice(0,50)});
    var downloads=[],hosts=['jetload','forafile','vk.com/doc','frdl.my','bysetayico','href.li'];
    var as=document.getElementsByTagName('a');
    for(var i=0;i<as.length;i++){var a=as[i],h=a.getAttribute('href')||'';if(!h||h==='#'||h.indexOf('http')!==0)continue;var p=a.parentElement;if(!p)continue;var pid=p.getAttribute('id')||'',pl=p.getAttribute('aria-label')||'';var isQ=(pl==='quality'||pl==='q_hidden'),isD=(pid==='download'||pid==='d_hidden'||pl==='download');if(!isQ&&!isD){var hl=h.toLowerCase(),hit=false;for(var k=0;k<hosts.length;k++){if(hl.indexOf(hosts[k])!==-1){hit=true;break;}}if(!hit)continue;}downloads.push({name:(a.textContent||'').trim().slice(0,50),url:h});}
    var iframes=[],fs=document.getElementsByTagName('iframe');
    for(var i=0;i<fs.length;i++){var s=fs[i].getAttribute('data-src')||fs[i].src||'';if(s&&s.indexOf('about:blank')===-1)iframes.push(s);}
    var res={servers:servers,downloads:downloads,iframes:iframes};
    console.log('[EXTRACT_ALL] servers:',servers.length,'downloads:',downloads.length,'iframes:',iframes.length);
    return (function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})()(res);
})();
        """.trimIndent()

        val JS_FETCH_IFRAMES = """
(function(){
    var items = [];
    var watchLis = document.querySelectorAll('#watch li');
    for (var w = 0; w < watchLis.length; w++) {
        var li = watchLis[w];
        var idx = li.getAttribute('data-index') || li.getAttribute('data-idx') || li.getAttribute('data-post') || '';
        var id = li.getAttribute('data-id') || li.getAttribute('data-ix') || '';
        var al = li.getAttribute('aria-label') || '';
        if (idx || id || al === 'embed') { items.push({el: li, index: idx, id: id}); }
    }
    var baseUrl = window.location.origin;
    var results = [];
    var done = 0;
    var total = Math.min(items.length, 10);
    for (var i = 0; i < total; i++) {
        var item = items[i];
        var idx = item.index;
        var id = item.id;
        var name = (item.el.textContent || item.el.getAttribute('aria-label') || '').trim().slice(0, 60);
        
        // Check for existing iframe inside the li via getElementsByTagName (NOT patched)
        var childIframes = item.el.getElementsByTagName('iframe');
        if (childIframes.length > 0) {
            var iframeSrc = childIframes[0].getAttribute('data-src') || childIframes[0].src || '';
            if (iframeSrc && iframeSrc.indexOf('about:blank') === -1) {
                results.push({name: name, index: idx, iframe: iframeSrc, direct: true});
                done++;
                if (done === total) { window.__cimaIframeResults = (window.__cimaOrigJSON||(function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})())(results); }
                continue;
            }
        }
        
        // Check all iframes in the page for any pre-loaded player iframe
        var allIframes = document.getElementsByTagName('iframe');
        for (var f = 0; f < allIframes.length; f++) {
            var src = allIframes[f].getAttribute('data-src') || allIframes[f].src || '';
            if (src && src.indexOf('about:blank') === -1 && src.length > 10) {
                results.push({name: name, index: idx, iframe: src, direct: true});
                done++;
                if (done === total) { window.__cimaIframeResults = (window.__cimaOrigJSON||(function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})())(results); }
                break;
            }
        }
        if (done > i) continue;
        
        // Fall back to AJAX via core.php
        var ajaxUrl = baseUrl + '/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=' + idx + '&id=' + id;
        (function(srvName, srvIdx, url) {
            fetch(url, {credentials: 'include', headers: {'X-Requested-With': 'XMLHttpRequest', 'Referer': window.location.href}})
                .then(function(r) { return r.text(); })
                .then(function(html) {
                    var iframeSrc = '';
                    var match = html.match(/<iframe[^>]+src=["']([^"']+)["']/);
                    if (match) iframeSrc = match[1];
                    results.push({name: srvName, index: srvIdx, iframe: iframeSrc, direct: false, responseLength: html.length});
                    done++;
                    if (done === total) { window.__cimaIframeResults = (window.__cimaOrigJSON||(function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})())(results); }
                })
                .catch(function(err) {
                    results.push({name: srvName, index: srvIdx, iframe: '', direct: false, error: err.message});
                    done++;
                });
        })(name, idx, ajaxUrl);
    }
    return 'fetching_' + total + '_servers';
})();
        """.trimIndent()

        val JS_EXTRACT_DOWNLOADS = """
(function(){
    var downloads = [];
    var anchors = document.getElementsByTagName('a');
    var dlHosts = ['jetload','forafile','vk.com/doc','frdl.my','bysetayico','upns','href.li'];
    for (var i = 0; i < anchors.length; i++) {
        var a = anchors[i];
        var href = a.getAttribute('href') || '';
        if (!href || href === '#' || href.indexOf('http') !== 0) continue;
        var parent = a.parentElement;
        if (!parent) continue;
        var parentId = parent.getAttribute('id') || '';
        var parentLabel = parent.getAttribute('aria-label') || '';
        var isQuality = (parentLabel === 'quality' || parentLabel === 'q_hidden');
        var isDownload = (parentId === 'download' || parentId === 'd_hidden' || parentLabel === 'download');
        if (!isQuality && !isDownload) {
            var hl = href.toLowerCase();
            var matched = false;
            for (var k = 0; k < dlHosts.length; k++) { if (hl.indexOf(dlHosts[k]) !== -1) { matched = true; break; } }
            if (!matched) continue;
        }
        var name = (a.textContent || '').trim().slice(0, 60);
        downloads.push({name: name, url: href});
    }
    console.log('[DOWNLOAD] download count:',downloads.length);
    return (function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})()(downloads);
})();
        """.trimIndent()

        /**
         * Extracts ALL iframes currently in the page DOM (including ones injected by the
         * page's own JS, e.g. the VK embed that auto-plays). This is the V1 fallback
         * `iframe` selector. Returns [{name, iframe, src}].
         */
        val JS_EXTRACT_DIRECT_IFRAMES = """
(function(){
    var iframes = document.getElementsByTagName('iframe');
    var results = [];
    for (var i = 0; i < iframes.length; i++) {
        var src = iframes[i].getAttribute('data-src') || iframes[i].src || '';
        if (!src || src.indexOf('about:blank') !== -1) continue;
        var parent = iframes[i].parentElement || {};
        var name = (parent.getAttribute && parent.getAttribute('aria-label')) || parent.className || ('iframe#' + i);
        results.push({name: String(name).slice(0, 40), iframe: src, src: src});
    }
    return (window.__cimaOrigJSON||(function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})())(results);
})();
        """.trimIndent()

        /**
         * Diagnostic snapshot of the watching page state. Logs (to console, captured by
         * NavEngineJS) the JS-visible userAgent, cookies, availability of consent libs
         * (Swal / jQuery / $.cookie), and any visible modal/dialog buttons — so we can tell
         * whether the page reached the consent/server-list stage or bounced to /home.
         */
        /**
         * Waits until an <iframe> is present in the live DOM (the page decrypts and injects
         * the player iframe after its JS runs), then returns document.documentElement.outerHTML.
         * This captures the already-decrypted content (server tabs + player iframe). If no
         * iframe appears within 30s it returns the current outerHTML anyway.
         */
        val JS_EXTRACT_HTML_AFTER_IFRAME = """
(function(){
    return new Promise(function(resolve){
        var deadline = Date.now() + 30000;
        var poll = setInterval(function(){
            var iframes = document.getElementsByTagName('iframe');
            var real = null;
            for (var i = 0; i < iframes.length; i++) {
                var s = iframes[i].getAttribute('data-src') || iframes[i].src || '';
                if (s && s.indexOf('about:blank') === -1 && s.length > 5) { real = iframes[i]; break; }
            }
            if (real || Date.now() > deadline) {
                clearInterval(poll);
                try {
                    resolve(document.documentElement.outerHTML);
                } catch(e) {
                    resolve('');
                }
            }
        }, 500);
    });
})();
        """.trimIndent()

        /**
         * Compact diagnostic: dumps every download/media-like <a> on the page (text + href)
         * plus the #download section's innerHTML, so the real download-link markup is
         * visible in the log without dumping the entire 3.4 MB document.
         */
        val JS_DUMP_LINKS = """
(function(){
    try {
        var anchors = document.getElementsByTagName('a');
        var hosts = ['jetload','forafile','ok.ru','vk.com/doc','youtube','dailymotion','frdl.my','bysetayico','upns','href.li','mega','mediafire','4shared','1fichier','solidfiles','drive','uptobox','adminfor','ceska','fastdrive','racaty','usercdn','onlinestream','shahid','mycima','cima','filerab','vidbob','upvid','giga-down','samaup','easyload','upstream'];
        var out = [];
        for (var i = 0; i < anchors.length; i++) {
            var a = anchors[i];
            var h = a.getAttribute('href') || '';
            var t = (a.textContent || '').trim().slice(0, 50);
            if (h.indexOf('http') !== 0) continue;
            var hit = false;
            var hl = h.toLowerCase();
            for (var k = 0; k < hosts.length; k++) { if (hl.indexOf(hosts[k]) !== -1) { hit = true; break; } }
            if (!hit && /\d+p/.test(t)) hit = true;
            if (hit) out.push({ t: t, h: h.slice(0, 400) });
        }
        var dlSection = null;
        var all = document.getElementsByTagName('*');
        for (var x = 0; x < all.length; x++) {
            var id = all[x].getAttribute('id') || '';
            var cls = all[x].getAttribute('class') || '';
            if (id === 'download' || id === 'd_hidden' || cls.indexOf('download') !== -1) {
                dlSection = all[x];
                break;
            }
        }
        var dlHtml = dlSection ? dlSection.innerHTML.slice(0, 4000) : 'NO_DOWNLOAD_SECTION';
        return 'DUMP_LINKS:' + (window.__cimaOrigJSON||(function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})())({ count: out.length, links: out, downloadSection: dlHtml });
    } catch(e) { return 'dump_err:' + e.message; }
})();
        """.trimIndent()

        val JS_DIAGNOSE_WATCHING = """
(function(){
    try {
        var watchList = document.querySelectorAll('#watch li');
        var qualityCount = 0;
        var anchors = document.getElementsByTagName('a');
        for (var x = 0; x < anchors.length; x++) {
            var p = anchors[x].parentElement;
            if (p) {
                var pl = p.getAttribute('aria-label') || '';
                if (pl === 'quality' || pl === 'q_hidden') qualityCount++;
            }
        }
        var diag = {
            url: window.location.href,
            ua: navigator.userAgent,
            cookie: (document.cookie || '').slice(0, 300),
            hasSwal: typeof window.Swal !== 'undefined',
            hasJQuery: typeof window.jQuery !== 'undefined',
            hasJQueryCookie: (typeof window.jQuery !== 'undefined' && typeof window.jQuery.cookie !== 'undefined'),
            watchItems: watchList.length + qualityCount,
            visibleDialogs: 0,
            dialogButtons: [],
            swal2Confirm: '',
            swal2Title: '',
            swal2Html: '',
            iframes: []
        };
        var modals = document.querySelectorAll('.swal2-container, .swal2-modal, .swal2-popup, .modal, .popup');
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
        diag.allSpans = watchList.length;
        var watchEl = document.querySelector('#watch');
        diag.watchHtml = watchEl ? (watchEl.innerHTML.slice(0, 1200) || 'no_watch') : 'no_watch';
        var ifr = document.getElementsByTagName('iframe');
        for (var i = 0; i < Math.min(ifr.length, 8); i++) {
            diag.iframes.push((ifr[i].getAttribute('data-src') || ifr[i].src || 'none').slice(0, 120));
        }
        return 'DIAG_JSON:' + (window.__cimaOrigJSON||(function(){try{var f=document.createElement('iframe');document.documentElement.appendChild(f);var j=f.contentWindow.JSON.stringify.bind(f.contentWindow.JSON);f.remove();return j}catch(e){return JSON.stringify}})())(diag);
    } catch(e) {
        return 'diag_error:' + e.message;
    }
})();
        """.trimIndent()
    }
}