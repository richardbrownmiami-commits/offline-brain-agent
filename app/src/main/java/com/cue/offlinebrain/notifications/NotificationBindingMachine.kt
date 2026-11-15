package com.cue.offlinebrain.notifications

/** Pure policy for the NotificationListenerService lifecycle; Android callbacks remain in the service. */
class NotificationBindingMachine(private val idleTimeoutMillis: Long = 30_000L) {
    enum class State { UNBOUND, CONNECTING, ACTIVE }
    enum class UnbindReason { APP_BACKGROUND, IDLE_30_SECONDS, USER_TEST, DISCONNECTED }

    data class Snapshot(
        val state: State,
        val lastConnectAt: Long? = null,
        val lastUnbindAt: Long? = null,
        val lastUnbindReason: UnbindReason? = null,
        val lastNotificationAt: Long? = null,
        val rebindRequests: Long = 0,
        val connectCount: Long = 0,
        val unbindCount: Long = 0,
        val notificationCount: Long = 0
    ) {
        fun secondsIdle(now: Long): Long = lastNotificationAt?.let { ((now - it).coerceAtLeast(0L) / 1_000L) } ?: 0L
    }

    private var snapshot = Snapshot(State.UNBOUND)

    @Synchronized fun snapshot(): Snapshot = snapshot

    /** Returns false while a connection is already pending/active, preventing rebind loops. */
    @Synchronized fun requestRebind(): Boolean {
        if (snapshot.state != State.UNBOUND) return false
        snapshot = snapshot.copy(state = State.CONNECTING, rebindRequests = snapshot.rebindRequests + 1)
        return true
    }

    @Synchronized fun connected(now: Long) {
        snapshot = snapshot.copy(state = State.ACTIVE, lastConnectAt = now, connectCount = snapshot.connectCount + 1)
    }

    @Synchronized fun notificationPosted(now: Long) {
        snapshot = snapshot.copy(lastNotificationAt = now, notificationCount = snapshot.notificationCount + 1)
    }

    /** Foreground use pins an on-demand listener. A background listener is released at 30s idle. */
    @Synchronized fun unbindReason(now: Long, appForeground: Boolean, alwaysOn: Boolean): UnbindReason? {
        if (snapshot.state != State.ACTIVE || appForeground || alwaysOn) return null
        val lastActivity = snapshot.lastNotificationAt ?: snapshot.lastConnectAt ?: now
        if (now - lastActivity < idleTimeoutMillis) return null
        return UnbindReason.IDLE_30_SECONDS
    }

    @Synchronized fun appBackgrounded(alwaysOn: Boolean): UnbindReason? =
        if (snapshot.state == State.ACTIVE && !alwaysOn) UnbindReason.APP_BACKGROUND else null

    @Synchronized fun unbound(now: Long, reason: UnbindReason) {
        if (snapshot.state == State.UNBOUND) return
        snapshot = snapshot.copy(
            state = State.UNBOUND,
            lastUnbindAt = now,
            lastUnbindReason = reason,
            unbindCount = snapshot.unbindCount + 1
        )
    }

    @Synchronized fun connectionFailed() {
        if (snapshot.state == State.CONNECTING) snapshot = snapshot.copy(state = State.UNBOUND)
    }
}

object NotificationRebindWaitPolicy {
    const val MAX_WAIT_MILLIS = 3_000L
    fun timedOut(startedAt: Long, now: Long, timeoutMillis: Long = MAX_WAIT_MILLIS): Boolean =
        now - startedAt >= timeoutMillis
}
