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
import com.redballoons.plugin.settings.RedBalloonsSettings
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

@Service
class OpencodeService {

    private val LOG = Logger.getInstance(OpencodeService::class.java)
    private val activeProcesses = ConcurrentHashMap<String, OSProcessHandler>()
    private val logFile = File("/tmp/oc.txt")

    fun log(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val line = "[$timestamp] $message"
        LOG.info(line)
        try {
            PrintWriter(FileWriter(logFile, true)).use { it.println(line) }
        } catch (e: Exception) {
            // ignore file write errors
        }
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
        if (activeProcesses.isEmpty()) return false
        var killed = false
        for (handler in activeProcesses.values) {
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                killed = true
            }
        }
        activeProcesses.clear()
        if (killed) LOG.info("All active processes killed by user")
        return killed
    }

    fun killProcess(xid: String): Boolean {
        val handler = activeProcesses.remove(xid)
        return if (handler != null && !handler.isProcessTerminated) {
            handler.destroyProcess()
            LOG.info("Process $xid killed by user")
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

    fun isRunning(): Boolean = activeProcesses.values.any { !it.isProcessTerminated }

    fun isRunning(xid: String): Boolean = activeProcesses[xid]?.isProcessTerminated == false

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
                activeProcesses[context.xid] = processHandler

                log("Process started, PID: ${processHandler.process.pid()}, XID: ${context.xid}")

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
                        log("Process terminated with exit code: ${event.exitCode}, XID: ${context.xid}")
                        activeProcesses.remove(context.xid)
                    }
                })

                processHandler.startNotify()

                while (!processHandler.waitFor(100)) {
                    if (indicator.isCanceled) {
                        log("Cancelled by user")
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

                val tempOutput = if (context.tmpFile.exists()) context.tmpFile.readText().trim() else ""
                val stdout = outputBuilder.toString().trim()

                val result = if (exitCode == 0 && tempOutput.isNotBlank()) {
                    ExecutionResult(
                        success = true,
                        output = tempOutput,
                        error = errorBuilder.toString().trim(),
                        exitCode = exitCode
                    )
                } else {
                    if (exitCode == 0 && tempOutput.isBlank() && stdout.isNotBlank()) {
                        log("Using stdout: $stdout")
                    }
                    ExecutionResult(
                        success = exitCode == 0,
                        output = stdout,
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