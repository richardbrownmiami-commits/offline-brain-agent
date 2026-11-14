package com.cue.offlinebrain

import android.view.ViewGroup
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cue.offlinebrain.overlay.OverlayComposeOwners
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class OverlayComposeAttachTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun composeViewAttachesAndComposesWithLifecycleAndSavedStateOwners() {
        lateinit var overlayView: ComposeView
        composeRule.activity.runOnUiThread {
            val activity = composeRule.activity
            overlayView = ComposeView(activity).apply {
                OverlayComposeOwners.install(this, activity, activity)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { Text("Overlay attached") }
            }
            activity.addContentView(
                overlayView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        composeRule.waitForIdle()
        assertTrue("ComposeView should be attached to the window", overlayView.isAttachedToWindow)
        composeRule.onNodeWithText("Overlay attached").assertIsDisplayed()
        composeRule.activity.runOnUiThread {
            (overlayView.parent as? ViewGroup)?.removeView(overlayView)
            overlayView.disposeComposition()
        }
    }
}
