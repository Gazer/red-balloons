package com.redballoons.plugin.prompt

import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project
import com.redballoons.plugin.model.ExecutionResult
import com.redballoons.plugin.model.SearchResult
import com.redballoons.plugin.model.SelectionContext
import com.redballoons.plugin.services.OpencodeService
import java.io.File
import java.time.Instant
import java.util.UUID

sealed class ContextData(open val project: Project) {
    data class Visual(
        override val project: Project,
        val fullPath: String,
        val fileType: String,
        val selectionContext: SelectionContext,
        val imports: List<String> = emptyList(),
        val content: String = "",
    ) : ContextData(project)

    data class Search(
        override val project: Project,
        val quickFixItems: List<SearchResult> = emptyList(),
        val response: String = "",
    ) : ContextData(project)

    data class Vibe(
        override val project: Project,
        val quickFixItems: List<SearchResult> = emptyList(),
    ) : ContextData(project)
}

enum class Operation {
    VISUAL,
    SEARCH,
    TUTORIAL,
    VIBE,
    UNKNOWN
}

enum class ContextState {
    READY,
    REQUESTING,
    CANCELLED,
    DONE,
    ERROR
}

class Context(
    model: String? = null,
    val workingDirectory: String,
    val xid: String = UUID.randomUUID().toString(),
) {
    var state: ContextState = ContextState.READY
    var operation: Operation = Operation.UNKNOWN
    var userPrompt: String = ""
    val cleanUps: MutableList<() -> Unit> = mutableListOf()
    val mdFileNames: MutableList<String> = mutableListOf()
    var model: String = model ?: ""
    val agentContext: MutableList<String> = mutableListOf()
    val tmpDir = File(workingDirectory, "tmp").also {
        it.mkdir()
    }
    val tmpFile: File = File.createTempFile("context_", ".tmp", tmpDir)
    val marks: MutableMap<String, Any> = mutableMapOf()
    val startedAt: Instant = Instant.now()
    var data: ContextData? = null
    var process: OSProcessHandler? = null

    fun addPromptContent(prompt: String) {
        agentContext.add(prompt)
    }

    fun startRequest(cb: (ExecutionResult) -> Unit) {
        if (state != ContextState.READY) {
            return
        }
        // TODO: Validations?
        finalize()

        val prompt = agentContext.joinToString("\n")
        val provider = OpencodeService.getInstance()

        state = ContextState.REQUESTING
        provider.makeRequest(prompt, this) { result ->
            cb(result)
        }
    }

    private fun finalize() {
        if (data is ContextData.Visual) {
            val visualData = data as ContextData.Visual
            val loc = PromptStrings.getFileLocation(visualData.fullPath, visualData.selectionContext)
            agentContext.add(loc)
            agentContext.add(PromptStrings.getRangeText((data as ContextData.Visual).selectionContext))
        }
        agentContext.add(
            PromptStrings.getTempfileLocation(tmpFile)
        )
        if (operation == Operation.VISUAL || operation == Operation.SEARCH || operation == Operation.TUTORIAL) {
            agentContext.add(PromptStrings.getOnlyTempfileChange())
        }
    }

    fun addReferences(refs: List<Map<String, String>>) {
        for (ref in refs) {
            ref["content"]?.let { content ->
                agentContext.add(content)
            }
        }
    }
}