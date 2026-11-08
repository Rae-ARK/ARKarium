package com.arkarium.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Coverage for TtsVoiceModel.kt: languages present, rows for one language,
// installed-vs-not, the description string, and voiceNameToApply.
class TtsVoiceModelTest {

    private fun voice(
        name: String,
        language: String,
        region: String = "",
        quality: Int = 300,
        online: Boolean = false,
        installed: Boolean = true
    ) = TtsVoiceInfo(name, language, region, quality, online, installed)

    @Test
    fun languagesOf_putsDeviceLanguageFirst_thenRestAlphabetical() {
        val voices = listOf(
            voice("en-in", "en", "India"),
            voice("ja-jp", "ja", "Japan"),
            voice("ta-in", "ta", "India"),
            voice("en-us", "en", "United States")
        )
        assertEquals(listOf("ta", "en", "ja"), languagesOf(voices, deviceLanguage = "ta"))
    }

    @Test
    fun languagesOf_deviceLanguageAbsent_stillSortsTheRest() {
        val voices = listOf(voice("en-us", "en"), voice("ja-jp", "ja"))
        assertEquals(listOf("en", "ja"), languagesOf(voices, deviceLanguage = "fr"))
    }

    @Test
    fun languagesOf_distinctOnly() {
        val voices = listOf(voice("en-in-a", "en"), voice("en-in-b", "en"))
        assertEquals(listOf("en"), languagesOf(voices, deviceLanguage = "en"))
    }

    @Test
    fun rowsForLanguage_filtersToThatLanguage() {
        val voices = listOf(voice("en-us", "en"), voice("ja-jp", "ja"))
        assertEquals(listOf("en-us"), rowsForLanguage(voices, "en").map { it.name })
    }

    @Test
    fun rowsForLanguage_localBeforeOnline() {
        val voices = listOf(
            voice("en-online", "en", online = true),
            voice("en-local", "en", online = false)
        )
        assertEquals(
            listOf("en-local", "en-online"),
            rowsForLanguage(voices, "en").map { it.name }
        )
    }

    @Test
    fun rowsForLanguage_thenByRegionThenName() {
        val voices = listOf(
            voice("en-us-b", "en", region = "United States"),
            voice("en-in-a", "en", region = "India"),
            voice("en-in-z", "en", region = "India")
        )
        assertEquals(
            listOf("en-in-a", "en-in-z", "en-us-b"),
            rowsForLanguage(voices, "en").map { it.name }
        )
    }

    @Test
    fun descriptionOf_statesOnlineAndOfflineTheSameWay() {
        val online = voice("v1", "en", region = "India", quality = 400, online = true)
        val offline = voice("v2", "en", region = "India", quality = 400, online = false)
        assertEquals("India \u00b7 online \u00b7 high quality", descriptionOf(online))
        assertEquals("India \u00b7 offline \u00b7 high quality", descriptionOf(offline))
    }

    @Test
    fun descriptionOf_omitsBlankRegion() {
        val noRegion = voice("v1", "en", region = "", quality = 100, online = false)
        assertEquals("offline \u00b7 very low quality", descriptionOf(noRegion))
    }

    @Test
    fun qualityLabel_fiveTierLadder() {
        assertEquals("very low quality", qualityLabel(50))
        assertEquals("low quality", qualityLabel(200))
        assertEquals("standard quality", qualityLabel(300))
        assertEquals("high quality", qualityLabel(400))
        assertEquals("very high quality", qualityLabel(500))
    }

    @Test
    fun installedOnly_dropsNotInstalled() {
        val voices = listOf(
            voice("v1", "en", installed = true),
            voice("v2", "en", installed = false)
        )
        assertEquals(listOf("v1"), installedOnly(voices).map { it.name })
    }

    @Test
    fun withFavoritesFirst_movesFavoritesToTheFront() {
        val voices = listOf(voice("a", "en"), voice("b", "en"), voice("c", "en"))
        assertEquals(
            listOf("b", "a", "c"),
            withFavoritesFirst(voices, favorites = setOf("b")).map { it.name }
        )
    }

    @Test
    fun withFavoritesFirst_preservesOrderWithinEachGroup() {
        val voices = listOf(voice("a", "en"), voice("b", "en"), voice("c", "en"), voice("d", "en"))
        assertEquals(
            listOf("b", "d", "a", "c"),
            withFavoritesFirst(voices, favorites = setOf("b", "d")).map { it.name }
        )
    }

    @Test
    fun withFavoritesFirst_noFavorites_leavesOrderUnchanged() {
        val voices = listOf(voice("a", "en"), voice("b", "en"))
        assertEquals(listOf("a", "b"), withFavoritesFirst(voices, favorites = emptySet()).map { it.name })
    }

    @Test
    fun voiceNameToApply_nullWhenNoStoredName() {
        assertNull(voiceNameToApply(null, listOf("a", "b")))
    }

    @Test
    fun voiceNameToApply_nullWhenStoredNameNotAvailable() {
        assertNull(voiceNameToApply("c", listOf("a", "b")))
    }

    @Test
    fun voiceNameToApply_returnsStoredNameWhenAvailable() {
        assertEquals("b", voiceNameToApply("b", listOf("a", "b")))
        assertTrue(listOf("a", "b").contains("b"))
    }
}
