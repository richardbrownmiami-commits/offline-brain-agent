package com.cue.offlinebrain.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainReplyCodecTest {
    @Test fun textDeviceAndDocumentRepliesRoundTrip() {
        val replies = listOf(
            BrainReply.Text("hello"),
            BrainReply.DeviceStatusCard("Device status", listOf(DeviceStatusRow("Battery", "74%", 74), DeviceStatusRow("Network", "online", null)), "local only"),
            BrainReply.DocumentSummary("Quarterly notes", "Summary: three source-backed points.", "Page one readable content.", "quarterly.pdf")
        )
        replies.forEach { reply ->
            assertEquals(reply, BrainReplyCodec.decode(BrainReplyCodec.encode(reply)))
        }
    }

    @Test fun notificationCardsRoundTripCountsItemsAndCollapseMetadata() {
        val reply = BrainReply.NotificationList(
            title = "Music and media notifications",
            category = "Music and media",
            totalCount = 3,
            unreadCount = 2,
            latestAt = 1_730_000_000_000L,
            categoryCounts = listOf(ReplyCategoryCount("Music and media", 3)),
            items = listOf(ReplyNotificationItem(12, "com.example.player", "Night Queen Live Stream", "", "Music and media", 1_730_000_000_000L, duplicateCount = 3)),
            note = "saved locally"
        )
        assertEquals(reply, BrainReplyCodec.decode(BrainReplyCodec.encode(reply)))
        assertTrue(reply.plainText.contains("Night Queen Live Stream x3"))

        val summary = BrainReply.NotificationSummaryCard("Calls", "Calls", 5, 2, 1_730_000_000_000L,
            listOf(ReplyCategoryCount("Calls", 5)))
        assertEquals(summary, BrainReplyCodec.decode(BrainReplyCodec.encode(summary)))
    }

    @Test fun unknownOrMalformedPayloadFallsBackCleanly() {
        assertNull(BrainReplyCodec.decode("not json"))
        assertNull(BrainReplyCodec.decode("{\"version\":99,\"type\":\"text\",\"text\":\"future\"}"))
        assertNull(BrainReplyCodec.decode("{\"version\":1,\"type\":\"future\"}"))
    }

    @Test fun legacyPdfTextBecomesTypedDocumentAndKeepsCleanedSourceText() {
        val reply = brainReplyFromLegacyText("Summary from pages 1–2\n--- CLEANED TEXT ---\nSource line one.\nSource line two.", "report.pdf")
        assertTrue(reply is BrainReply.DocumentSummary)
        reply as BrainReply.DocumentSummary
        assertEquals("Summary from pages 1–2", reply.summary)
        assertEquals("Source line one.\nSource line two.", reply.cleanedText)
        assertEquals("report.pdf", reply.sourceName)
    }
}
