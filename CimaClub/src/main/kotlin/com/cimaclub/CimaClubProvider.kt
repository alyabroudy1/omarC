package com.cimaclub

import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.lagradost.cloudstream3.mainPageOf

class CimaClub : BaseProvider() {
    override val baseDomain get() = "ciimaclub.club"
    override val providerName get() = "CimaClub"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/cimaclub.json"

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبي",
        "$mainUrl/category/افلام-عربي/" to "أفلام عربي",
        "$mainUrl/category/افلام-هندي/" to "أفلام هندي",
        "$mainUrl/category/افلام-اسيوية/" to "أفلام اسيوية",
        "$mainUrl/category/افلام-انمي/" to "أفلام انمي",
        "$mainUrl/category/مسلسلات-رمضان-2025/" to "مسلسلات رمضان 2025",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبي",
        "$mainUrl/category/مسلسلات-تركية/" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربي",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات اسيوية",
        "$mainUrl/category/مسلسلات-هندية/" to "مسلسلات هندي",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات انمي",
        "$mainUrl/category/مسلسلات-مدبلجة/" to "مسلسلات مدبلجة",
    )

    override fun getParser(): NewBaseParser {
        return CimaClubParser()
    }
}
