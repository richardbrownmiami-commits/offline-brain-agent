package com.cue.offlinebrain.notifications

/**
 * Bounded, process-local aggregation for skip diagnostics. It performs no I/O and never logs callback
 * details. Call [record] from the decision path and forward only its returned expired summaries to
 * the existing logger; call [drain] when flushing/shutting down. Ongoing/media updates should pass
 * [noisyUpdate] = true so they are not counted or emitted.
 */
class NotificationDiagnosticAggregator(
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS
) {
    data class Summary(
        val packageName: String,
        val minute: Long,
        val reasonCode: String,
        val count: Long
    ) {
        /** Concise, content-free line suitable for an app-private diagnostic log. */
        fun asLine(): String = "notification-skip package=$packageName minute=$minute reason=$reasonCode count=$count"
    }

    private data class Key(val packageName: String, val minute: Long, val reasonCode: String)
    private val buckets = LinkedHashMap<Key, Long>()
    private val overflowKey = Key(OVERFLOW_PACKAGE, OVERFLOW_MINUTE, OVERFLOW_REASON)

    init {
        require(maxBuckets >= 1) { "maxBuckets must be positive" }
    }

    /** Records a skip and returns summaries for buckets from earlier minutes that have expired. */
    @Synchronized
    fun record(
        packageName: String,
        reason: NotificationDecisionReason,
        timestamp: Long,
        noisyUpdate: Boolean = false
    ): List<Summary> {
        if (noisyUpdate || reason == NotificationDecisionReason.SAVED) return emptyList()
        val minute = Math.floorDiv(timestamp, MILLIS_PER_MINUTE)
        val expired = expireBefore(minute)
        val candidate = Key(sanitizePackage(packageName), minute, reason.code)
        if (buckets.containsKey(candidate)) {
            buckets[candidate] = (buckets[candidate] ?: 0L) + 1L
        } else if (regularBucketCount() < maxBuckets - 1) {
            buckets[candidate] = 1L
        } else {
            // A single constant overflow key keeps attacker-controlled package/minute/reason
            // cardinality from growing the map. It deliberately does not retain input dimensions.
            buckets[overflowKey] = (buckets[overflowKey] ?: 0L) + 1L
        }
        return expired
    }

    /** Returns all pending summaries and empties the in-memory map. */
    @Synchronized
    fun drain(): List<Summary> {
        val result = buckets.map { (key, count) -> Summary(key.packageName, key.minute, key.reasonCode, count) }
            .sortedWith(compareBy<Summary> { it.minute }.thenBy { it.packageName }.thenBy { it.reasonCode })
        buckets.clear()
        return result
    }

    /** Current bucket count, exposed for boundedness diagnostics and deterministic tests. */
    @Synchronized
    fun bucketCount(): Int = buckets.size

    private fun expireBefore(minute: Long): List<Summary> {
        val expiredKeys = buckets.keys.filter { it != overflowKey && it.minute < minute }
        val result = expiredKeys.mapNotNull { key ->
            buckets.remove(key)?.let { Summary(key.packageName, key.minute, key.reasonCode, it) }
        }.toMutableList()
        return result.sortedWith(compareBy<Summary> { it.minute }.thenBy { it.packageName }.thenBy { it.reasonCode })
    }

    private fun regularBucketCount(): Int = buckets.size - if (buckets.containsKey(overflowKey)) 1 else 0

    private fun sanitizePackage(value: String): String {
        if (value.isBlank()) return UNKNOWN_PACKAGE
        // Package identifiers are not display labels: keep only their conventional ASCII alphabet,
        // cap length, and replace anything else (including controls/whitespace) with '_'.
        val bounded = value.take(MAX_PACKAGE_LENGTH)
        val cleaned = buildString(bounded.length) {
            bounded.forEach { ch -> append(if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_') ch else '_') }
        }.trim('_', '.')
        return cleaned.ifEmpty { UNKNOWN_PACKAGE }
    }

    companion object {
        const val DEFAULT_MAX_BUCKETS = 256
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_PACKAGE_LENGTH = 128
        private const val UNKNOWN_PACKAGE = "unknown"
        private const val OVERFLOW_PACKAGE = "[overflow]"
        private const val OVERFLOW_REASON = "overflow"
        private const val OVERFLOW_MINUTE = Long.MIN_VALUE
    }
}
