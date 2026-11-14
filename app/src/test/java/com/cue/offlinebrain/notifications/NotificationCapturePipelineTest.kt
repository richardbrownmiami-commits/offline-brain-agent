package com.cue.offlinebrain.notifications

import com.cue.offlinebrain.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCapturePipelineTest {
    private val base = AppSettings(notificationLiveCaptureEnabled = true)

    @Test fun emptyAllowAndBlockListsAllowAllThirdPartyAppsByDefault() {
        assertNull(reason(settings = base, packageName = "com.example.anything"))
        assertEquals(NotificationDecisionReason.OWN_PACKAGE, reason(settings = base, packageName = "com.cue.offlinebrain", ownPackage = "com.cue.offlinebrain"))
    }

    @Test fun tangoLiveAlertsAreSocialAndTransportRequiresMediaMetadata() {
        assertEquals("Social", NotificationCategory.infer("com.sgiggle.production", null, android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("Other", NotificationCategory.infer("com.example.live", android.app.Notification.CATEGORY_TRANSPORT, android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("Music and media", NotificationCategory.infer("com.example.player", android.app.Notification.CATEGORY_TRANSPORT, android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED, hasMediaSession = true))
    }

    @Test fun captureOffHasItsOwnSkipReason() {
        assertEquals(NotificationDecisionReason.CAPTURE_OFF, reason(settings = base.copy(notificationLiveCaptureEnabled = false)))
    }

    @Test fun blockedAppWinsAndMatchesChildPackages() {
        val settings = base.copy(notificationBlocklist = "com.example.blocked")
        assertEquals(NotificationDecisionReason.BLOCKED_APP, reason(settings = settings, packageName = "com.example.blocked.child"))
    }

    @Test fun nonEmptyAllowListRejectsUnlistedPackageButAllowsListedChildren() {
        val settings = base.copy(notificationAllowlist = "com.example.allowed")
        assertEquals(NotificationDecisionReason.NOT_IN_ALLOW_LIST, reason(settings = settings, packageName = "com.other.app"))
        assertNull(reason(settings = settings, packageName = "com.example.allowed.child"))
    }

    @Test fun appOwnedTestCaptureIsNotDroppedByOwnPackageFilter() {
        assertNull(reason(settings = base, packageName = "com.cue.offlinebrain", ownPackage = "com.cue.offlinebrain", isTest = true))
    }

    @Test fun testCaptureStillUsesTheLiveAllowAndBlockFilters() {
        assertEquals(NotificationDecisionReason.BLOCKED_APP, reason(
            settings = base.copy(notificationBlocklist = "com.cue.diagnostic"),
            packageName = "com.cue.diagnostic", isTest = true
        ))
        assertEquals(NotificationDecisionReason.NOT_IN_ALLOW_LIST, reason(
            settings = base.copy(notificationAllowlist = "com.example.allowed"),
            packageName = "com.cue.diagnostic", isTest = true
        ))
    }

    @Test fun groupSummaryIsSkippedOnlyWhenAnActiveChildExists() {
        assertEquals(NotificationDecisionReason.GROUP_SUMMARY, reason(settings = base, isSummary = true, hasChild = true))
        assertNull(reason(settings = base, title = "", text = "", isSummary = true, hasChild = false))
    }

    @Test fun emptyNonSummaryIsIdentified() {
        assertEquals(NotificationDecisionReason.EMPTY_TEXT, reason(settings = base, title = "", text = ""))
    }

    @Test fun ongoingNotificationsAreSavedInitiallyAndRateLimitedAfterSuccessfulSave() {
        assertNull(reason(settings = base, ongoing = true, ongoingRecentlySaved = false))
        assertEquals(NotificationDecisionReason.ONGOING, reason(settings = base, ongoing = true, ongoingRecentlySaved = true))
    }

    @Test fun rapidDuplicateIsReportedAsRateLimited() {
        assertEquals(NotificationDecisionReason.RATE_LIMITED, reason(settings = base, burstDuplicate = true))
    }

    @Test fun roomDuplicateAndSuccessfulInsertMapToDistinctDecisions() {
        assertEquals(NotificationDecisionReason.SAVED, NotificationCapturePolicy.reasonForStoreResult(true))
        assertEquals(NotificationDecisionReason.DUPLICATE, NotificationCapturePolicy.reasonForStoreResult(false))
    }

    @Test fun everySkipCodeIsStableAndAvailableToDiagnostics() {
        val codes = NotificationDecisionReason.values().filter { it != NotificationDecisionReason.SAVED }.map { it.code }
        listOf("blocked-app", "not-in-allow-list", "ongoing", "group-summary", "duplicate", "empty-text", "rate-limited", "capture-off", "own-package", "exception")
            .forEach { assertTrue("missing reason $it", it in codes) }
        assertEquals("exception", NotificationDecisionReason.EXCEPTION.code)
        assertEquals("null-sbn", NotificationDecisionReason.NULL_SBN.code)
        assertEquals("missing-notification", NotificationDecisionReason.MISSING_NOTIFICATION.code)
    }

    @Test fun ongoingRateLimitUsesLastSuccessfulSaveNotLastSuppressedUpdate() {
        val limiter = OngoingNotificationRateLimiter(30_000L)
        assertFalse(limiter.shouldSkip("key", 1_000L))
        limiter.markSaved("key", 1_000L)
        assertTrue(limiter.shouldSkip("key", 2_000L))
        assertTrue(limiter.shouldSkip("key", 20_000L))
        assertTrue(limiter.shouldSkip("key", 30_999L))
        assertFalse(limiter.shouldSkip("key", 31_000L))
    }

    @Test fun telemetryKeepsOnlyTwentyPackageAndReasonLines() {
        NotificationPipelineTelemetry.resetForTests()
        repeat(25) { index ->
            NotificationPipelineTelemetry.callbackReceived("com.example.app$index", "Other", "none", 0, "unit-test")
            NotificationPipelineTelemetry.decided(
                NotificationPipelineDecision("com.example.app$index", NotificationDecisionReason.EMPTY_TEXT, "Other", "none", 0, "unit-test")
            )
        }
        NotificationPipelineTelemetry.callbackRemoved("com.example.removed", "unit-test")
        val snapshot = NotificationPipelineTelemetry.snapshot.value
        assertEquals(25, snapshot.received)
        assertEquals(1, snapshot.removed)
        assertEquals(20, snapshot.recent.size)
        assertEquals(25, snapshot.skippedByReason["empty-text"])
        assertEquals("com.example.app5", snapshot.recent.firstOrNull()?.packageName)
        assertEquals("empty-text", snapshot.recent.firstOrNull()?.reason)
    }

    private fun reason(
        settings: AppSettings = base,
        packageName: String = "com.example.app",
        ownPackage: String = "com.cue.offlinebrain",
        title: String = "Title",
        text: String = "Body",
        isSummary: Boolean = false,
        hasChild: Boolean = false,
        ongoing: Boolean = false,
        isTest: Boolean = false,
        ongoingRecentlySaved: Boolean = false,
        burstDuplicate: Boolean = false
    ): NotificationDecisionReason? = NotificationCapturePolicy.rejection(
        settings, packageName, ownPackage, title, text, isSummary, hasChild, ongoing, isTest, ongoingRecentlySaved, burstDuplicate
    )
}
