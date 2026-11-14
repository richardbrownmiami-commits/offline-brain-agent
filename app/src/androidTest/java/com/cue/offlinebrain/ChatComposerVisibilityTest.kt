package com.cue.offlinebrain

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.cue.offlinebrain.data.AlarmEntity
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ChatComposerVisibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatComposerIsVisibleOnLaunch() {
        composeRule.onNodeWithText("Chat").performClick()
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertHasClickAction()
        composeRule.onNodeWithTag("chat_input").performTextInput("Keyboard input test")
        composeRule.onNodeWithTag("chat_input").assertTextContains("Keyboard input test")
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
    }

    @Test
    fun repeatingAlarmSwipeOffersUndo() {
        val app = composeRule.activity.application as OfflineBrainApplication
        val id = runBlocking {
            app.database.dao().addAlarm(AlarmEntity(label = "Swipe undo test", triggerAt = System.currentTimeMillis() + 3_600_000L, cron = "0 8 * * *", enabled = false))
        }
        try {
            composeRule.onNodeWithText("Alarms").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Swipe undo test").assertIsDisplayed().performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("Swipe undo test").assertIsDisplayed()
        } finally {
            runBlocking { app.database.dao().deleteAlarm(id) }
        }
    }
}
