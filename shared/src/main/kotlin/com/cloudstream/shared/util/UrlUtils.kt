package com.cloudstream.shared.util

/**
 * Resolves a URL scraped out of a page against the site it came from.
 *
 * One implementation of what was five private `fixUrl` copies (Replaymatch, Tuniflix and
 * TukTukcima's `fixUrlLocally` now call this; ArabSeedV4Parser and KooraLive keep their own because
 * theirs mean something different — see `docs/reviews/` and the Wave 3 report).
 *
 * The contract is exactly the one all three collapsed copies shared:
 *  - blank in, blank out;
 *  - an absolute `http://` / `https://` URL is returned untouched;
 *  - a protocol-relative `//host/path` gets `https:`;
 *  - a root-relative `/path` is appended to [base];
 *  - anything else is treated as a path relative to [base]'s root.
 *
 * [base] is normalised by dropping trailing slashes, so passing `https://a.b` or `https://a.b/`
 * gives the same answer. Pure — no Android, no network, no `java.net.URI` parsing to throw on the
 * malformed hrefs these sites emit.
 */
fun fixUrl(url: String, base: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("//")) return "https:$trimmed"

    val root = base.trimEnd('/')
    return if (trimmed.startsWith("/")) root + trimmed else "$root/$trimmed"
}
