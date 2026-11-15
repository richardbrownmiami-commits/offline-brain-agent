package com.cue.offlinebrain.notifications

/**
 * Parses notification questions locally and deterministically; it performs no network or LLM work.
 * Fuzzy correction is limited to this small command vocabulary (distance <= 2); ties clarify rather
 * than guessing, and arbitrary contact/app values are never fuzzily expanded.
 */
object NotificationQueryParser {
    enum class Action { SHOW, READ, TELL, LIST, WHAT_IN, ANY, OPEN, MARK_UNREAD, DELETE, CLEAR, REPLY }
    enum class Category { MESSAGES_SMS, CHAT, CALLS, SYSTEM, MUSIC_AND_MEDIA, SOCIAL, EMAIL, WEATHER, SHOPPING, FOOD, GAMES, FINANCE }

    data class Query(
        val action: Action,
        val category: Category? = null,
        val unreadOnly: Boolean = false,
        val todayOnly: Boolean = false,
        val fromApp: String? = null,
        val contact: String? = null,
        val yesterdayOnly: Boolean = false,
        val lastMinutes: Int? = null,
        val lastHours: Int? = null,
        val lastDays: Int? = null,
        val olderThanDays: Int? = null,
        val replyText: String? = null,
        val notificationId: Long? = null
    )

    sealed class Outcome {
        data class Parsed(val query: Query) : Outcome()
        data class Clarify(val candidates: Set<String>) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    private val categoryWords = mapOf(
        Category.MESSAGES_SMS to setOf("text", "texts", "sms", "message", "messages", "messaging", "texts", "texting", "smses"),
        Category.CHAT to setOf("chat", "chats", "whatsapp", "telegram", "conversation", "conversations", "signal", "messenger", "discord", "slack"),
        Category.CALLS to setOf("call", "calls", "missed", "ring", "phonecall", "phonecalls", "dial", "voicemail", "voicemails"),
        Category.SYSTEM to setOf("system", "android", "phone", "device", "battery", "update", "updates", "alert", "alerts"),
        Category.MUSIC_AND_MEDIA to setOf("music", "media", "youtube", "video", "videos", "song", "songs", "audio", "stream", "spotify", "podcast", "podcasts"),
        Category.SOCIAL to setOf("social", "instagram", "facebook", "twitter", "reddit", "posts", "tweet", "tweets", "tiktok", "snapchat", "linkedin"),
        Category.EMAIL to setOf("email", "emails", "mail", "inbox", "gmail", "outlook"),
        Category.WEATHER to setOf("weather", "forecast", "forecasts"),
        Category.SHOPPING to setOf("shopping", "shop", "order", "orders", "delivery", "deliveries", "package", "packages", "amazon"),
        Category.FOOD to setOf("food", "restaurant", "restaurants", "swiggy", "zomato", "meal", "meals", "grocery", "groceries"),
        Category.GAMES to setOf("game", "games", "gaming", "xbox", "playstation"),
        Category.FINANCE to setOf("finance", "bank", "banking", "payment", "payments", "transaction", "transactions", "bill", "bills", "money")
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
    private val modifiers = setOf("new", "unread", "today", "latest", "unseen", "newest")
    private val ignoredWords = setOf(
        "me", "my", "the", "a", "an", "please", "did", "do", "does", "send", "sent", "say", "said",
        "get", "got", "received", "receive", "have", "has", "had", "about", "is", "are", "was", "were",
        "all", "for", "of", "on", "at", "to", "and", "or", "i", "you", "your", "with", "from", "whether",
        "notification", "notifications", "what's", "whats", "what", "who", "how", "when", "where", "which", "why", "there", "in", "recent", "latest", "newest",
        "last", "most", "could", "would", "can", "showing", "getting", "gotten"
    )

    fun parse(input: String): Outcome {
        val raw = input.trim()
        if (raw.isEmpty()) return Outcome.Rejected("Empty query")

        Regex("^reply\\s+to\\s+([^:]{1,48})\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { m ->
            val target = boundedValue(m.groupValues[1]) ?: return Outcome.Rejected("Invalid reply target")
            val text = m.groupValues[2].trim()
            if (text.isEmpty() || text.length > 500) return Outcome.Rejected("Invalid reply text")
            return Outcome.Parsed(Query(Action.REPLY, contact = target, replyText = text))
        }

        val mark = Regex("^mark\\s+(all|calls?|texts?|messages?)\\s+unread[?.!]*$", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (mark != null) {
            val what = mark.groupValues[1].lowercase()
            val cat = when (what) { "call", "calls" -> Category.CALLS; "text", "texts", "message", "messages" -> Category.MESSAGES_SMS; else -> null }
            return Outcome.Parsed(Query(Action.MARK_UNREAD, category = cat, unreadOnly = true))
        }

        val destructive = Regex("^(delete|clear)\\s+(?:all\\s+)?(.+?)[?.!]*$", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (destructive != null) {
            val intent = if (destructive.groupValues[1].equals("clear", true)) Action.CLEAR else Action.DELETE
            val tail = destructive.groupValues[2].trim()
            val timeframe = parseTimeframe(tail)
            val withoutTime = timeframe.first.replace(Regex("\\b(saved|notification|notifications|from)\\b", RegexOption.IGNORE_CASE), " ").trim()
            val category = detectCategory(withoutTime)
            val standalone = withoutTime.isBlank()
            if (category == null && !standalone) return Outcome.Rejected("A notification category is required")
            return Outcome.Parsed(Query(intent, category = category, todayOnly = timeframe.second.today,
                yesterdayOnly = timeframe.second.yesterday, lastMinutes = timeframe.second.minutes,
                lastHours = timeframe.second.hours, lastDays = timeframe.second.days, olderThanDays = timeframe.second.older))
        }

        val startsContactQuery = Regex("^what\\s+did\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val mentionsSend = Regex("\\bsend\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (startsContactQuery && mentionsSend) {
            val contactMatch = Regex("^\\s*what\\s+did\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} .'_-]{0,47}?)\\s+send(?:\\s+me)?(?:\\s+notifications?)?\\s*[?!.,;:]*\\s*$", RegexOption.IGNORE_CASE).matchEntire(raw)
                ?: return Outcome.Rejected("Invalid contact query")
            val contact = boundedValue(contactMatch.groupValues.getOrNull(1).orEmpty()) ?: return Outcome.Rejected("Invalid contact")
            return Outcome.Parsed(Query(Action.WHAT_IN, contact = contact))
        }

        val appMatch = Regex("\\bfrom\\s+([\\p{L}\\p{N}](?:[\\p{L}\\p{N}._-]{0,46}[\\p{L}\\p{N}])?)(?=$|[\\s?!.,;:])", RegexOption.IGNORE_CASE).find(raw)
        val fromApp = appMatch?.groupValues?.getOrNull(1)?.takeIf { it.length in 1..48 }?.takeUnless { it.equals("today", true) }
        if (Regex("\\bfrom\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) && appMatch == null) return Outcome.Rejected("Invalid app filter")
        val withoutApp = if (appMatch != null && fromApp != null) raw.removeRange(appMatch.range) else raw
        val normalized = withoutApp.lowercase().replace("what's", "what is").replace(Regex("\\bwhats\\b"), "what is")
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim()
        if (normalized.isEmpty()) return Outcome.Rejected("Empty query")
        val tokens = normalized.split(Regex("\\s+").toRegex()).filter(String::isNotBlank)
        val categoryScores = mutableMapOf<Category, Int>()
        val exactCategoryCandidates = linkedSetOf<Category>()
        val actionScores = mutableMapOf<Action, Int>()
        var unreadOnly = false
        var todayOnly = false
        for (token in tokens) {
            if (token in modifiers) {
                if (token in setOf("new", "unread", "latest", "unseen", "newest")) unreadOnly = true
                if (token == "today") todayOnly = true
                continue
            }
            if (token in ignoredWords) { if (token in setOf("latest", "newest")) unreadOnly = true; continue }
            val exactCategoryMatches = categoryWords.filterValues { token in it }.keys
            if (exactCategoryMatches.isNotEmpty()) { exactCategoryCandidates.addAll(exactCategoryMatches); continue }
            val exactActionMatches = actionWords.filterValues { token in it }.keys
            if (exactActionMatches.isNotEmpty()) { exactActionMatches.forEach { actionScores[it] = (actionScores[it] ?: 0) + 1 }; continue }
            val modifier = nearestSignal(token, modifiers)
            if (modifier != null) { if (modifier in setOf("new", "unread", "latest", "unseen", "newest")) unreadOnly = true; if (modifier == "today") todayOnly = true; continue }
            val categoryMatches = nearestCategories(token)
            val actionMatches = nearestActions(token)
            val categoryDistance = bestCategoryDistance(token)
            val actionDistance = bestActionDistance(token)
            if (actionMatches.isNotEmpty() && (categoryMatches.isEmpty() || actionDistance!! <= (categoryDistance ?: Int.MAX_VALUE))) {
                actionMatches.forEach { actionScores[it] = (actionScores[it] ?: 0) + 1 }; continue
            }
            if (categoryMatches.isNotEmpty()) { categoryMatches.forEach { categoryScores[it] = (categoryScores[it] ?: 0) + 1 }; continue }
        }
        val foundCategories = if (exactCategoryCandidates.isNotEmpty()) exactCategoryCandidates else categoryScores.keys
        if (foundCategories.size > 1) return Outcome.Clarify(foundCategories.map { it.name }.toSet())
        val actionCandidates = actionScores.keys
        val explicitActions = actionCandidates - setOf(Action.WHAT_IN, Action.ANY)
        if (explicitActions.size > 1) return Outcome.Clarify(explicitActions.map { it.name }.toSet())
        val hasQuestion = "what" in tokens
        val hasIn = "in" in tokens
        val action = explicitActions.singleOrNull() ?: if (Action.ANY in actionCandidates) Action.ANY else if (hasQuestion && hasIn) Action.WHAT_IN else Action.SHOW
        val category = foundCategories.singleOrNull()
        val explicitStandalone = Regex("\\bnotifications?\\b").containsMatchIn(normalized)
        if (category == null && !explicitStandalone) return Outcome.Rejected("A notification category is required")
        return Outcome.Parsed(Query(action, category, unreadOnly, todayOnly, fromApp))
    }

    private data class TimeParts(val today: Boolean = false, val yesterday: Boolean = false, val minutes: Int? = null, val hours: Int? = null, val days: Int? = null, val older: Int? = null)
    private fun parseTimeframe(text: String): Pair<String, TimeParts> {
        val s = text.trim().lowercase()
        if (Regex("\\btoday\\b").containsMatchIn(s)) return Pair(s.replace(Regex("\\btoday\\b"), "").trim(), TimeParts(today = true))
        if (Regex("\\byesterday\\b").containsMatchIn(s)) return Pair(s.replace(Regex("\\byesterday\\b"), "").trim(), TimeParts(yesterday = true))
        val older = Regex("\\bolder\\s+than\\s+(\\d{1,4})\\s+days?\\b").find(s)
        if (older != null) return Pair(s.replace(older.value, "").trim(), TimeParts(older = older.groupValues[1].toIntOrNull()))
        val last = Regex("\\blast\\s+(\\d{1,4})\\s+(minutes?|mins?|hours?|hrs?|days?)\\b").find(s)
        if (last != null) {
            val n = last.groupValues[1].toIntOrNull()
            val unit = last.groupValues[2]
            val parts = when { unit.startsWith("min") -> TimeParts(minutes = n); unit.startsWith("h") -> TimeParts(hours = n); else -> TimeParts(days = n) }
            return Pair(s.replace(last.value, "").trim(), parts)
        }
        return Pair(s, TimeParts())
    }
    private fun detectCategory(text: String): Category? {
        val tokens = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val found = tokens.flatMap { token -> categoryWords.filterValues { token in it }.keys }.toSet()
        return found.singleOrNull()
    }
    private fun nearestCategories(token: String): Set<Category> {
        val exact = categoryWords.filterValues { token in it }.keys
        if (exact.isNotEmpty()) return exact
        if (token.length < 3) return emptySet()
        val distances = categoryWords.flatMap { (category, words) -> words.map { category to distance(token, it, 2) } }.filter { it.second <= 2 }
        if (distances.isEmpty()) return emptySet()
        val best = distances.minOf { it.second }
        return distances.filter { it.second == best }.map { it.first }.toSet()
    }
    private fun bestCategoryDistance(token: String): Int? = categoryWords.values.asSequence().flatten().map { distance(token, it, 2) }.minOrNull()?.takeIf { it <= 2 }
    private fun bestActionDistance(token: String): Int? = actionWords.values.asSequence().flatten().map { distance(token, it, 2) }.minOrNull()?.takeIf { it <= 2 }
    private fun nearestActions(token: String): Set<Action> {
        val exact = actionWords.filterValues { token in it }.keys
        if (exact.isNotEmpty()) return exact
        if (token.length < 3) return emptySet()
        val distances = actionWords.flatMap { (action, words) -> words.map { action to distance(token, it, 2) } }.filter { it.second <= 2 }
        if (distances.isEmpty()) return emptySet()
        val best = distances.minOf { it.second }
        return distances.filter { it.second == best }.map { it.first }.toSet()
    }
    private fun nearestSignal(token: String, signals: Set<String>): String? {
        if (token in signals) return token
        if (token.length < 3) return null
        return signals.map { it to distance(token, it, 2) }.filter { it.second <= 2 }.minByOrNull { it.second }?.first
    }
    private fun boundedValue(value: String): String? {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        return cleaned.takeIf { it.length in 1..48 && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == '.' || c == '\'' } }
    }
    private fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val curr = IntArray(b.length + 1); curr[0] = i + 1; var rowMin = curr[0]
            for (j in b.indices) { curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1); rowMin = minOf(rowMin, curr[j + 1]) }
            if (rowMin > limit) return limit + 1
            prev = curr
        }
        return prev[b.length]
    }
}

/** Shared phrase set used by host regression tests and the on-device self-diagnostic. */
object NotificationQueryRegressionCases {
    val positivePhrases: List<String> = listOf(
        "show text notification", "read sms notifications", "tell me about message notification", "list messages", "what in chat notification", "show whatsapp notifications", "read telegram notification", "list chat", "show call notification", "read calls", "tell missed notification", "list calls notifications", "show system notification", "read android notifications", "tell phone notification", "list system", "show music notification", "read media notifications", "tell youtube notification", "list music", "show social notification", "read social notifications", "tell social", "list social notification", "show email notification", "read email notifications", "tell email", "list email notification", "show weather notification", "read weather notifications", "tell weather", "list weather notification", "show shopping notification", "read shopping notifications", "tell shopping", "list shopping notification", "show food notification", "read food notifications", "tell food", "list food notification", "show games notification", "read games notifications", "tell games", "list games notification", "show finance notification", "read finance notifications", "tell finance", "list finance notification", "open text notification", "any new calls notification", "show unread email today", "list music from youtube",
        "latest text", "latest unseen messages", "latest calls", "latest email", "latest chat", "latest system alerts", "latest music alerts", "latest social posts", "latest weather alert", "latest shopping orders", "latest food alerts", "latest game alerts", "latest bank alerts", "latest WhatsApp messages", "latest Gmail alerts", "latest YouTube alerts", "latest Instagram alerts", "latest Android alerts", "latest missed calls", "latest SMS alerts",
        "mark all unread", "mark calls unread", "mark text unread", "mark messages unread", "delete notifications today", "clear notifications yesterday", "delete calls today", "clear text messages yesterday", "delete email last 5 minutes", "clear chat last 2 hours", "delete calls last 3 days", "clear messages last 7 days", "delete notifications older than 30 days", "clear alerts older than 14 days",
        "delete messages today", "clear calls yesterday", "delete WhatsApp last 4 hours", "clear SMS last 10 minutes", "delete Gmail last 2 days", "clear social last 6 days", "delete weather yesterday", "clear music today", "delete shopping last 8 hours", "clear food last 9 days", "delete game alerts older than 90 days", "clear bank notifications older than 60 days",
        "reply to Alex: On my way", "reply to WhatsApp: Thanks", "reply to Sam Lee: I will call you", "reply to Gmail: Received", "reply to Mom: See you soon", "reply to Telegram: Yes, sounds good", "reply to Jordan: Not today", "reply to Signal: Please send details", "reply to Priya: Thank you!", "reply to Work Chat: I am available",
        "delete Telegram messages today", "clear WhatsApp notifications yesterday", "delete voicemail last 11 minutes", "clear Outlook mail older than 21 days"
    )

    data class NaturalSentence(val text: String, val category: NotificationQueryParser.Category, val action: NotificationQueryParser.Action = NotificationQueryParser.Action.SHOW, val unreadOnly: Boolean = false, val todayOnly: Boolean = false)
    val naturalSentences = listOf(
        NaturalSentence("Could you show me my latest text messages please?", NotificationQueryParser.Category.MESSAGES_SMS), NaturalSentence("Did I receive any new SMS today?", NotificationQueryParser.Category.MESSAGES_SMS, NotificationQueryParser.Action.ANY, true, true), NaturalSentence("Tell me what's in the message notifications", NotificationQueryParser.Category.MESSAGES_SMS, NotificationQueryParser.Action.TELL), NaturalSentence("Please list the text alerts from today", NotificationQueryParser.Category.MESSAGES_SMS, NotificationQueryParser.Action.LIST, todayOnly = true), NaturalSentence("Check whether there are unread SMS messages", NotificationQueryParser.Category.MESSAGES_SMS, unreadOnly = true),
        NaturalSentence("Can you show my WhatsApp chat alerts?", NotificationQueryParser.Category.CHAT), NaturalSentence("Read the newest Telegram conversations for me", NotificationQueryParser.Category.CHAT, NotificationQueryParser.Action.READ), NaturalSentence("I got new WhatsApp chats, could you find them?", NotificationQueryParser.Category.CHAT, unreadOnly = true), NaturalSentence("List my unread chats today", NotificationQueryParser.Category.CHAT, NotificationQueryParser.Action.LIST, true, true), NaturalSentence("Could you tell me about the latest Telegram notifications?", NotificationQueryParser.Category.CHAT, NotificationQueryParser.Action.TELL),
        NaturalSentence("Show all missed calls from earlier today", NotificationQueryParser.Category.CALLS, todayOnly = true), NaturalSentence("Tell me who rang with missed call alerts", NotificationQueryParser.Category.CALLS, NotificationQueryParser.Action.TELL), NaturalSentence("List unread call notifications, please", NotificationQueryParser.Category.CALLS, NotificationQueryParser.Action.LIST, unreadOnly = true), NaturalSentence("Can you find the most recent calls?", NotificationQueryParser.Category.CALLS), NaturalSentence("Show system update notifications from today", NotificationQueryParser.Category.SYSTEM, todayOnly = true),
        NaturalSentence("Tell me if Android has any unread device alerts", NotificationQueryParser.Category.SYSTEM, NotificationQueryParser.Action.TELL, unreadOnly = true), NaturalSentence("List my latest phone alerts", NotificationQueryParser.Category.SYSTEM, NotificationQueryParser.Action.LIST), NaturalSentence("Can you find recent Android notifications?", NotificationQueryParser.Category.SYSTEM), NaturalSentence("Show me my latest music alerts", NotificationQueryParser.Category.MUSIC_AND_MEDIA), NaturalSentence("Read the newest YouTube notifications", NotificationQueryParser.Category.MUSIC_AND_MEDIA, NotificationQueryParser.Action.READ),
        NaturalSentence("List audio updates from today", NotificationQueryParser.Category.MUSIC_AND_MEDIA, NotificationQueryParser.Action.LIST, todayOnly = true), NaturalSentence("Tell me which media apps sent alerts", NotificationQueryParser.Category.MUSIC_AND_MEDIA, NotificationQueryParser.Action.TELL), NaturalSentence("Show my latest Instagram notifications", NotificationQueryParser.Category.SOCIAL), NaturalSentence("Tell me if I have unread Facebook updates", NotificationQueryParser.Category.SOCIAL, NotificationQueryParser.Action.TELL, unreadOnly = true), NaturalSentence("List new social alerts today", NotificationQueryParser.Category.SOCIAL, NotificationQueryParser.Action.LIST, true, true),
        NaturalSentence("Could you show me the recent posts notifications?", NotificationQueryParser.Category.SOCIAL), NaturalSentence("Show the newest email alerts", NotificationQueryParser.Category.EMAIL), NaturalSentence("Read unread Gmail notifications today", NotificationQueryParser.Category.EMAIL, NotificationQueryParser.Action.READ, true, true), NaturalSentence("Tell me about my latest inbox updates", NotificationQueryParser.Category.EMAIL, NotificationQueryParser.Action.TELL), NaturalSentence("List mail notifications please", NotificationQueryParser.Category.EMAIL, NotificationQueryParser.Action.LIST), NaturalSentence("Show my local weather alerts", NotificationQueryParser.Category.WEATHER),
        NaturalSentence("Tell me the forecast notifications from today", NotificationQueryParser.Category.WEATHER, NotificationQueryParser.Action.TELL, todayOnly = true), NaturalSentence("List unread weather updates", NotificationQueryParser.Category.WEATHER, NotificationQueryParser.Action.LIST, unreadOnly = true), NaturalSentence("Could you find the latest weather notifications?", NotificationQueryParser.Category.WEATHER), NaturalSentence("Show my order notifications", NotificationQueryParser.Category.SHOPPING), NaturalSentence("Tell me about the latest shopping alerts", NotificationQueryParser.Category.SHOPPING, NotificationQueryParser.Action.TELL),
        NaturalSentence("List new delivery notices today", NotificationQueryParser.Category.SHOPPING, NotificationQueryParser.Action.LIST, true, true), NaturalSentence("Could you find notifications from the shop app?", NotificationQueryParser.Category.SHOPPING), NaturalSentence("Show my latest food alerts", NotificationQueryParser.Category.FOOD), NaturalSentence("Tell me about restaurant updates", NotificationQueryParser.Category.FOOD, NotificationQueryParser.Action.TELL), NaturalSentence("List Swiggy notifications from today", NotificationQueryParser.Category.FOOD, NotificationQueryParser.Action.LIST, todayOnly = true),
        NaturalSentence("Could you find new Zomato alerts please?", NotificationQueryParser.Category.FOOD, unreadOnly = true), NaturalSentence("Show my latest game notifications", NotificationQueryParser.Category.GAMES), NaturalSentence("Tell me about unread gaming alerts", NotificationQueryParser.Category.GAMES, NotificationQueryParser.Action.TELL, unreadOnly = true), NaturalSentence("List new games notices today", NotificationQueryParser.Category.GAMES, NotificationQueryParser.Action.LIST, true, true), NaturalSentence("Can you find notifications from my game app?", NotificationQueryParser.Category.GAMES),
        NaturalSentence("Show my latest bank alerts", NotificationQueryParser.Category.FINANCE), NaturalSentence("Tell me whether I have unread payment notifications", NotificationQueryParser.Category.FINANCE, unreadOnly = true), NaturalSentence("List new finance updates from today", NotificationQueryParser.Category.FINANCE, NotificationQueryParser.Action.LIST, true, true), NaturalSentence("Could you find transaction alerts?", NotificationQueryParser.Category.FINANCE)
    )
}
