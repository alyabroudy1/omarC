package com.arabseed.extractors

import com.cloudstream.shared.extractors.JsonFetcher
import com.cloudstream.shared.extractors.LazyExtractor

/**
 * Arabseed-specific lazy extractor.
 * Extends shared LazyExtractor with provider-specific configuration.
 * 
 * Handles virtual URLs like:
 * https://arabseed.show/get__watch__server/?post_id=X&quality=Y&server=Z&csrf_token=...
 */
class ArabseedLazyExtractor(
    private val fetcher: JsonFetcher? = null
) : LazyExtractor() {
    
    override val name = "ArabseedLazy"
    override val mainUrl = "https://asd.pics"
    override val jsonFetcher: JsonFetcher? get() = fetcher
}
