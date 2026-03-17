package com.cloudstream.shared.webview

/**
 * Ad blocker for WebView sessions.
 *
 * Provides 3 layers of ad blocking:
 * 1. **Network**: Block requests to known ad domains (`shouldBlockRequest`)
 * 2. **CSS**: Hide common ad containers (`AD_BLOCK_CSS`)
 * 3. **JS**: Kill popups, remove overlays, block redirects (`AD_BLOCK_JS`)
 *
 * Used by [VideoSnifferEngine] in both sniff mode and player mode.
 */
object AdBlocker {

    // ========================= LAYER 1: NETWORK BLOCKING =========================

    private val BLOCKED_DOMAINS = setOf(
        // Ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "adsrvr.org", "adcolony.com", "adform.net",
        "pubmatic.com", "openx.net", "criteo.com", "criteo.net",
        "rubiconproject.com", "smartadserver.com", "amazon-adsystem.com",
        "moatads.com", "serving-sys.com", "medianet.com",

        // Popunder / redirect ad networks
        "propellerads.com", "propellerpops.com", "popcash.net",
        "juicyads.com", "trafficjunky.net", "exoclick.com",
        "adsterra.com", "hilltopads.net", "a-ads.com",
        "clickadu.com", "pushground.com", "richpush.co",
        "monetag.com", "profitablegatecpm.com",

        // Tracking / analytics
        "google-analytics.com", "googletagmanager.com",
        "facebook.net", "connect.facebook.com",
        "hotjar.com", "mixpanel.com", "segment.io",
        "chartbeat.net", "quantserve.com", "scorecardresearch.com",

        // Crypto miners
        "coinhive.com", "coin-hive.com", "cryptoloot.pro",

        // Common Arabic-site ad networks
        "pubfuture.com", "revenuehits.com", "yllix.com",
        "bidvertiser.com", "popads.net", "popmyads.com",
        "ad-maven.com", "admaven.com",

        // Malware / scam
        "malware-check.top", "virus-alert.top",

        // Domains identified from log analysis (FaselHD ad networks)
        "oyo4d.com",                    // Ad tracker
        "071kk.com",                    // Ad network
        "browsecoherentunrefined.com",  // Ad/tracking
        "fleraprt.com",                 // Analytics tracker
        "tzegilo.com",                  // WebGL ad loader
        "pyppo.com"                     // Intent redirect ads
    )

    /** Check if a network request URL should be blocked. */
    fun shouldBlockRequest(url: String): Boolean {
        val lowerUrl = url.lowercase()

        // Fast path: data URIs, favicon
        if (lowerUrl.startsWith("data:") || lowerUrl.contains("favicon.ico")) return false

        // Check domain blocklist
        return try {
            val host = java.net.URI(url).host?.lowercase() ?: return false
            BLOCKED_DOMAINS.any { blocked ->
                host == blocked || host.endsWith(".$blocked")
            }
        } catch (_: Exception) {
            false
        }
    }

    // ========================= LAYER 2: CSS AD HIDING =========================

    /** CSS injected into every page to hide common ad containers. */
    val AD_BLOCK_CSS = """
        (function() {
            var style = document.createElement('style');
            style.textContent = `
                [class*="ad-container"], [class*="ad-wrapper"], [class*="ad-banner"],
                [class*="ads-container"], [class*="ads-wrapper"],
                [id*="ad-container"], [id*="ad-wrapper"], [id*="ad-banner"],
                [class*="popup-ad"], [class*="popunder"], [class*="popup-overlay"],
                .ad-overlay, .ads-overlay,
                div[data-ad], div[data-ads],
                iframe[src*="ads"], iframe[src*="doubleclick"],
                a[href*="ad.doubleclick"], a[href*="clickadu"],
                .sticky-ad, .floating-ad, .banner-ad {
                    display: none !important;
                    visibility: hidden !important;
                    height: 0 !important;
                    width: 0 !important;
                    overflow: hidden !important;
                }
            `;
            document.head.appendChild(style);
        })();
    """.trimIndent()

    // ========================= LAYER 3: JS BEHAVIOR BLOCKING =========================

    /** JS injected to kill popup windows, overlays, and redirect hijacking. */
    val AD_BLOCK_JS = """
        (function() {
            // 1. Block window.open (popup ads)
            window.open = function() { return null; };

            // 2. Block alert/confirm/prompt (ad dialogs)
            window.alert = function() {};
            window.confirm = function() { return false; };
            window.prompt = function() { return null; };

            // 3. Block beforeunload traps
            window.addEventListener('beforeunload', function(e) {
                e.stopImmediatePropagation();
            }, true);

            // 4. Intercept target="_blank" link clicks (ad redirects)
            document.addEventListener('click', function(e) {
                var link = e.target.closest('a');
                if (link && link.target === '_blank') {
                    // Check if this is likely an ad (external domain)
                    try {
                        var linkHost = new URL(link.href).hostname;
                        var pageHost = window.location.hostname;
                        if (linkHost !== pageHost) {
                            e.preventDefault();
                            e.stopPropagation();
                            console.log('[AdBlocker] Blocked external link: ' + link.href);
                        }
                    } catch(err) {}
                }
            }, true);

            // 5. Kill floating overlays periodically
            setInterval(function() {

                document.querySelectorAll('div, aside, section').forEach(function(el) {
                    var style = window.getComputedStyle(el);
                    var pos = style.position;
                    var zIndex = parseInt(style.zIndex) || 0;

                    // High z-index fixed/absolute element = likely ad overlay
                    if ((pos === 'fixed' || pos === 'absolute') && zIndex > 5000) {
                        // Skip if it contains a video player
                        if (!el.querySelector('video') && !el.querySelector('iframe') &&
                            !el.closest('.jw-wrapper') && !el.closest('.plyr') &&
                            !el.closest('.video-js')) {
                            el.remove();
                            console.log('[AdBlocker] Removed overlay z=' + zIndex);
                        }
                    }
                });
            }, 2000);
        })();
    """.trimIndent()
}
