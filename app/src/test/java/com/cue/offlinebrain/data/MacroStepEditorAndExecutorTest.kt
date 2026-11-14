package com.cue.offlinebrain.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroStepEditorAndExecutorTest {
    @Test fun editorIdsSurviveReorderEditDuplicateAndPersistence() {
        val first = MacroStepEditorModel.newDraft(TypedMacroStep.OpenApp("Chrome"))
        val second = MacroStepEditorModel.newDraft(TypedMacroStep.Wait(2))
        val rows = listOf(first, second)
        val moved = MacroStepEditorModel.move(rows, 0, 1)
        assertEquals(listOf(second.id, first.id), moved.map { it.id })
        val edited = MacroStepEditorModel.update(moved, 1, MacroStepKind.OPEN_APP, "Calendar", null)
        assertEquals(first.id, edited[1].id)
        val duplicated = MacroStepEditorModel.duplicate(edited, 1)
        assertEquals(3, duplicated.size)
        assertEquals(TypedMacroStep.OpenApp("Calendar"), duplicated[2].step)
        assertFalse(duplicated[1].id == duplicated[2].id)
        assertEquals(duplicated.map { it.id }, MacroStepEditorModel.drafts(MacroStepEditorModel.encode(duplicated)).map { it.id })
    }

    @Test fun editorCanInsertAndRemoveWithoutChangingOtherIds() {
        val first = MacroStepEditorModel.newDraft(TypedMacroStep.Back)
        val middle = MacroStepEditorModel.newDraft(TypedMacroStep.Scroll)
        val last = MacroStepEditorModel.newDraft(TypedMacroStep.Home)
        val added = MacroStepEditorModel.insertAfter(listOf(first, last), 0, middle)
        assertEquals(listOf(first.id, middle.id, last.id), added.map { it.id })
        val removed = MacroStepEditorModel.remove(added, 1)
        assertEquals(listOf(first.id, last.id), removed.map { it.id })
        assertEquals(6, MacroStepEditorModel.estimatedSeconds(listOf(first, MacroStepEditorModel.newDraft(TypedMacroStep.Wait(4)))))
    }

    @Test fun editorRejectsNestedMacroCycles() {
        val existing = MacroEntity(1L, "One", TypedMacroSteps.encode(listOf(TypedMacroStep.RunAnotherMacro(2L))))
        val candidateSteps = listOf(MacroStepEditorModel.newDraft(TypedMacroStep.RunAnotherMacro(1L)))
        assertNotNull(MacroStepEditorModel.validate(candidateSteps, listOf(existing), editedMacroId = 2L))
    }

    @Test fun executorStopsAtFirstFailedStepAndDoesNotRunLaterCommands() = runBlocking {
        val ran = mutableListOf<String>()
        val executor = MacroStepExecutor(
            findMacro = { null },
            runChatCommand = { command -> ran += command; if (command == "second") "FAIL — action refused" else "Done" },
            speakText = { true },
            delayBetweenSteps = {}
        )
        val macro = MacroEntity(7L, "Ordered", TypedMacroSteps.encode(listOf(
            TypedMacroStep.RunChatCommand("first"),
            TypedMacroStep.RunChatCommand("second"),
            TypedMacroStep.RunChatCommand("must not run")
        )))
        val result = executor.run(macro)
        assertFalse(result.succeeded)
        assertEquals(1, result.failedStepIndex)
        assertEquals(listOf("first", "second"), ran)
    }

    @Test fun executorStopsNestedMacroLoop() = runBlocking {
        val one = MacroEntity(1L, "One", TypedMacroSteps.encode(listOf(TypedMacroStep.RunAnotherMacro(2L))))
        val two = MacroEntity(2L, "Two", TypedMacroSteps.encode(listOf(TypedMacroStep.RunAnotherMacro(1L))))
        val executor = MacroStepExecutor({ id -> listOf(one, two).firstOrNull { it.id == id } }, { "unexpected" }, { true }, delayBetweenSteps = {})
        val result = executor.run(one)
        assertFalse(result.succeeded)
        assertEquals(0, result.failedStepIndex)
        assertTrue(result.message.orEmpty().contains("loop", ignoreCase = true))
    }

    @Test fun stopCancelsWaitBeforeAnyLaterCommand() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val executor = MacroStepExecutor(
            findMacro = { null },
            runChatCommand = { command -> ran += command; "Done" },
            speakText = { true },
            onProgress = { entered.complete(Unit) },
            delayBetweenSteps = {}
        )
        val macro = MacroEntity(3L, "Wait", TypedMacroSteps.encode(listOf(TypedMacroStep.Wait(300), TypedMacroStep.RunChatCommand("later"))))
        val job = launch { executor.run(macro) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(ran.isEmpty())
    }
}
