package com.cue.offlinebrain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundCommandParserTest {
    private val apps = listOf(
        InstalledCommandApp("Chrome", "com.android.chrome"),
        InstalledCommandApp("YouTube", "com.google.android.youtube"),
        InstalledCommandApp("WhatsApp", "com.whatsapp"),
        InstalledCommandApp("Settings", "com.android.settings"),
        InstalledCommandApp("Settings Plus", "com.example.settingsplus"),
        InstalledCommandApp("Gallery", "com.example.gallery"),
        InstalledCommandApp("Camera", "com.example.camera")
    )
    private fun parse(text: String) = CompoundCommandParser.parse(text, apps)

    @Test fun openChromeResolvesInstalledPackage() {
        val action = parse("open chrome").plan?.actions?.single()
        assertEquals(CommandActionKind.OPEN_APP, action?.kind)
        assertEquals("com.android.chrome", action?.packageName)
    }

    @Test fun openUsesLongestInstalledLabel() {
        assertEquals("Settings Plus", parse("open settings plus").plan?.actions?.single()?.appLabel)
    }

    @Test fun aliasBrowserResolvesInstalledBrowser() {
        assertTrue(parse("open browser").plan?.actions?.single()?.packageName == "com.android.chrome")
    }

    @Test fun oneCharacterAppTypoStillResolvesSafely() {
        val plan = parse("open chrom and search books").plan
        assertEquals("Chrome", plan?.actions?.firstOrNull()?.appLabel)
        assertEquals("books", plan?.actions?.lastOrNull()?.value)
    }

    @Test fun chainsWithAnd() {
        val plan = parse("open chrome and search books").plan
        assertEquals(listOf(CommandActionKind.OPEN_APP, CommandActionKind.WEB_SEARCH), plan?.actions?.map { it.kind })
        assertEquals("books", plan?.actions?.getOrNull(1)?.value)
    }

    @Test fun directWebSearchPreservesAllWords() {
        assertEquals("images of cats", parse("search images of cats").plan?.actions?.single()?.value)
    }

    @Test fun imageSearchIsAWebSearch() {
        assertEquals(CommandActionKind.WEB_SEARCH, parse("search images of cats").plan?.actions?.single()?.kind)
    }

    @Test fun youtubeSearchTargetsTheOpenedApp() {
        val plan = parse("open youtube and search lofi music").plan
        assertEquals(CommandActionKind.IN_APP_SEARCH, plan?.actions?.lastOrNull()?.kind)
        assertEquals("lofi music", plan?.actions?.lastOrNull()?.value)
    }

    @Test fun whatsappSearchTargetsTheOpenedApp() {
        val plan = parse("open whatsapp and search Mom").plan
        assertEquals("com.whatsapp", plan?.actions?.lastOrNull()?.packageName)
        assertEquals("Mom", plan?.actions?.lastOrNull()?.value)
    }

    @Test fun settingsWifiCommandIsTwoOrderedSteps() {
        val plan = parse("open settings then go to wifi").plan
        assertEquals(listOf(CommandActionKind.OPEN_APP, CommandActionKind.SETTINGS_PAGE), plan?.actions?.map { it.kind })
        assertEquals("wifi", plan?.actions?.lastOrNull()?.value)
    }

    @Test fun recognizesBluetoothSettings() {
        assertEquals("bluetooth", parse("go to bluetooth settings").plan?.actions?.single()?.value)
    }

    @Test fun recognizesDisplaySettings() {
        assertEquals("display", parse("go to brightness settings").plan?.actions?.single()?.value)
    }

    @Test fun recognizesCommaAndAfterThatSeparators() {
        val plan = parse("open Chrome, after that search books").plan
        assertEquals(2, plan?.actions?.size)
        assertEquals(CommandActionKind.WEB_SEARCH, plan?.actions?.lastOrNull()?.kind)
    }

    @Test fun recognizesAndThenSeparator() {
        assertEquals(2, parse("open Chrome and then search books").plan?.actions?.size)
    }

    @Test fun recognizesAlsoSeparator() {
        assertEquals(2, parse("open Chrome also search books").plan?.actions?.size)
    }

    @Test fun parsesTypeAndTap() {
        val plan = parse("type hello then tap Send").plan
        assertEquals(listOf(CommandActionKind.TYPE_TEXT, CommandActionKind.TAP_TEXT), plan?.actions?.map { it.kind })
    }

    @Test fun parsesScrollBackAndHome() {
        val plan = parse("scroll down, go back, go home").plan
        assertEquals(listOf(CommandActionKind.SCROLL, CommandActionKind.BACK, CommandActionKind.HOME), plan?.actions?.map { it.kind })
    }

    @Test fun recognizesCallPlayAndSend() {
        assertEquals(CommandActionKind.DIAL, parse("call 12345").plan?.actions?.single()?.kind)
        assertEquals(CommandActionKind.PLAY, parse("play lofi music").plan?.actions?.single()?.kind)
        assertEquals(CommandActionKind.SEND, parse("send hello").plan?.actions?.single()?.kind)
    }

    @Test fun missingAppDoesNotSwallowTheRestOfTheSentence() {
        val result = parse("open missingapp and search books")
        assertTrue(result.recognized)
        assertNull(result.plan)
        assertNotNull(result.error)
        assertTrue(result.error.orEmpty().contains("installed app"))
    }

    @Test fun ordinaryTextDoesNotBecomeACommand() {
        val result = CompoundCommandParser.parse("I like searching for books in the library", apps)
        assertFalse(result.recognized)
    }
}
