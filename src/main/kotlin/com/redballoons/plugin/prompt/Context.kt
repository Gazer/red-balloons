package com.redballoons.plugin.prompt

import com.intellij.openapi.project.Project
import com.redballoons.plugin.model.SearchResult
import com.redballoons.plugin.services.OpencodeService
import com.redballoons.plugin.services.SelectionContext
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

    data class Custom(
        override val project: Project,
        val properties: Map<String, Any>,
    ) : ContextData(project)
}

enum class Operation {
    VISUAL,
    SEARCH,
    TUTORIAL,
    VIBE,
    UNKNOWN
}

class Context(
    model: String? = null,
    val workingDirectory: String,
    val xid: String = UUID.randomUUID().toString(),
) {
    private val items: MutableList<String> = mutableListOf()

    var state: String = "ready"
    var operation: Operation = Operation.UNKNOWN
    var userPrompt: String = ""
    val cleanUps: MutableList<() -> Unit> = mutableListOf()
    val mdFileNames: MutableList<String> = mutableListOf()
    var model: String = model ?: ""
    val agentContext: MutableMap<String, Any> = mutableMapOf()
    val tmpDir = File(workingDirectory, "tmp").also {
        it.mkdir()
    }
    val tmpFile: File = File.createTempFile("context_", ".tmp", tmpDir)
    val marks: MutableMap<String, Any> = mutableMapOf()
    val startedAt: Instant = Instant.now()
    var data: ContextData? = null

    val concatenated: String
        get() = items.joinToString("\n")

    fun addPromptContent(prompt: String) {
        items.add(prompt)
    }

    fun startRequest(cb: (OpencodeService.ExecutionResult) -> Unit) {
        // TODO: Validations?
        finalize()

        val prompt = concatenated
        val provider = OpencodeService.getInstance()

        provider.makeRequest(prompt, this) { result ->
            cb(result)
        }
    }

    private fun finalize() {
        if (data is ContextData.Visual) {
            val visualData = data as ContextData.Visual
            val loc = PromptStrings.getFileLocation(visualData.fullPath, visualData.selectionContext)
            items.add(loc)
            items.add(PromptStrings.getRangeText((data as ContextData.Visual).selectionContext))
        }
        items.add(
            PromptStrings.getTempfileLocation(tmpFile)
        )
        if (operation == Operation.VISUAL || operation == Operation.SEARCH || operation == Operation.TUTORIAL) {
            items.add(PromptStrings.getOnlyTempfileChange())
        }
    }
}