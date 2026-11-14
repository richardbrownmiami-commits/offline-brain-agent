package com.cue.offlinebrain.data

import java.util.concurrent.atomic.AtomicLong

data class MacroStepDraft(val id: Long, val step: TypedMacroStep)

enum class MacroStepKind(val label: String, val fieldLabel: String, val placeholder: String) {
    OPEN_APP("Open an app", "App name", "Chrome"),
    TAP_TEXT("Tap a label", "Button or label to tap", "Search"),
    TYPE_TEXT("Type text", "Text to type", "A harmless example"),
    WAIT("Wait", "Seconds (0–300)", "2"),
    SCROLL("Scroll down", "No extra details", ""),
    BACK("Go back", "No extra details", ""),
    HOME("Go to Home", "No extra details", ""),
    RECENTS("Open Recents", "No extra details", ""),
    SPEAK_TEXT("Speak text", "Text to say aloud", "Your reminder is ready"),
    RUN_CHAT_COMMAND("Run a Chat request", "Request", "search cats"),
    DEVICE_STATUS("Read device status", "No extra details", ""),
    RUN_ANOTHER_MACRO("Run another macro", "Choose a saved macro", "");

    val needsText: Boolean get() = this in setOf(OPEN_APP, TAP_TEXT, TYPE_TEXT, SPEAK_TEXT, RUN_CHAT_COMMAND)
    val needsMacro: Boolean get() = this == RUN_ANOTHER_MACRO
    val needsValue: Boolean get() = needsText || this == WAIT

    fun build(value: String, nestedMacroId: Long? = null): TypedMacroStep = when (this) {
        OPEN_APP -> TypedMacroStep.OpenApp(value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH))
        TAP_TEXT -> TypedMacroStep.TapText(value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH))
        TYPE_TEXT -> TypedMacroStep.TypeText(value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH))
        WAIT -> TypedMacroStep.Wait(value.trim().toIntOrNull()?.coerceIn(0, TypedMacroSteps.MAX_WAIT_SECONDS) ?: 0)
        SCROLL -> TypedMacroStep.Scroll
        BACK -> TypedMacroStep.Back
        HOME -> TypedMacroStep.Home
        RECENTS -> TypedMacroStep.Recents
        SPEAK_TEXT -> TypedMacroStep.SpeakText(value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH))
        RUN_CHAT_COMMAND -> TypedMacroStep.RunChatCommand(value.take(TypedMacroSteps.MAX_PAYLOAD_LENGTH))
        DEVICE_STATUS -> TypedMacroStep.DeviceStatus
        RUN_ANOTHER_MACRO -> TypedMacroStep.RunAnotherMacro(nestedMacroId ?: -1L)
    }

    companion object {
        fun forStep(step: TypedMacroStep): MacroStepKind = when (step) {
            is TypedMacroStep.OpenApp -> OPEN_APP
            is TypedMacroStep.TapText -> TAP_TEXT
            is TypedMacroStep.TypeText -> TYPE_TEXT
            is TypedMacroStep.Wait -> WAIT
            TypedMacroStep.Scroll -> SCROLL
            TypedMacroStep.Back -> BACK
            TypedMacroStep.Home -> HOME
            TypedMacroStep.Recents -> RECENTS
            is TypedMacroStep.SpeakText -> SPEAK_TEXT
            is TypedMacroStep.RunChatCommand -> RUN_CHAT_COMMAND
            TypedMacroStep.DeviceStatus -> DEVICE_STATUS
            is TypedMacroStep.RunAnotherMacro -> RUN_ANOTHER_MACRO
        }

        fun inputFor(step: TypedMacroStep): String = when (step) {
            is TypedMacroStep.OpenApp -> step.app
            is TypedMacroStep.TapText -> step.text
            is TypedMacroStep.TypeText -> step.text
            is TypedMacroStep.Wait -> step.seconds.toString()
            is TypedMacroStep.SpeakText -> step.text
            is TypedMacroStep.RunChatCommand -> step.command
            else -> ""
        }

        fun nestedIdFor(step: TypedMacroStep): Long? = (step as? TypedMacroStep.RunAnotherMacro)?.macroId
    }
}

/** Immutable editor operations keep the same ID through edits and moves; duplicates receive a new one. */
object MacroStepEditorModel {
    private val ids = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L) * 1_000L)

    fun newDraft(step: TypedMacroStep): MacroStepDraft = MacroStepDraft(ids.incrementAndGet(), step)

    fun drafts(raw: String): List<MacroStepDraft> = TypedMacroSteps.decodeRecords(raw).map { MacroStepDraft(it.id, it.step) }

    fun encode(drafts: List<MacroStepDraft>): String = TypedMacroSteps.encodeRecords(drafts.map { TypedMacroStepRecord(it.id, it.step) })

    fun insertAfter(rows: List<MacroStepDraft>, index: Int, draft: MacroStepDraft): List<MacroStepDraft> {
        if (rows.size >= TypedMacroSteps.MAX_STEPS) return rows
        val id = if (rows.any { it.id == draft.id }) ids.incrementAndGet() else draft.id
        val safeIndex = (index + 1).coerceIn(0, rows.size)
        return rows.toMutableList().apply { add(safeIndex, draft.copy(id = id)) }
    }

    fun duplicate(rows: List<MacroStepDraft>, index: Int): List<MacroStepDraft> {
        if (rows.size >= TypedMacroSteps.MAX_STEPS) return rows
        val original = rows.getOrNull(index) ?: return rows
        return insertAfter(rows, index, original.copy(id = ids.incrementAndGet()))
    }

    fun update(rows: List<MacroStepDraft>, index: Int, kind: MacroStepKind, value: String, nestedMacroId: Long?): List<MacroStepDraft> =
        rows.mapIndexed { i, row -> if (i == index) row.copy(step = kind.build(value, nestedMacroId)) else row }

    fun remove(rows: List<MacroStepDraft>, index: Int): List<MacroStepDraft> = rows.filterIndexed { i, _ -> i != index }

    fun move(rows: List<MacroStepDraft>, from: Int, to: Int): List<MacroStepDraft> {
        if (from !in rows.indices || to !in rows.indices || from == to) return rows
        return rows.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun validate(rows: List<MacroStepDraft>, existing: List<MacroEntity>, editedMacroId: Long?): String? {
        if (rows.isEmpty()) return "Add at least one step."
        if (rows.size > TypedMacroSteps.MAX_STEPS) return "A macro can have up to ${TypedMacroSteps.MAX_STEPS} steps."
        if (rows.any { it.id <= 0L } || rows.map { it.id }.distinct().size != rows.size) return "Every step needs its own saved ID."
        rows.forEachIndexed { index, row ->
            when (val step = row.step) {
                is TypedMacroStep.OpenApp -> if (step.app.isBlank()) return "Step ${index + 1}: enter an app name."
                is TypedMacroStep.TapText -> if (step.text.isBlank()) return "Step ${index + 1}: enter a label to tap."
                is TypedMacroStep.TypeText -> if (step.text.isBlank()) return "Step ${index + 1}: enter text to type."
                is TypedMacroStep.Wait -> if (step.seconds !in TypedMacroSteps.MIN_WAIT_SECONDS..TypedMacroSteps.MAX_WAIT_SECONDS) return "Step ${index + 1}: choose 0 to 300 seconds."
                is TypedMacroStep.SpeakText -> if (step.text.isBlank()) return "Step ${index + 1}: enter text to speak."
                is TypedMacroStep.RunChatCommand -> if (step.command.isBlank()) return "Step ${index + 1}: enter a Chat request."
                is TypedMacroStep.RunAnotherMacro -> if (existing.none { it.id == step.macroId } || step.macroId == editedMacroId) return "Step ${index + 1}: choose a different saved macro."
                TypedMacroStep.Scroll, TypedMacroStep.Back, TypedMacroStep.Home, TypedMacroStep.Recents, TypedMacroStep.DeviceStatus -> Unit
            }
        }
        val candidateId = editedMacroId ?: NEW_MACRO_NODE
        val graph = existing.associate { macro -> macro.id to runMacroIds(macro.steps) }.toMutableMap()
        graph[candidateId] = rows.mapNotNull { (it.step as? TypedMacroStep.RunAnotherMacro)?.macroId }
        fun visit(id: Long, path: Set<Long>, depth: Int): Boolean {
            if (depth > MAX_NESTED_DEPTH || id in path) return false
            val children = graph[id] ?: return false
            return children.all { child -> graph.containsKey(child) && visit(child, path + id, depth + 1) }
        }
        if (!visit(candidateId, emptySet(), 1)) return "Nested macros must exist and cannot form a loop or run more than $MAX_NESTED_DEPTH levels deep."
        return null
    }

    fun estimatedSeconds(rows: List<MacroStepDraft>): Int = rows.sumOf { draft ->
        when (val step = draft.step) {
            is TypedMacroStep.Wait -> step.seconds
            is TypedMacroStep.SpeakText -> (step.text.length / 15).coerceAtLeast(1)
            else -> 2
        }
    }

    fun describe(step: TypedMacroStep, macros: List<MacroEntity> = emptyList()): String = when (step) {
        is TypedMacroStep.OpenApp -> "Open ${step.app}"
        is TypedMacroStep.TapText -> "Tap ‘${step.text}’"
        is TypedMacroStep.TypeText -> "Type ‘${step.text}’"
        is TypedMacroStep.Wait -> "Wait ${step.seconds} second${if (step.seconds == 1) "" else "s"}"
        TypedMacroStep.Scroll -> "Scroll down"
        TypedMacroStep.Back -> "Go back"
        TypedMacroStep.Home -> "Go to Home"
        TypedMacroStep.Recents -> "Open Recents"
        is TypedMacroStep.SpeakText -> "Speak ‘${step.text}’"
        is TypedMacroStep.RunChatCommand -> "Chat request: ${step.command}"
        TypedMacroStep.DeviceStatus -> "Read device status"
        is TypedMacroStep.RunAnotherMacro -> "Run ‘${macros.firstOrNull { it.id == step.macroId }?.name ?: "missing macro"}’"
    }

    private fun runMacroIds(raw: String): List<Long> = try {
        TypedMacroSteps.decode(raw).mapNotNull { (it as? TypedMacroStep.RunAnotherMacro)?.macroId }
    } catch (_: IllegalArgumentException) { emptyList() }

    private const val NEW_MACRO_NODE = -1L
    private const val MAX_NESTED_DEPTH = 5
}
