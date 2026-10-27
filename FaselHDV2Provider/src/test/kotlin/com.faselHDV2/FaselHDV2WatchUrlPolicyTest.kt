package com.faselHDV2

import org.junit.Assert.assertEquals
import org.junit.Test

class FaselHDV2WatchUrlPolicyTest {

    private val playerOne =
        "https://www.fasel-hd.co/video_player?player_token=one"
    private val playerTwo =
        "https://www.fasel-hd.co/video_player?player_token=two"
    private val detailPage = "https://www.fasel-hd.co/?p=8672"

    @Test
    fun `explicit players are kept and a generic detail page is dropped`() {
        val selected = FaselHDV2WatchUrlPolicy.select(listOf(playerOne, playerTwo, detailPage))

        assertEquals(listOf(playerOne, playerTwo), selected)
    }

    @Test
    fun `explicit players are ordered before unknown candidates`() {
        val unknown = "https://cdn.example/unclassified"
        val selected = FaselHDV2WatchUrlPolicy.select(listOf(unknown, playerOne))

        assertEquals(listOf(playerOne, unknown), selected)
    }

    @Test
    fun `a generic detail page is preserved when no explicit player exists`() {
        val selected = FaselHDV2WatchUrlPolicy.select(listOf(detailPage))

        assertEquals(listOf(detailPage), selected)
    }

    @Test
    fun `duplicates are removed`() {
        val selected = FaselHDV2WatchUrlPolicy.select(listOf(playerOne, playerOne, detailPage))

        assertEquals(listOf(playerOne), selected)
    }
}
