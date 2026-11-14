package com.cue.offlinebrain.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

data class TypedMacroStepRecord(val id: Long, val step: TypedMacroStep)

/** A typed representation for macro actions. Text fields are bounded to [TypedMacroSteps.MAX_PAYLOAD_LENGTH]. */
sealed class TypedMacroStep {
    data class OpenApp(val app: String) : TypedMacroStep()
    data class TapText(val text: String) : TypedMacroStep()
    data class TypeText(val text: String) : TypedMacroStep()
    data class Wait(val seconds: Int) : TypedMacroStep()
    object Scroll : TypedMacroStep()
    object Back : TypedMacroStep()
    object Home : TypedMacroStep()
    object Recents : TypedMacroStep()
    data class SpeakText(val text: String) : TypedMacroStep()
    data class RunChatCommand(val command: String) : TypedMacroStep()
    object DeviceStatus : TypedMacroStep()
    data class RunAnotherMacro(val macroId: Long) : TypedMacroStep()
}

/** Versioned, length-prefixed UTF-8 codec. No Android or JSON dependencies. */
object TypedMacroSteps {
    const val MAX_STEPS = 50
    const val MAX_PAYLOAD_LENGTH = 500
    const val MIN_WAIT_SECONDS = 0
    const val MAX_WAIT_SECONDS = 300
    private const val PREFIX = "OBMS:"
    private const val HEADER_V1 = "OBMS:1:"
    private const val HEADER_V2 = "OBMS:2:"

    // Stable on-disk discriminators; never reorder or reuse these values.
    private const val OPEN_APP = 1
    private const val TAP_TEXT = 2
    private const val TYPE_TEXT = 3
    private const val WAIT = 4
    private const val SCROLL = 5
    private const val BACK = 6
    private const val HOME = 7
    private const val RECENTS = 8
    private const val SPEAK_TEXT = 9
    private const val RUN_CHAT_COMMAND = 10
    private const val DEVICE_STATUS = 11
    private const val RUN_ANOTHER_MACRO = 12

    /** Convenience writer for callers without editor IDs. */
    fun encode(steps: List<TypedMacroStep>): String = encodeRecords(steps.mapIndexed { index, step -> TypedMacroStepRecord(index + 1L, step) })

    /** Persists editor step IDs so reorder/duplicate/edit operations have durable identities. */
    fun encodeRecords(records: List<TypedMacroStepRecord>): String {
        require(records.size <= MAX_STEPS) { "Too many macro steps: ${records.size}" }
        require(records.all { it.id > 0L } && records.map { it.id }.distinct().size == records.size) { "Macro step IDs must be unique and positive" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(records.size)
            records.forEach { record ->
                out.writeLong(record.id)
                writeStep(out, record.step)
            }
        }
        return HEADER_V2 + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    /** Decodes typed values or legacy newline-separated commands. */
    fun decode(raw: String): List<TypedMacroStep> = decodeRecords(raw).map { it.step }

    /** Older typed v1 records and legacy lines receive deterministic IDs until the next save. */
    fun decodeRecords(raw: String): List<TypedMacroStepRecord> {
        if (!raw.startsWith(PREFIX)) return decodeLegacy(raw)
        val versionHeader = when {
            raw.startsWith(HEADER_V2) -> HEADER_V2
            raw.startsWith(HEADER_V1) -> HEADER_V1
            else -> throw IllegalArgumentException("Unsupported or malformed typed macro version")
        }
        val encoded = raw.substring(versionHeader.length)
        val bytes = try { Base64.getUrlDecoder().decode(encoded) }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("Malformed typed macro payload", e) }
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val count = input.readInt()
                require(count in 0..MAX_STEPS) { "Typed macro step count out of range" }
                val result = ArrayList<TypedMacroStepRecord>(count)
                val ids = HashSet<Long>()
                repeat(count) { index ->
                    val id = if (versionHeader == HEADER_V2) input.readLong() else index + 1L
                    require(id > 0L && ids.add(id)) { "Typed macro step IDs must be unique and positive" }
                    result += TypedMacroStepRecord(id, readStep(input))
                }
                require(input.available() == 0) { "Unexpected trailing typed macro data" }
                return result
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed typed macro payload", e)
        }
    }

    private fun decodeLegacy(raw: String): List<TypedMacroStepRecord> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(MAX_STEPS)
        .mapIndexed { index, line -> TypedMacroStepRecord(index + 1L, TypedMacroStep.RunChatCommand(line.take(MAX_PAYLOAD_LENGTH))) }
        .toList()

    private fun writeStep(out: DataOutputStream, step: TypedMacroStep) {
        when (step) {
            is TypedMacroStep.OpenApp -> { out.writeByte(OPEN_APP); writeText(out, step.app) }
            is TypedMacroStep.TapText -> { out.writeByte(TAP_TEXT); writeText(out, step.text) }
            is TypedMacroStep.TypeText -> { out.writeByte(TYPE_TEXT); writeText(out, step.text) }
            is TypedMacroStep.Wait -> {
                require(step.seconds in MIN_WAIT_SECONDS..MAX_WAIT_SECONDS) { "Wait seconds out of range" }
                out.writeByte(WAIT); out.writeInt(step.seconds)
            }
            TypedMacroStep.Scroll -> out.writeByte(SCROLL)
            TypedMacroStep.Back -> out.writeByte(BACK)
            TypedMacroStep.Home -> out.writeByte(HOME)
            TypedMacroStep.Recents -> out.writeByte(RECENTS)
            is TypedMacroStep.SpeakText -> { out.writeByte(SPEAK_TEXT); writeText(out, step.text) }
            is TypedMacroStep.RunChatCommand -> { out.writeByte(RUN_CHAT_COMMAND); writeText(out, step.command) }
            TypedMacroStep.DeviceStatus -> out.writeByte(DEVICE_STATUS)
            is TypedMacroStep.RunAnotherMacro -> {
                require(step.macroId > 0L) { "Nested macro ID must be positive" }
                out.writeByte(RUN_ANOTHER_MACRO); out.writeLong(step.macroId)
            }
        }
    }

    private fun readStep(input: DataInputStream): TypedMacroStep = when (val discriminator = input.readUnsignedByte()) {
        OPEN_APP -> TypedMacroStep.OpenApp(readText(input))
        TAP_TEXT -> TypedMacroStep.TapText(readText(input))
        TYPE_TEXT -> TypedMacroStep.TypeText(readText(input))
        WAIT -> input.readInt().let { require(it in MIN_WAIT_SECONDS..MAX_WAIT_SECONDS) { "Wait seconds out of range" }; TypedMacroStep.Wait(it) }
        SCROLL -> TypedMacroStep.Scroll
        BACK -> TypedMacroStep.Back
        HOME -> TypedMacroStep.Home
        RECENTS -> TypedMacroStep.Recents
        SPEAK_TEXT -> TypedMacroStep.SpeakText(readText(input))
        RUN_CHAT_COMMAND -> TypedMacroStep.RunChatCommand(readText(input))
        DEVICE_STATUS -> TypedMacroStep.DeviceStatus
        RUN_ANOTHER_MACRO -> input.readLong().let { require(it > 0L) { "Nested macro ID must be positive" }; TypedMacroStep.RunAnotherMacro(it) }
        else -> throw IllegalArgumentException("Unknown macro-step discriminator: $discriminator")
    }

    private fun writeText(out: DataOutputStream, value: String) {
        require(value.length <= MAX_PAYLOAD_LENGTH) { "Text payload exceeds $MAX_PAYLOAD_LENGTH characters" }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..(MAX_PAYLOAD_LENGTH * 4)) { "Text payload byte length out of range" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val value = String(bytes, StandardCharsets.UTF_8)
        require(value.length <= MAX_PAYLOAD_LENGTH) { "Text payload exceeds $MAX_PAYLOAD_LENGTH characters" }
        require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "Malformed UTF-8 text" }
        return value
    }
}
