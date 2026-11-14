package com.cue.offlinebrain.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cue.offlinebrain.diagnostics.CrashLog
import java.util.concurrent.ConcurrentHashMap

object NotificationReplyRegistry {
    private data class ReplyAction(val pendingIntent: PendingIntent, val inputs: Array<RemoteInput>)
    private val actions = ConcurrentHashMap<String, ReplyAction>()

    fun identity(packageName: String, postedAt: Long): String = "$packageName:$postedAt"

    fun register(packageName: String, postedAt: Long, pendingIntent: PendingIntent?, inputs: Array<RemoteInput>?) {
        val key = identity(packageName, postedAt)
        if (pendingIntent == null || inputs.isNullOrEmpty()) actions.remove(key)
        else {
            actions[key] = ReplyAction(pendingIntent, inputs)
            if (actions.size > MAX_ACTIONS) actions.keys.take(actions.size - MAX_ACTIONS).forEach(actions::remove)
        }
    }

    fun canReply(packageName: String, postedAt: Long): Boolean = actions.containsKey(identity(packageName, postedAt))

    fun reply(context: Context, packageName: String, postedAt: Long, text: String): Boolean {
        val action = actions[identity(packageName, postedAt)] ?: return false
        val value = text.trim().take(2000)
        if (value.isBlank()) return false
        return try {
            val fillIn = Intent()
            val results = Bundle()
            action.inputs.forEach { results.putCharSequence(it.resultKey, value) }
            RemoteInput.addResultsToIntent(action.inputs, fillIn, results)
            action.pendingIntent.send(context, 0, fillIn)
            true
        } catch (error: Exception) {
            CrashLog.record("NOTIFICATIONS", "notification reply action could not be sent", error)
            false
        }
    }

    fun remove(packageName: String, postedAt: Long) { actions.remove(identity(packageName, postedAt)) }
    fun clear() { actions.clear() }

    private const val MAX_ACTIONS = 500
}
