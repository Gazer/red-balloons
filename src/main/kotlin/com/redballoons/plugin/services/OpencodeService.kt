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
import com.intellij.openapi.util.Key
import com.redballoons.plugin.prompt.Context
import com.redballoons.plugin.prompt.ContextState
import com.redballoons.plugin.settings.RedBalloonsSettings
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

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

                override fun processTerminated(event: ProcessEvent) {
                    log("Models process terminated with exit code: ${event.exitCode}")
                }
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
                processHandler.processInput.close()
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

                while (!processHandler.waitFor(100)) {
                    if (indicator.isCanceled) {
                        log("Cancelled by user")
                        context.state = ContextState.CANCELLED
                        processHandler.destroyProcess()

                        val result = ExecutionResult(
                            success = false,
                            output = outputBuilder.toString(),
                            error = "Cancelled by user",
                            exitCode = -1
                        )
                        ApplicationManager.getApplication().invokeLater {
                            onComplete(result)
                        }
                        return
                    }
                }

                val exitCode = processHandler.exitCode ?: -1
                log("Done with exit code: $exitCode")

                val result = if (exitCode == 0 && context.tmpFile.exists()) {
                    context.state = ContextState.DONE
                    val tempOutput = context.tmpFile.readText().trim()
                    ExecutionResult(
                        success = true,
                        output = tempOutput,
                        error = errorBuilder.toString().trim(),
                        exitCode = exitCode
                    )
                } else {
                    log("Using stdout: ${outputBuilder.toString().trim()}")
                    context.state = if (exitCode == 0) ContextState.DONE else ContextState.ERROR
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