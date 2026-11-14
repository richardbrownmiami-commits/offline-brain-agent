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
        Category.MESSAGES_SMS to setOf("text", "texts", "sms", "message", "messages", "messaging"),
        Category.CHAT to setOf("chat", "chats", "whatsapp", "telegram", "conversation", "conversations"),
        Category.CALLS to setOf("call", "calls", "missed", "ring", "phonecall", "phonecalls"),
        Category.SYSTEM to setOf("system", "android", "phone", "device"),
        Category.MUSIC_AND_MEDIA to setOf("music", "media", "youtube", "video", "videos", "song", "songs", "audio", "stream"),
        Category.SOCIAL to setOf("social", "instagram", "facebook", "twitter", "reddit", "posts", "tweet", "tweets"),
        Category.EMAIL to setOf("email", "emails", "mail", "inbox", "gmail"),
        Category.WEATHER to setOf("weather", "forecast"),
        Category.SHOPPING to setOf("shopping", "shop", "order", "orders", "delivery"),
        Category.FOOD to setOf("food", "restaurant", "restaurants", "swiggy", "zomato"),
        Category.GAMES to setOf("game", "games", "gaming"),
        Category.FINANCE to setOf("finance", "bank", "banking", "payment", "payments", "transaction", "transactions")
    )
    private val actionWords = mapOf(
        Action.SHOW to setOf("show", "display", "see", "find", "check"),
        Action.READ to setOf("read", "play"),
        Action.TELL to setOf("tell", "explain", "summarize", "describe"),
        Action.LIST to setOf("list", "enumerate"),
        Action.WHAT_IN to setOf("what", "in"),
        Action.ANY to setOf("any"),
        Action.OPEN to setOf("open")
    )
    private val modifiers = setOf("new", "unread", "today")
    private val ignoredWords = setOf(
        "me", "my", "the", "a", "an", "please", "did", "do", "does", "send", "sent", "say", "said",
        "get", "got", "received", "receive", "have", "has", "had", "about", "is", "are", "was", "were",
        "all", "for", "of", "on", "at", "to", "and", "or", "i", "you", "your", "with", "from", "whether",
        "notification", "notifications", "what's", "whats", "what", "who", "how", "when", "where", "which", "why", "there", "in", "recent", "latest", "newest",
        "last", "most", "please", "could", "would", "can", "showing", "getting", "gotten"
    )
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
            ?.takeUnless { it.equals("today", ignoreCase = true) }
        if (Regex("\\bfrom\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) && appMatch == null) {
            return Outcome.Rejected("Invalid app filter")
        }
        val withoutApp = if (appMatch != null && fromApp != null) raw.removeRange(appMatch.range) else raw
        val normalized = withoutApp.lowercase().replace("what's", "what is").replace(Regex("\\bwhats\\b"), "what is")
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()
        if (normalized.isEmpty()) return Outcome.Rejected("Empty query")
        val tokens = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
        val categoryScores = mutableMapOf<Category, Int>()
        val exactCategoryCandidates = linkedSetOf<Category>()
        val actionScores = mutableMapOf<Action, Int>()
        var unreadOnly = false
        var todayOnly = false
        for (token in tokens) {
            if (token in modifiers) {
                if (token in setOf("new", "unread")) unreadOnly = true
                if (token == "today") todayOnly = true
                continue
            }
            if (token in ignoredWords) continue
            val exactCategoryMatches = categoryWords.filterValues { token in it }.keys
            if (exactCategoryMatches.isNotEmpty()) {
                exactCategoryCandidates.addAll(exactCategoryMatches)
                continue
            }
            val exactActionMatches = actionWords.filterValues { token in it }.keys
            if (exactActionMatches.isNotEmpty()) {
                exactActionMatches.forEach { actionScores[it] = (actionScores[it] ?: 0) + 1 }
                continue
            }
            val modifier = nearestSignal(token, modifiers)
            if (modifier != null) {
                if (modifier in setOf("new", "unread")) unreadOnly = true
                if (modifier == "today") todayOnly = true
                continue
            }
            val categoryMatches = nearestCategories(token)
            val actionMatches = nearestActions(token)
            val categoryDistance = bestCategoryDistance(token)
            val actionDistance = bestActionDistance(token)
            if (actionMatches.isNotEmpty() &&
                (categoryMatches.isEmpty() || actionDistance!! <= (categoryDistance ?: Int.MAX_VALUE))) {
                actionMatches.forEach { actionScores[it] = (actionScores[it] ?: 0) + 1 }
                continue
            }
            if (categoryMatches.isNotEmpty()) {
                categoryMatches.forEach { categoryScores[it] = (categoryScores[it] ?: 0) + 1 }
                continue
            }
            // Unknown words are permitted; only recognized intent tokens score.
        }
        val foundCategories = if (exactCategoryCandidates.isNotEmpty()) exactCategoryCandidates else categoryScores.keys
        if (foundCategories.size > 1) return Outcome.Clarify(foundCategories.map { it.name }.toSet())
        val actionCandidates = actionScores.keys
        val explicitActions = actionCandidates - setOf(Action.WHAT_IN, Action.ANY)
        if (explicitActions.size > 1) return Outcome.Clarify(explicitActions.map { it.name }.toSet())
        val hasQuestion = "what" in tokens
        val hasIn = "in" in tokens
        val action = explicitActions.singleOrNull()
            ?: if (Action.ANY in actionCandidates) Action.ANY
            else if (hasQuestion && hasIn) Action.WHAT_IN else Action.SHOW
        val category = foundCategories.singleOrNull()
        val explicitStandalone = Regex("\\bnotifications?\\b").containsMatchIn(normalized)
        if (category == null && !explicitStandalone) return Outcome.Rejected("A notification category is required")
        return Outcome.Parsed(Query(action, category, unreadOnly, todayOnly, fromApp))
    }

    private fun nearestCategories(token: String): Set<Category> {
        val exact = categoryWords.filterValues { token in it }.keys
        if (exact.isNotEmpty()) return exact
        if (token.length < 3) return emptySet()
        val distances = categoryWords.flatMap { (category, words) -> words.map { category to distance(token, it, 2) } }
            .filter { it.second <= 2 }
        if (distances.isEmpty()) return emptySet()
        val best = distances.minOf { it.second }
        return distances.filter { it.second == best }.map { it.first }.toSet()
    }

    private fun bestCategoryDistance(token: String): Int? = categoryWords.values.asSequence().flatten()
        .map { distance(token, it, 2) }.minOrNull()?.takeIf { it <= 2 }

    private fun bestActionDistance(token: String): Int? = actionWords.values.asSequence().flatten()
        .map { distance(token, it, 2) }.minOrNull()?.takeIf { it <= 2 }

    private fun nearestActions(token: String): Set<Action> {
        val exact = actionWords.filterValues { token in it }.keys
        if (exact.isNotEmpty()) return exact
        if (token.length < 3) return emptySet()
        val distances = actionWords.flatMap { (action, words) -> words.map { action to distance(token, it, 2) } }
            .filter { it.second <= 2 }
        if (distances.isEmpty()) return emptySet()
        val best = distances.minOf { it.second }
        return distances.filter { it.second == best }.map { it.first }.toSet()
    }

    private fun nearestSignal(token: String, signals: Set<String>): String? {
        if (token in signals) return token
        if (token.length < 3) return null
        return signals.map { it to distance(token, it, 2) }.filter { it.second <= 2 }
            .minByOrNull { it.second }?.first
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

    data class NaturalSentence(
        val text: String,
        val category: NotificationQueryParser.Category,
        val action: NotificationQueryParser.Action = NotificationQueryParser.Action.SHOW,
        val unreadOnly: Boolean = false,
        val todayOnly: Boolean = false
    )

    val naturalSentences = listOf(
        NaturalSentence("Could you show me my latest text messages please?", NotificationQueryParser.Category.MESSAGES_SMS),
        NaturalSentence("Did I receive any new SMS today?", NotificationQueryParser.Category.MESSAGES_SMS, NotificationQueryParser.Action.ANY, true, true),
        NaturalSentence("Tell me what's in the message notifications", NotificationQueryParser.Category.MESSAGES_SMS, NotificationQueryParser.Action.TELL),
        NaturalSentence("Please list the text alerts from today", NotificationQueryParser.Category.MESSAGES_SMS, NotificationQueryParser.Action.LIST, todayOnly = true),
        NaturalSentence("Check whether there are unread SMS messages", NotificationQueryParser.Category.MESSAGES_SMS, unreadOnly = true),
        NaturalSentence("Can you show my WhatsApp chat alerts?", NotificationQueryParser.Category.CHAT),
        NaturalSentence("Read the newest Telegram conversations for me", NotificationQueryParser.Category.CHAT, NotificationQueryParser.Action.READ),
        NaturalSentence("I got new WhatsApp chats, could you find them?", NotificationQueryParser.Category.CHAT, unreadOnly = true),
        NaturalSentence("List my unread chats today", NotificationQueryParser.Category.CHAT, NotificationQueryParser.Action.LIST, true, true),
        NaturalSentence("Could you tell me about the latest Telegram notifications?", NotificationQueryParser.Category.CHAT, NotificationQueryParser.Action.TELL),
        NaturalSentence("Show all missed calls from earlier today", NotificationQueryParser.Category.CALLS, todayOnly = true),
        NaturalSentence("Tell me who rang with missed call alerts", NotificationQueryParser.Category.CALLS, NotificationQueryParser.Action.TELL),
        NaturalSentence("List unread call notifications, please", NotificationQueryParser.Category.CALLS, NotificationQueryParser.Action.LIST, unreadOnly = true),
        NaturalSentence("Can you find the most recent calls?", NotificationQueryParser.Category.CALLS),
        NaturalSentence("Show system update notifications from today", NotificationQueryParser.Category.SYSTEM, todayOnly = true),
        NaturalSentence("Tell me if Android has any unread device alerts", NotificationQueryParser.Category.SYSTEM, NotificationQueryParser.Action.TELL, unreadOnly = true),
        NaturalSentence("List my latest phone alerts", NotificationQueryParser.Category.SYSTEM, NotificationQueryParser.Action.LIST),
        NaturalSentence("Can you find recent Android notifications?", NotificationQueryParser.Category.SYSTEM),
        NaturalSentence("Show me my latest music alerts", NotificationQueryParser.Category.MUSIC_AND_MEDIA),
        NaturalSentence("Read the newest YouTube notifications", NotificationQueryParser.Category.MUSIC_AND_MEDIA, NotificationQueryParser.Action.READ),
        NaturalSentence("List audio updates from today", NotificationQueryParser.Category.MUSIC_AND_MEDIA, NotificationQueryParser.Action.LIST, todayOnly = true),
        NaturalSentence("Tell me which media apps sent alerts", NotificationQueryParser.Category.MUSIC_AND_MEDIA, NotificationQueryParser.Action.TELL),
        NaturalSentence("Show my latest Instagram notifications", NotificationQueryParser.Category.SOCIAL),
        NaturalSentence("Tell me if I have unread Facebook updates", NotificationQueryParser.Category.SOCIAL, NotificationQueryParser.Action.TELL, unreadOnly = true),
        NaturalSentence("List new social alerts today", NotificationQueryParser.Category.SOCIAL, NotificationQueryParser.Action.LIST, true, true),
        NaturalSentence("Could you show me the recent posts notifications?", NotificationQueryParser.Category.SOCIAL),
        NaturalSentence("Show the newest email alerts", NotificationQueryParser.Category.EMAIL),
        NaturalSentence("Read unread Gmail notifications today", NotificationQueryParser.Category.EMAIL, NotificationQueryParser.Action.READ, true, true),
        NaturalSentence("Tell me about my latest inbox updates", NotificationQueryParser.Category.EMAIL, NotificationQueryParser.Action.TELL),
        NaturalSentence("List mail notifications please", NotificationQueryParser.Category.EMAIL, NotificationQueryParser.Action.LIST),
        NaturalSentence("Show my local weather alerts", NotificationQueryParser.Category.WEATHER),
        NaturalSentence("Tell me the forecast notifications from today", NotificationQueryParser.Category.WEATHER, NotificationQueryParser.Action.TELL, todayOnly = true),
        NaturalSentence("List unread weather updates", NotificationQueryParser.Category.WEATHER, NotificationQueryParser.Action.LIST, unreadOnly = true),
        NaturalSentence("Could you find the latest weather notifications?", NotificationQueryParser.Category.WEATHER),
        NaturalSentence("Show my order notifications", NotificationQueryParser.Category.SHOPPING),
        NaturalSentence("Tell me about the latest shopping alerts", NotificationQueryParser.Category.SHOPPING, NotificationQueryParser.Action.TELL),
        NaturalSentence("List new delivery notices today", NotificationQueryParser.Category.SHOPPING, NotificationQueryParser.Action.LIST, true, true),
        NaturalSentence("Could you find notifications from the shop app?", NotificationQueryParser.Category.SHOPPING),
        NaturalSentence("Show my latest food alerts", NotificationQueryParser.Category.FOOD),
        NaturalSentence("Tell me about restaurant updates", NotificationQueryParser.Category.FOOD, NotificationQueryParser.Action.TELL),
        NaturalSentence("List Swiggy notifications from today", NotificationQueryParser.Category.FOOD, NotificationQueryParser.Action.LIST, todayOnly = true),
        NaturalSentence("Could you find new Zomato alerts please?", NotificationQueryParser.Category.FOOD, unreadOnly = true),
        NaturalSentence("Show my latest game notifications", NotificationQueryParser.Category.GAMES),
        NaturalSentence("Tell me about unread gaming alerts", NotificationQueryParser.Category.GAMES, NotificationQueryParser.Action.TELL, unreadOnly = true),
        NaturalSentence("List new games notices today", NotificationQueryParser.Category.GAMES, NotificationQueryParser.Action.LIST, true, true),
        NaturalSentence("Can you find notifications from my game app?", NotificationQueryParser.Category.GAMES),
        NaturalSentence("Show my latest bank alerts", NotificationQueryParser.Category.FINANCE),
        NaturalSentence("Tell me whether I have unread payment notifications", NotificationQueryParser.Category.FINANCE, NotificationQueryParser.Action.TELL, unreadOnly = true),
        NaturalSentence("List new finance updates from today", NotificationQueryParser.Category.FINANCE, NotificationQueryParser.Action.LIST, true, true),
        NaturalSentence("Could you find transaction alerts?", NotificationQueryParser.Category.FINANCE)
    )
}
