package com.hytaledocs.intellij.run

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

class HytaleProcessManager {
    companion object {
        private val LOG = Logger.getInstance(HytaleProcessManager::class.java)
    }

    fun killLingeringServerProcesses(console: HytaleConsole) {
        try {
            ProcessHandle.allProcesses()
                .filter { handle ->
                    handle.info().commandLine()
                        .map { cmd -> cmd.contains("HytaleServer.jar") }
                        .orElse(false)
                }
                .forEach { handle ->
                    console.printInfo("Killing lingering server process: ${handle.pid()}")
                    handle.destroyForcibly()
                }
            Thread.sleep(2000) // Wait for processes to terminate
        } catch (e: Exception) {
            LOG.warn("Failed to kill lingering processes", e)
        }
    }
}
