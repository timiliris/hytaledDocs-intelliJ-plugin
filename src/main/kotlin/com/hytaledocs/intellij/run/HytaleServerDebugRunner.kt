package com.hytaledocs.intellij.run

import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor

/**
 * Debug runner for Hytale Server.
 * Starts the server with JDWP agent and attaches the IntelliJ debugger.
 */
class HytaleServerDebugRunner : GenericDebuggerRunner() {

    override fun getRunnerId(): String = "HytaleServerDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        return executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is HytaleServerRunConfiguration
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        if (state !is HytaleServerRunState) {
            return super.doExecute(state, environment)
        }

        // Get the remote connection info
        val connection = state.getRemoteConnection()

        // Execute the run state (starts server with debug agent)
        val executionResult = state.execute(environment.executor, this)
            ?: return null

        // Attach debugger to the running server
        // The last parameter is pollConnection (true = poll until server is ready)
        return attachVirtualMachine(state, environment, connection, true)
    }
}
