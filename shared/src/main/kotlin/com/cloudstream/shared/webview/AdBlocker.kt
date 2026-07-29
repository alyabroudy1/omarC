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

            // 5. Neutralise floating overlays periodically.
            //
            // This used to call el.remove() on any fixed/absolute element with z-index > 5000,
            // sparing only elements that contained a <video>/<iframe> or sat inside .jw-wrapper /
            // .plyr / .video-js. That was wrong in three ways, and between them they produced the
            // "page shows for a second, then goes black forever" symptom (observed 2026-07-29 on
            // pl.asdplay.cam and miiiixdrop.net, removing z=300000 and z=2147463647):
            //   * A high z-index is not an ad signature. Player UI uses exactly these values.
            //   * A player's ERROR screen ("file deleted") contains no video and no iframe and is
            //     not inside those three skin classes, so it was always removed — and hosts with a
            //     custom skin lost their whole UI.
            //   * remove() is irreversible and the interval repeats, so anything the page re-rendered
            //     was deleted again. It also destroyed the text that the deleted-video detector in
            //     VideoSnifferEngine reads to offer "skip server", so the two features fought:
            //     the page visibly said "file deleted" and the detector saw an empty document.
            //
            // Now: hide instead of delete, and demand an actual ad signal rather than treating
            // "floats above the page" as proof. Hiding stops the obstruction while leaving the node
            // in the DOM, so textContent still reads it.
            // Two patterns on purpose. Short generic words must be delimited or they match innocent
            // names — "download", "addToList", "padding-box", "gradient" and "radar" all contain
            // "ad". Distinctive vendor tokens are safe to match anywhere, and must be, because the
            // commonest ad class of all — "adsbygoogle" — has no delimiter after its keyword.
            var AD_HINT = /(^|[-_.\s])(ads?|advert|banner|popup|promo|sponsor|interstitial|preroll)([-_.\s]|$)/i;
            var AD_BRAND = /(adsbygoogle|googlesyndication|doubleclick|taboola|outbrain|propeller|popunder|adnxs|adform|zergnet|mgid)/i;
            var ERROR_HINT = /(deleted|not found|no longer available|removed|unavailable|missing|expired|404|غير موجود|تم الحذف|غير متوفر)/i;

            function looksLikeAd(el) {
                // Ad networks label their containers; players do not.
                var id = el.id || '';
                var cls = (typeof el.className === 'string') ? el.className : '';
                if (AD_HINT.test(id) || AD_HINT.test(cls)) return true;
                if (AD_BRAND.test(id) || AD_BRAND.test(cls)) return true;
                // A cross-origin iframe or a bare outbound link with almost no text of its own is
                // the other reliable shape.
                var f = el.querySelector('iframe[src]');
                if (f) {
                    try {
                        var h = new URL(f.src, location.href).hostname;
                        if (h && h !== location.hostname && (el.textContent || '').trim().length < 40) return true;
                    } catch(e) {}
                }
                return false;
            }

            function protectedOverlay(el) {
                // Anything that is, contains, or sits inside media — plus anything carrying the
                // failure message we specifically want to be able to read.
                if (el.querySelector('video') || el.querySelector('audio')) return true;
                if (el.closest('.jw-wrapper') || el.closest('.plyr') || el.closest('.video-js')) return true;
                if (el.closest('[class*="player"]') || el.closest('[id*="player"]')) return true;
                var txt = (el.textContent || '');
                if (txt.length < 400 && ERROR_HINT.test(txt)) return true;
                return false;
            }

            setInterval(function() {
                document.querySelectorAll('div, aside, section').forEach(function(el) {
                    try {
                        if (el.dataset && el.dataset.csAdHidden === '1') return;
                        var style = window.getComputedStyle(el);
                        var pos = style.position;
                        var zIndex = parseInt(style.zIndex) || 0;
                        if ((pos !== 'fixed' && pos !== 'absolute') || zIndex <= 5000) return;
                        if (protectedOverlay(el) || !looksLikeAd(el)) return;

                        el.style.setProperty('visibility', 'hidden', 'important');
                        el.style.setProperty('pointer-events', 'none', 'important');
                        if (el.dataset) el.dataset.csAdHidden = '1';
                        console.log('[AdBlocker] Hid overlay z=' + zIndex + ' id=' + (el.id || '-') + ' cls=' + String((typeof el.className === 'string') ? el.className : '').slice(0, 40));
                    } catch(err) {}
                });
            }, 2000);
        })();
    """.trimIndent()
}
