package com.cue.offlinebrain.data

import java.util.Locale

object MacroRunPolicy {
    fun isFailureReply(reply: String): Boolean {
        val text = reply.trimStart().lowercase(Locale.ROOT)
        return text.startsWith("fail") ||
            text.startsWith("i couldn't") ||
            text.startsWith("i could not") ||
            text.startsWith("android could not") ||
            text.startsWith("permission required") ||
            text.startsWith("accessibility service is not enabled") ||
            text.startsWith("this phone could not")
    }
}
