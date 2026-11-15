package com.cue.offlinebrain.engine

import java.util.Locale
import kotlin.math.abs

/** Only launcher apps already installed on the device are eligible for app-opening commands. */
data class InstalledCommandApp(val label: String, val packageName: String)

enum class CommandActionKind {
    OPEN_APP, WEB_SEARCH, IN_APP_SEARCH, TYPE_TEXT, TAP_TEXT, SCROLL, BACK, HOME,
    SETTINGS_PAGE, DIAL, PLAY, SEND
}

data class CommandAction(
    val kind: CommandActionKind,
    val description: String,
    val value: String = "",
    val packageName: String = "",
    val appLabel: String = "",
    val imageSearch: Boolean = false
)

data class CommandPlan(val actions: List<CommandAction>) {
    fun plainText(): String = actions.mapIndexed { index, action -> "${index + 1}. ${action.description}" }.joinToString("\n")
}

data class CommandParseResult(val recognized: Boolean, val plan: CommandPlan? = null, val error: String? = null)

/** Rule-based command parser. It does not launch apps or touch the network. */
object CompoundCommandParser {
    private val splitBeforeVerb = Regex(
        "(?i)\\s*(?:,\\s*|\\b(?:and\\s+then|after\\s+that|then|also)\\b\\s*|\\band\\b(?=\\s+(?:open|launch|start|search|find|look\\s+for|type|write|tap|click|press|scroll|go\\s+back|go\\s+home|go\\s+to|call|play|send)\\b))\\s*"
    )
    private val verbPattern = Regex("(?i)^(?:open|launch|start|search|find|look\\s+for|type|write|tap|click|press|scroll|go\\s+back|go\\s+home|go\\s+to|back|home|call|play|send)\\b")

    fun parse(text: String, installedApps: List<InstalledCommandApp>): CommandParseResult {
        val cleaned = text.trim().removePrefix("please ").trim()
        if (cleaned.isBlank()) return CommandParseResult(false)
        val implicitSearch = Regex("(?i)^(open|launch|start)\\s+(.+?)\\s+(search|find|look\\s+for)\\s+(.+)$").matchEntire(cleaned)
        val normalized = if (implicitSearch != null) {
            "${implicitSearch.groupValues[1]} ${implicitSearch.groupValues[2]} and ${implicitSearch.groupValues[3]} ${implicitSearch.groupValues[4]}"
        } else cleaned
        val segments = splitBeforeVerb.split(normalized).map { it.trim().trimEnd('.', '?', '!') }.filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.none { verbPattern.containsMatchIn(it) }) return CommandParseResult(false)

        val actions = mutableListOf<CommandAction>()
        var previousApp: InstalledCommandApp? = null
        for (segment in segments) {
            val lower = segment.lowercase(Locale.ROOT).trim()
            val action = when {
                lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") -> {
                    val name = segment.dropWhile { !it.isWhitespace() }.trim()
                    if (name.isBlank()) return CommandParseResult(true, error = "Say which installed app to open.")
                    val app = resolveApp(name, installedApps)
                        ?: return CommandParseResult(true, error = "I couldn't find an installed app matching ‘${name.take(60)}’. Check the app name and try again.")
                    previousApp = app
                    CommandAction(CommandActionKind.OPEN_APP, "Open ${app.label}", packageName = app.packageName, appLabel = app.label)
                }
                lower.startsWith("search ") || lower.startsWith("find ") || lower.startsWith("look for ") -> {
                    val rawQuery = when {
                        lower.startsWith("look for ") -> segment.drop(9).trim()
                        else -> segment.dropWhile { !it.isWhitespace() }.trim()
                    }
                    val imageSearch = Regex("(?i)\\s+images?$\\s*").containsMatchIn(rawQuery)
                    val query = if (imageSearch) rawQuery.replace(Regex("(?i)\\s+images?$\\s*"), "").trim() else rawQuery
                    if (query.isBlank()) return CommandParseResult(true, error = "Say what you want me to search for.")
                    val prior = previousApp
                    val browser = prior?.let(::isBrowser) == true
                    when {
                        prior != null && !browser -> CommandAction(CommandActionKind.IN_APP_SEARCH, "Search for ${query.take(70)} in ${prior.label}", query, prior.packageName, prior.label)
                        else -> CommandAction(CommandActionKind.WEB_SEARCH, "Search Google for ${query.take(70)}${if (prior != null) " in ${prior.label}" else ""}", query, prior?.packageName.orEmpty(), prior?.label.orEmpty(), imageSearch)
                    }
                }
                lower.startsWith("type ") || lower.startsWith("write ") -> {
                    val value = segment.dropWhile { !it.isWhitespace() }.trim()
                    if (value.isBlank()) return CommandParseResult(true, error = "Say what text to type.")
                    CommandAction(CommandActionKind.TYPE_TEXT, "Type ${value.take(70)}", value)
                }
                lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ") -> {
                    val value = segment.dropWhile { !it.isWhitespace() }.trim()
                    if (value.isBlank()) return CommandParseResult(true, error = "Say which visible button or text to tap.")
                    CommandAction(CommandActionKind.TAP_TEXT, "Tap ${value.take(70)}", value)
                }
                lower == "scroll up" || lower == "scroll down" -> CommandAction(CommandActionKind.SCROLL, segment.replaceFirstChar { it.uppercase() }, lower)
                lower == "back" || lower == "go back" -> CommandAction(CommandActionKind.BACK, "Go back")
                lower == "home" || lower == "go home" -> CommandAction(CommandActionKind.HOME, "Go to the home screen")
                lower.startsWith("go to ") -> {
                    val page = segment.drop(6).trim()
                    val key = when {
                        "wifi" in page.lowercase(Locale.ROOT) || "wi-fi" in page.lowercase(Locale.ROOT) -> "wifi"
                        "bluetooth" in page.lowercase(Locale.ROOT) -> "bluetooth"
                        "brightness" in page.lowercase(Locale.ROOT) || "display" in page.lowercase(Locale.ROOT) -> "display"
                        else -> return CommandParseResult(true, error = "I can open Wi‑Fi, Bluetooth, or display settings. I can't open ‘${page.take(50)}’ directly yet.")
                    }
                    CommandAction(CommandActionKind.SETTINGS_PAGE, "Open ${key.replaceFirstChar { it.uppercase() }} settings", key)
                }
                lower.startsWith("call ") -> CommandAction(CommandActionKind.DIAL, "Open the phone dialer for ${segment.drop(5).trim().take(40)}", segment.drop(5).trim())
                lower.startsWith("play ") -> CommandAction(CommandActionKind.PLAY, "Play ${segment.drop(5).trim().take(60)}", segment.drop(5).trim())
                lower.startsWith("send ") -> CommandAction(CommandActionKind.SEND, "Prepare to send ${segment.drop(5).trim().take(60)}", segment.drop(5).trim())
                else -> return CommandParseResult(true, error = "I couldn't understand the step ‘${segment.take(70)}’. Try open, search, type, tap, scroll, back, or home.")
            }
            actions += action
        }
        return if (actions.isEmpty()) CommandParseResult(false) else CommandParseResult(true, CommandPlan(actions))
    }

    private fun resolveApp(value: String, apps: List<InstalledCommandApp>): InstalledCommandApp? {
        val name = value.trim().lowercase(Locale.ROOT)
        val direct = apps.filter { app ->
            val label = app.label.trim().lowercase(Locale.ROOT)
            label.isNotBlank() && (name == label || name.startsWith("$label "))
        }.maxByOrNull { it.label.length }
        if (direct != null) return direct
        val alias = name.takeWhile { !it.isWhitespace() }.removeSuffix(".")
        val needles = when (alias) {
            "chrome" -> listOf("chrome")
            "browser" -> listOf("chrome", "browser", "internet", "firefox", "edge")
            "whatsapp" -> listOf("whatsapp")
            "youtube" -> listOf("youtube")
            "gallery" -> listOf("gallery", "photos", "pictures")
            "camera" -> listOf("camera")
            "settings" -> listOf("settings")
            else -> emptyList()
        }
        val aliasMatches = apps.filter { app -> needles.any { needle -> app.label.contains(needle, true) } }
        return aliasMatches.maxByOrNull { app -> app.label.length }
            ?: apps.filter { app -> withinOneEdit(name, app.label.trim().lowercase(Locale.ROOT)) }.maxByOrNull { it.label.length }
    }

    private fun withinOneEdit(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank() || abs(left.length - right.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < left.length && j < right.length) {
            if (left.getOrNull(i) == right.getOrNull(j)) { i++; j++; continue }
            edits++
            if (edits > 1) return false
            when {
                left.length > right.length -> i++
                right.length > left.length -> j++
                else -> { i++; j++ }
            }
        }
        if (i < left.length || j < right.length) edits++
        return edits <= 1
    }

    private fun isBrowser(app: InstalledCommandApp): Boolean {
        val value = "${app.label} ${app.packageName}".lowercase(Locale.ROOT)
        return listOf("chrome", "browser", "firefox", "edge", "opera", "brave", "internet").any(value::contains)
    }
}
