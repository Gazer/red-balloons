package com.redballoons.plugin.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.Operation
import com.redballoons.plugin.settings.RedBalloonsSettings
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * Context information for Selection Mode
 */
data class SelectionContext(
    val filePath: String,
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val selectedText: String,
    val fileContent: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

@Service
class OpencodeService {

    private val LOG = Logger.getInstance(OpencodeService::class.java)
    private val currentProcess: AtomicReference<OSProcessHandler?> = AtomicReference(null)
    private val logFile = File("/tmp/oc.txt")

    private fun log(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val line = "[$timestamp] $message"
        LOG.info(line)
        try {
            PrintWriter(FileWriter(logFile, true)).use { it.println(line) }
        } catch (e: Exception) {
            // ignore file write errors
        }
    }

    /**
     * Get the temp output file path inside the project's .opencode directory
     */
    private fun getTempOutputFile(workingDirectory: String): File {
        val opencodeDir = File(workingDirectory, ".opencode")
        if (!opencodeDir.exists()) {
            opencodeDir.mkdirs()
        }
        return File(opencodeDir, "plugin_output.txt")
    }

    data class ExecutionResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int,
        val imports: List<String> = emptyList(),
    )

    enum class ExecutionMode {
        SELECTION,
        VIBE,
        SEARCH
    }

    /**
     * Execute opencode CLI with the given prompt (generic version for Vibe/Search modes)
     */
    fun execute(
        project: Project,
        prompt: String,
        mode: ExecutionMode,
        context: String? = null,
        workingDirectory: String? = null,
        onComplete: (ExecutionResult) -> Unit,
    ) {
        val settings = RedBalloonsSettings.getInstance()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Opencode: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }} Mode",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Running opencode..."

                log("=== START EXECUTION ===")
                log("Mode: $mode")
                log("Prompt: $prompt")

                try {
                    val fullPrompt = buildFullPrompt(prompt, mode, context, settings)
                    val result = executeSync(
                        fullPrompt = fullPrompt,
                        workingDirectory = workingDirectory ?: project.basePath ?: ".",
                        settings = settings,
                        indicator = indicator
                    )

                    log("=== EXECUTION COMPLETE ===")
                    log("Success: ${result.success}")
                    log("Exit code: ${result.exitCode}")

                    ApplicationManager.getApplication().invokeLater {
                        onComplete(result)
                    }
                } catch (e: Exception) {
                    log("=== EXCEPTION ===")
                    log("Type: ${e.javaClass.simpleName}")
                    log("Message: ${e.message}")
                    LOG.error("Opencode execution failed", e)
                    ApplicationManager.getApplication().invokeLater {
                        onComplete(
                            ExecutionResult(
                                success = false,
                                output = "",
                                error = "${e.javaClass.simpleName}: ${e.message}",
                                exitCode = -1
                            )
                        )
                    }
                }
            }

            override fun onCancel() {
                killCurrentProcess()
            }
        })
    }

    private fun executeSync(
        fullPrompt: String,
        workingDirectory: String,
        settings: RedBalloonsSettings,
        indicator: ProgressIndicator,
    ): ExecutionResult {
        val commandLine = GeneralCommandLine().apply {
            exePath = settings.opencodeCliPath
            setWorkDirectory(File(workingDirectory))

            // Use "run" subcommand for non-interactive execution
            addParameter("run")

            // Add model flag if specified
            if (settings.modelName.isNotBlank()) {
                addParameter("--model")
                addParameter(settings.modelName)
            }

            // Add the prompt as the message
            addParameter(fullPrompt)
        }

        log("Command: ${commandLine.commandLineString}")
        log("Working dir: $workingDirectory")

        // Log a copy-pasteable version of the command
        val copyPasteCmd = buildString {
            append("cd '")
            append(workingDirectory)
            append("' && ")
            append(settings.opencodeCliPath)
            append(" run")
            if (settings.modelName.isNotBlank()) {
                append(" --model '")
                append(settings.modelName)
                append("'")
            }
            append(" $'")
            append(fullPrompt.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n"))
            append("'")
        }
        log("=== COPY-PASTE COMMAND ===")
        log(copyPasteCmd)
        log("=== END COMMAND ===")

        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        val processHandler = OSProcessHandler(commandLine)
        currentProcess.set(processHandler)
        log("Process started, PID: ${processHandler.process.pid()}")

        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text
                if (outputType === ProcessOutputTypes.STDOUT) {
                    outputBuilder.append(text)
                    log("STDOUT: $text")
                } else if (outputType === ProcessOutputTypes.STDERR) {
                    errorBuilder.append(text)
                    log("STDERR: $text")
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                log("Process terminated with exit code: ${event.exitCode}")
                currentProcess.set(null)
            }
        })

        processHandler.startNotify()

        // Wait for process to complete, checking for cancellation
        while (!processHandler.isProcessTerminated) {
            if (indicator.isCanceled) {
                processHandler.destroyProcess()
                return ExecutionResult(
                    success = false,
                    output = outputBuilder.toString(),
                    error = "Cancelled by user",
                    exitCode = -1
                )
            }
            Thread.sleep(100)
        }

        val exitCode = processHandler.exitCode ?: -1

        return ExecutionResult(
            success = exitCode == 0,
            output = outputBuilder.toString().trim(),
            error = errorBuilder.toString().trim(),
            exitCode = exitCode
        )
    }

    private fun buildFullPrompt(
        userPrompt: String,
        mode: ExecutionMode,
        context: String?,
        settings: RedBalloonsSettings,
    ): String {
        return when (mode) {
            ExecutionMode.SELECTION -> {
                // This shouldn't be called anymore, but keep for backwards compat
                val systemPrompt = settings.selectionModeSystemPrompt
                """
$systemPrompt

--- CODE TO MODIFY ---
${context ?: ""}
--- END CODE ---

User instruction: $userPrompt
                """.trimIndent()
            }

            ExecutionMode.SEARCH -> {
                val systemPrompt = settings.searchModeSystemPrompt
                """
$systemPrompt

User search query: $userPrompt
                """.trimIndent()
            }

            ExecutionMode.VIBE -> {
                // Vibe mode: just pass the prompt directly, opencode handles everything
                userPrompt
            }
        }
    }

    /**
     * Kill the currently running process (Kill Switch)
     */
    fun killCurrentProcess(): Boolean {
        val process = currentProcess.get()
        return if (process != null && !process.isProcessTerminated) {
            process.destroyProcess()
            currentProcess.set(null)
            LOG.info("Process killed by user")
            true
        } else {
            false
        }
    }

    fun getModels(): List<String> {
        val settings = RedBalloonsSettings.getInstance()

        try {
            val commandLine = GeneralCommandLine().apply {
                exePath = settings.opencodeCliPath
                addParameter("models")
            }

            log("Getting models: ${commandLine.commandLineString}")

            val processHandler = OSProcessHandler(commandLine)
            val outputBuilder = StringBuilder()

            processHandler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType === ProcessOutputTypes.STDOUT) {
                        outputBuilder.append(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {}
            })

            processHandler.startNotify()
            processHandler.waitFor()

            val output = outputBuilder.toString()
            log("Models output: $output")

            return output.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            LOG.error("Failed to get models", e)
            return emptyList()
        }
    }

    fun isRunning(): Boolean = currentProcess.get()?.isProcessTerminated == false

    fun makeRequest(query: String, context: Context, cb: (ExecutionResult) -> Unit) {
        val command = buildCommand(query, context)
        log("Command: ${command.commandLineString}")
        log("Working dir: ${context.workingDirectory}")

        runAsync(context, command) { result ->
            cb(result)
        }
    }

    private fun buildCommand(query: String, context: Context): GeneralCommandLine {
        val settings = RedBalloonsSettings.getInstance()
        return GeneralCommandLine().apply {
            exePath = settings.opencodeCliPath
            setWorkDirectory(File(context.data?.project?.basePath ?: "."))

            addParameter("run")
            addParameter("--agent")
            addParameter("build")
            addParameter("--model")
            addParameter(context.model)
            addParameter(query)
        }
    }

    private fun runAsync(context: Context, command: GeneralCommandLine, onComplete: (ExecutionResult) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.data?.project,
            "${context.operation.name} Mode",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Running opencode..."

                log("=== START EXECUTION ===")
                log("Mode: ${context.operation}")

                val processHandler = OSProcessHandler(command)
                currentProcess.set(processHandler)
                log("Process started, PID: ${processHandler.process.pid()}")

                val outputBuilder = StringBuilder()
                val errorBuilder = StringBuilder()

                processHandler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        val text = event.text
                        if (outputType === ProcessOutputTypes.STDOUT) {
                            outputBuilder.append(text)
                            log("STDOUT: $text")
                        } else if (outputType === ProcessOutputTypes.STDERR) {
                            errorBuilder.append(text)
                            log("STDERR: $text")
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        log("Process terminated with exit code: ${event.exitCode}")
                        currentProcess.set(null)
                    }
                })

                processHandler.startNotify()

                // Wait for process to complete, checking for cancellation
                while (!processHandler.isProcessTerminated) {
                    if (indicator.isCanceled) {
                        processHandler.destroyProcess()

                        log("Cancelled by user")
                        val result = ExecutionResult(
                            success = false,
                            output = outputBuilder.toString(),
                            error = "Cancelled by user",
                            exitCode = -1
                        )
                        ApplicationManager.getApplication().invokeLater {
                            onComplete(result)
                        }
                    }
                    Thread.sleep(100)
                }

                val exitCode = processHandler.exitCode ?: -1

                log("Done")
                val result = if (exitCode == 0 && context.tmpFile.exists()) {
                    val tempOutput = context.tmpFile.readText().trim()
                    ExecutionResult(
                        success = true,
                        output = tempOutput,
                        error = errorBuilder.toString().trim(),
                        exitCode = exitCode
                    )
                } else {
                    log("Using stdout: ${outputBuilder.toString().trim()}")
                    ExecutionResult(
                        success = exitCode == 0,
                        output = outputBuilder.toString().trim(),
                        error = errorBuilder.toString().trim(),
                        exitCode = exitCode
                    )
                }
                ApplicationManager.getApplication().invokeLater {
                    onComplete(result)
                }
            }
        })
    }

    companion object {
        fun getInstance(): OpencodeService =
            ApplicationManager.getApplication().getService(OpencodeService::class.java)
    }
}