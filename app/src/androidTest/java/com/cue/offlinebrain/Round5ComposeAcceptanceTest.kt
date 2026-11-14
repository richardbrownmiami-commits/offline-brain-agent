package com.cue.offlinebrain

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cue.offlinebrain.data.MacroEntity
import com.cue.offlinebrain.data.NotificationEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Round5ComposeAcceptanceTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun chatQuickActionRowShowsOnlyNotifications() {
        composeRule.onNodeWithText("Chat").performClick()
        composeRule.onNodeWithTag("chat_quick_actions").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_quick_action_notifications").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Weather", substring = false).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("News", substring = false).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Alarm", substring = false).fetchSemanticsNodes().isEmpty())
    }

    @Test fun toolsShowsRefreshableDeviceStatusCard() {
        composeRule.onNodeWithText("Tools").performClick()
        composeRule.onNodeWithText("Device status · this phone", substring = false).assertExists()
        composeRule.onNodeWithText("Refresh", substring = false).assertExists()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Storage", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun notificationCardHidesSmsBodyUntilShownAndCanMarkRead() {
        val app = composeRule.activity.application as OfflineBrainApplication
        val dao = app.database.dao()
        val row = NotificationEntity(
            packageName = app.packageName,
            title = "Test sender",
            text = "Private test message body",
            postedAt = System.currentTimeMillis(),
            dedupeKey = "round7-card-ui-test",
            textHash = "round7-card-ui-test-hash",
            category = "Messages/SMS"
        )
        runBlocking { dao.insertNotifications(listOf(row)) }
        try {
            composeRule.onAllNodesWithText("Notifications", substring = false).get(1).performClick()
            composeRule.onNodeWithText("Show text", substring = false).performScrollTo().assertIsDisplayed()
            assertTrue(composeRule.onAllNodesWithText("Private test message body", substring = true).fetchSemanticsNodes().isEmpty())
            composeRule.onNodeWithText("Show text", substring = false).performClick()
            composeRule.onNodeWithText("Test sender: Private test message body", substring = false).assertExists()
            composeRule.onNodeWithText("Mark read", substring = false).performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Mark read", substring = false).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            runBlocking { dao.deleteNotificationByDedupeKey("round7-card-ui-test") }
        }
    }

    @Test fun floatingAssistantOffersExplicitTenSecondTest() {
        composeRule.onNodeWithText("Tools").performClick()
        composeRule.onNodeWithText("Set up floating button").performScrollTo().performClick()
        composeRule.onNodeWithText("Show bubble for 10 seconds").performScrollTo().assertIsDisplayed()
    }

    @Test fun voiceSettingsExplainReviewFirstAsTheDefault() {
        val app = composeRule.activity.application as OfflineBrainApplication
        runBlocking { app.settings.setAutoSendVoiceTranscript(false) }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Voice and speech").performClick()
        composeRule.onNodeWithText("Review before sending").assertIsDisplayed()
        composeRule.onNodeWithText("recognized words are placed in Chat so you can review or edit them before sending.", substring = true).assertIsDisplayed()
    }

    @Test fun backupPageExplainsEncryptionAndPassphraseBeforeExport() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Backup and restore").performClick()
        composeRule.onNodeWithText("How backup works").assertIsDisplayed()
        composeRule.onNodeWithText("The file is encrypted", substring = true).assertIsDisplayed()
    }

    @Test fun savedMacroCanBeLoadedIntoTheManualEditWizard() {
        val app = composeRule.activity.application as OfflineBrainApplication
        val id = runBlocking { app.database.dao().saveMacro(MacroEntity(name = "Round5 UI macro", steps = "weather in Delhi\nlatest news")) }
        try {
            composeRule.onNodeWithText("Tools").performClick()
            composeRule.onNodeWithText("Build or play a macro").performClick()
            composeRule.onNodeWithText("Round5 UI macro").assertIsDisplayed()
            composeRule.onNodeWithText("Edit", substring = false).performClick()
            composeRule.onNodeWithText("Edit a typed macro · step 1 of 2").assertIsDisplayed()
            composeRule.onNodeWithText("Step 1 · Run a Chat request").assertIsDisplayed()
            composeRule.onNodeWithText("Step 2 · Run a Chat request").assertIsDisplayed()
            composeRule.onNodeWithText("Insert after").assertIsDisplayed()
            composeRule.onNodeWithText("Move down").assertIsDisplayed()
        } finally {
            runBlocking { app.database.dao().deleteMacro(id) }
        }
    }

    @Test fun firstRunMacroTutorialBuildsTheTypedThreeStepExample() {
        val app = composeRule.activity.application as OfflineBrainApplication
        val previouslySeen = runBlocking { app.settings.settings.first().macroTutorialSeen }
        runBlocking { app.settings.setMacroTutorialSeen(false) }
        try {
            composeRule.onNodeWithText("Tools").performClick()
            composeRule.onNodeWithText("Build or play a macro").performClick()
            composeRule.onNodeWithText("Three-step macro tutorial · 1 of 3").assertIsDisplayed()
            composeRule.onNodeWithText("Build the 3-step example").performClick()
            composeRule.onNodeWithText("Step 1 · Open an app").assertIsDisplayed()
            composeRule.onNodeWithText("Step 2 · Wait").assertIsDisplayed()
            composeRule.onNodeWithText("Step 3 · Run a Chat request").assertIsDisplayed()
            composeRule.onNodeWithText("search cats", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("Review macro").assertIsDisplayed()
        } finally {
            runBlocking { app.settings.setMacroTutorialSeen(previouslySeen) }
        }
    }
}
