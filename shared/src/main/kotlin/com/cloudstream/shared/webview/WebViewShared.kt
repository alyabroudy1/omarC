package com.cloudstream.shared.webview

/**
 * Pieces shared by the three WebView engines ([NavigationEngine], [VideoSnifferEngine],
 * [CfBypassEngine]). Top-level functions and constants only — nothing here touches Android at
 * class-init, so the JVM unit tests can load it.
 */

/**
 * Parse a `Cookie:`-style header into name -> value.
 *
 * Splits on `;`, trims both halves, takes the name before the first `=` and the value after it
 * (so `jwt=aa=bb` keeps `aa=bb`). Entries with a blank *name* are dropped; an **empty value is
 * kept** — `"c="` yields `c` -> `""`, and a bare `"flag"` with no `=` yields `flag` -> `""` too.
 */
internal fun parseCookieString(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    return header.split(";").associate {
        val parts = it.split("=", limit = 2)
        (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
    }.filter { it.key.isNotBlank() }
}

/**
 * The DisableDevtool anti-anti-bot shim, injected on page start by every engine.
 *
 * `disable-devtool` ships on several of the hosts we sniff; left alone it redirects the page (or
 * blanks it) the moment it thinks a debugger is attached, which an automated WebView trips. The
 * shim keeps `window.DisableDevtool` *callable* — a page that finds it missing knows it was
 * tampered with — but neuters every escape hatch: `ignore` always true, no `url` /`timeOutUrl` to
 * redirect to, and `ondevtoolopen` a no-op. The setter remembers the real implementation and the
 * getter still delegates to it, so the library's own bookkeeping runs and only the eviction is
 * dropped.
 *
 * One copy for all engines; it used to be duplicated verbatim in each.
 */
internal const val DISABLE_DEVTOOL_BYPASS_JS = """
    try {
        var originalDisableDevtool;
        Object.defineProperty(window, 'DisableDevtool', {
            get: function() {
                return function(options) {
                    options = options || {};
                    options.ignore = function() { return true; };
                    options.url = "";
                    options.timeOutUrl = "";
                    options.ondevtoolopen = function() {};
                    if (originalDisableDevtool) {
                        try {
                            return originalDisableDevtool(options);
                        } catch(err) {}
                    }
                };
            },
            set: function(val) {
                originalDisableDevtool = val;
            },
            configurable: true
        });
    } catch(e) {}
"""

/**
 * The phrase list both "this video is gone" scans share, as JS array *elements* (a trailing comma
 * is deliberate: each call site appends its own entries or closes the literal).
 *
 * Phrase-anchored on purpose. A match auto-skips the server, so a loose single word like
 * "unavailable" or "expired" would throw away a working host on the strength of an ad's copy.
 */
internal const val DELETED_VIDEO_PHRASES_JS = """
    "file was deleted", "video not found", "404 not found",
    "no longer available", "file not found",
    "we're sorry, this video is no longer available",
    "file deleted", "video removed", "content removed",
    "this video has been removed", "page not found",
    "the file you requested has been deleted",
    "تم حذف الملف", "الملف غير موجود", "الصفحة غير موجودة",
    "هذا الفيديو غير متاح", "تم الحذف", "غير موجود",
    "الملف المطلوب غير موجود",
    "error 404", "404 error", "410 error",
    "this video does not exist",
    "access denied", "blocked",
"""
