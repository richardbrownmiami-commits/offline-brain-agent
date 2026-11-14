package com.cue.offlinebrain.notifications

/**
 * Parses notification questions locally and deterministically; it performs no network or LLM work.
 * Fuzzy correction is limited to this small command vocabulary (distance <= 2); ties clarify rather
 * than guessing, and arbitrary contact/app values are never fuzzily expanded.
 */
object NotificationQueryParser {
    enum class Action { SHOW, READ, TELL, LIST, WHAT_IN, ANY, OPEN }
    enum class Category { MESSAGES_SMS, CHAT, CALLS, SYSTEM, MUSIC_AND_MEDIA, SOCIAL, EMAIL, WEATHER, SHOPPING, FOOD, GAMES, FINANCE }

    data class Query(
        val action: Action,
        val category: Category? = null,
        val unreadOnly: Boolean = false,
        val todayOnly: Boolean = false,
        val fromApp: String? = null,
        val contact: String? = null
    )

    sealed class Outcome {
        data class Parsed(val query: Query) : Outcome()
        data class Clarify(val candidates: Set<String>) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    private val categoryWords = mapOf(
        Category.MESSAGES_SMS to setOf("text", "sms", "message", "messages"),
        Category.CHAT to setOf("chat", "whatsapp", "telegram"),
        Category.CALLS to setOf("call", "calls", "missed"),
        Category.SYSTEM to setOf("system", "android", "phone"),
        Category.MUSIC_AND_MEDIA to setOf("music", "media", "youtube"),
        Category.SOCIAL to setOf("social"), Category.EMAIL to setOf("email"),
        Category.WEATHER to setOf("weather"), Category.SHOPPING to setOf("shopping"),
        Category.FOOD to setOf("food"), Category.GAMES to setOf("games"), Category.FINANCE to setOf("finance")
    )
    private val actionWords = mapOf(
        Action.SHOW to setOf("show"), Action.READ to setOf("read"), Action.TELL to setOf("tell"),
        Action.LIST to setOf("list"), Action.WHAT_IN to setOf("what", "in"),
        Action.ANY to setOf("any"), Action.OPEN to setOf("open")
    )
    private val vocabulary = (categoryWords.values.flatten() + actionWords.values.flatten() + setOf("new", "unread", "today", "from", "notification", "notifications", "did", "send", "sent", "me", "about", "is", "are", "the", "what's", "whats", "i", "get", "got", "received", "have")).toSet()
    private val filler = setOf("me", "about", "the", "a", "an", "my", "please", "notification", "notifications", "did", "send", "sent", "is", "are", "what", "in", "what's", "whats", "i", "get", "got", "received", "have")

    fun parse(input: String): Outcome {
        val raw = input.trim()
        if (raw.isEmpty()) return Outcome.Rejected("Empty query")

        val startsContactQuery = Regex("^what\\s+did\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val mentionsSend = Regex("\\bsend\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (startsContactQuery && mentionsSend) {
            val contactMatch = Regex(
                "^\\s*what\\s+did\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} .'_-]{0,47}?)\\s+send(?:\\s+me)?(?:\\s+notifications?)?\\s*[?!.,;:]*\\s*$",
                RegexOption.IGNORE_CASE
            ).matchEntire(raw) ?: return Outcome.Rejected("Invalid contact query")
            val contact = boundedValue(contactMatch.groupValues.getOrNull(1).orEmpty())
                ?: return Outcome.Rejected("Invalid contact")
            return Outcome.Parsed(Query(Action.WHAT_IN, contact = contact))
        }

        val appMatch = Regex(
            "\\bfrom\\s+([\\p{L}\\p{N}](?:[\\p{L}\\p{N}._-]{0,46}[\\p{L}\\p{N}])?)(?=$|[\\s?!.,;:])",
            RegexOption.IGNORE_CASE
        ).find(raw)
        val fromApp = appMatch?.groupValues?.getOrNull(1)?.takeIf { it.length in 1..48 }
        if (Regex("\\bfrom\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) && appMatch == null) {
            return Outcome.Rejected("Invalid app filter")
        }
        val withoutApp = if (appMatch != null) raw.removeRange(appMatch.range) else raw
        val normalized = withoutApp.lowercase().replace("what's", "what is").replace(Regex("\\bwhats\\b"), "what is")
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()
        if (normalized.isEmpty()) return Outcome.Rejected("Empty query")
        val rawTokens = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
        val replacements = mutableMapOf<String, String>()
        for (token in rawTokens) {
            if (token in vocabulary || token in filler) continue
            val near = vocabulary.map { it to distance(token, it, 2) }.filter { it.second <= 2 }
            if (near.isEmpty()) return Outcome.Rejected("Unrecognized term: $token")
            val best = near.minOf { it.second }
            val choices = near.filter { it.second == best }.map { it.first }.toSet()
            val matchedCategories = choices.mapNotNull { word -> categoryWords.entries.firstOrNull { word in it.value }?.key }
            if (choices.isNotEmpty() && matchedCategories.size == choices.size && matchedCategories.distinct().size == 1) {
                replacements[token] = categoryWords[matchedCategories.first()]?.firstOrNull() ?: choices.first()
                continue
            }
            if (choices.size != 1) return Outcome.Clarify(choices)
            replacements[token] = choices.first()
        }
        val tokens = rawTokens.map { replacements[it] ?: it }
        val foundCategories = categoryWords.mapNotNull { (cat, words) -> if (tokens.any { it in words }) cat else null }.toSet()
        if (foundCategories.size > 1) return Outcome.Clarify(foundCategories.map { it.name }.toSet())
        val actionCandidates = actionWords.filterValues { words -> tokens.any { it in words } }.keys
        val explicitActions = actionCandidates - Action.WHAT_IN
        if (explicitActions.size > 1) return Outcome.Clarify(explicitActions.map { it.name }.toSet())
        val hasQuestion = "what" in tokens
        val hasIn = "in" in tokens
        val action = explicitActions.singleOrNull() ?: if (hasQuestion && hasIn) Action.WHAT_IN else Action.SHOW
        val wordsForAction = actionWords[action].orEmpty()
        val extras = tokens.filterNot { it in filler || it in wordsForAction || categoryWords.values.any { set -> it in set } || it in setOf("new", "unread", "today") }
        if (extras.isNotEmpty()) return Outcome.Rejected("Unexpected term: ${extras.first()}")
        val category = foundCategories.singleOrNull()
        val explicitStandalone = tokens.any { it in setOf("notification", "notifications") }
        if (category == null && !explicitStandalone) return Outcome.Rejected("A notification category is required")
        return Outcome.Parsed(Query(action, category, tokens.any { it == "new" || it == "unread" }, tokens.contains("today"), fromApp))
    }

    private fun boundedValue(value: String): String? {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        return cleaned.takeIf { it.length in 1..48 && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == '.' || c == '\'' } }
    }

    private fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val curr = IntArray(b.length + 1)
            curr[0] = i + 1
            var rowMin = curr[0]
            for (j in b.indices) {
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
                rowMin = minOf(rowMin, curr[j + 1])
            }
            if (rowMin > limit) return limit + 1
            prev = curr
        }
        return prev[b.length]
    }
}

/** Shared phrase set used by host regression tests and the on-device self-diagnostic. */
object NotificationQueryRegressionCases {
    val positivePhrases: List<String> = listOf(
        "show text notification", "read sms notifications", "tell me about message notification", "list messages",
        "what in chat notification", "show whatsapp notifications", "read telegram notification", "list chat",
        "show call notification", "read calls", "tell missed notification", "list calls notifications",
        "show system notification", "read android notifications", "tell phone notification", "list system",
        "show music notification", "read media notifications", "tell youtube notification", "list music",
        "show social notification", "read social notifications", "tell social", "list social notification",
        "show email notification", "read email notifications", "tell email", "list email notification",
        "show weather notification", "read weather notifications", "tell weather", "list weather notification",
        "show shopping notification", "read shopping notifications", "tell shopping", "list shopping notification",
        "show food notification", "read food notifications", "tell food", "list food notification",
        "show games notification", "read games notifications", "tell games", "list games notification",
        "show finance notification", "read finance notifications", "tell finance", "list finance notification",
        "open text notification", "any new calls notification", "show unread email today", "list music from youtube"
    )
}
