package com.hytaledocs.intellij.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.ServerSocket


/**
 * Execution state for Hytale Server run configuration.
 * Handles: Build -> Deploy -> Start/Reload server.
 * Supports debugging by adding JDWP agent when running in debug mode.
 */
class HytaleServerRunState(
    private val project: Project,
    private val config: HytaleServerRunConfiguration,
    private val environment: ExecutionEnvironment
) : RunProfileState {

    companion object {
        private val LOG = Logger.getInstance(HytaleServerRunState::class.java)
        private const val DEBUG_PORT_START = 5005
        private const val DEBUG_PORT_END = 5015
    }

    val isDebugMode: Boolean
        get() = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID

    // Find available debug port at construction time
    private val debugPort: Int by lazy { findAvailablePort() }

    /**
     * Returns the remote connection for debugging.
     * IntelliJ will use this to attach the debugger after the server starts.
     */
    fun getRemoteConnection(): RemoteConnection {
        return RemoteConnection(true, "localhost", debugPort.toString(), false)
    }

    /**
     * Find an available port for debugging.
     */
    private fun findAvailablePort(): Int {
        for (port in DEBUG_PORT_START..DEBUG_PORT_END) {
            try {
                ServerSocket(port).use { return port }
            } catch (e: Exception) {
                // Port in use, try next
            }
        }
        // Fallback: let OS assign a port
        return ServerSocket(0).use { it.localPort }
    }

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console

        val processHandler = HytaleServerProcessHandler(project, config, consoleView, isDebugMode, debugPort)
        consoleView.attachToProcess(processHandler)

        // Start execution in background
        processHandler.startExecution()

        return DefaultExecutionResult(consoleView, processHandler)
    }
}

