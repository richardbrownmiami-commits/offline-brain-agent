package com.cue.offlinebrain.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

 data class MacroStepProgress(val macroName: String, val stepIndex: Int, val stepCount: Int, val nestingDepth: Int)

data class MacroExecutionResult(
    val succeeded: Boolean,
    val failedStepIndex: Int? = null,
    val stepCount: Int = 0,
    val message: String? = null
)

/** Executes only after its caller obtains explicit user consent; stop is coroutine cancellation. */
class MacroStepExecutor(
    private val findMacro: suspend (Long) -> MacroEntity?,
    private val runChatCommand: suspend (String) -> String,
    private val speakText: suspend (String) -> Boolean,
    private val onProgress: (MacroStepProgress) -> Unit = {},
    private val delayBetweenSteps: suspend () -> Unit = { delay(850L) },
    private val onError: (Exception) -> Unit = {}
) {
    private data class Failure(val rootIndex: Int, val rootCount: Int, val message: String)

    suspend fun run(macro: MacroEntity, startAtStep: Int = 0): MacroExecutionResult {
        val records = try { TypedMacroSteps.decodeRecords(macro.steps) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            onError(error)
            return MacroExecutionResult(false, 0, 1, "Saved steps could not be decoded. Reopen and review this macro.")
        }
        if (records.isEmpty()) return MacroExecutionResult(false, 0, 0, "This macro has no usable steps.")
        val first = startAtStep.coerceIn(0, records.lastIndex)
        val failure = runMacro(macro, first, emptySet(), depth = 0, rootIndex = null, rootCount = records.size)
        return if (failure == null) MacroExecutionResult(true, stepCount = records.size)
        else MacroExecutionResult(false, failure.rootIndex, failure.rootCount, failure.message.take(400))
    }

    private suspend fun runMacro(
        macro: MacroEntity,
        startAtStep: Int,
        path: Set<Long>,
        depth: Int,
        rootIndex: Int?,
        rootCount: Int
    ): Failure? {
        if (macro.id in path) return Failure(rootIndex ?: startAtStep.coerceAtLeast(0), rootCount, "Nested macro loop was stopped safely.")
        if (depth >= MAX_NESTING_DEPTH) return Failure(rootIndex ?: startAtStep.coerceAtLeast(0), rootCount, "Nested macro depth limit was reached.")
        val records = try { TypedMacroSteps.decodeRecords(macro.steps) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            onError(error)
            return Failure(rootIndex ?: startAtStep.coerceAtLeast(0), rootCount, "A saved macro contains unreadable steps.")
        }
        if (records.isEmpty()) return Failure(rootIndex ?: 0, rootCount, "A saved macro has no usable steps.")
        val first = startAtStep.coerceIn(0, records.lastIndex)
        val nextPath = path + macro.id
        for (index in first..records.lastIndex) {
            val step = records.getOrNull(index)?.step
                ?: return Failure(rootIndex ?: index, rootCount, "A macro step was unavailable.")
            val reportIndex = rootIndex ?: index
            if (depth == 0) onProgress(MacroStepProgress(macro.name, index, records.size, depth))
            else onProgress(MacroStepProgress(macro.name, index, records.size, depth))
            try {
                val failure = execute(step, macro, nextPath, depth, reportIndex, rootCount)
                if (failure != null) return Failure(reportIndex, rootCount, failure)
                if (index < records.lastIndex) delayBetweenSteps()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(error)
                return Failure(reportIndex, rootCount, "Step ${index + 1} stopped after ${error.javaClass.simpleName}.")
            }
        }
        return null
    }

    private suspend fun execute(
        step: TypedMacroStep,
        currentMacro: MacroEntity,
        path: Set<Long>,
        depth: Int,
        rootIndex: Int,
        rootCount: Int
    ): String? = when (step) {
        is TypedMacroStep.OpenApp -> commandFailure("open ${step.app}")
        is TypedMacroStep.TapText -> commandFailure("tap ${step.text}")
        is TypedMacroStep.TypeText -> commandFailure("type ${step.text}")
        is TypedMacroStep.Wait -> { delay(step.seconds * 1_000L); null }
        TypedMacroStep.Scroll -> commandFailure("scroll down")
        TypedMacroStep.Back -> commandFailure("go back")
        TypedMacroStep.Home -> commandFailure("go home")
        TypedMacroStep.Recents -> commandFailure("open recent apps")
        is TypedMacroStep.SpeakText -> if (speakText(step.text)) null else "Text-to-speech is unavailable or could not finish."
        is TypedMacroStep.RunChatCommand -> commandFailure(step.command)
        TypedMacroStep.DeviceStatus -> commandFailure("device status")
        is TypedMacroStep.RunAnotherMacro -> {
            val nested = findMacro(step.macroId) ?: return "The selected nested macro is missing."
            val failure = runMacro(nested, 0, path, depth + 1, rootIndex, rootCount)
            failure?.let { "Nested macro ‘${nested.name}’ failed: ${it.message}" }
        }
    }

    private suspend fun commandFailure(command: String): String? {
        val reply = runChatCommand(command)
        return reply.takeIf(MacroRunPolicy::isFailureReply)?.take(400)
    }

    companion object { private const val MAX_NESTING_DEPTH = 5 }
}
