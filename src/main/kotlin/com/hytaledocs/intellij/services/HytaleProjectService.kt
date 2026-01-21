package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.util.HttpClientPool
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class HytaleProjectService(private val project: Project) {

    companion object {
        const val SERVER_JAR_URL = "https://cdn.hytale.com/HytaleServer.jar"
        const val SERVER_JAR_NAME = "HytaleServer.jar"

        fun getInstance(project: Project): HytaleProjectService {
            return project.getService(HytaleProjectService::class.java)
        }
    }

    fun isHytaleProject(): Boolean {
        val basePath = project.basePath ?: return false
        val manifestFile = File(basePath, "src/main/resources/manifest.json")
        val serverJar = File(basePath, "libs/$SERVER_JAR_NAME")
        return manifestFile.exists() || serverJar.exists()
    }

    fun hasServerJar(): Boolean {
        val basePath = project.basePath ?: return false
        return File(basePath, "libs/$SERVER_JAR_NAME").exists()
    }

    fun downloadServerJar(): CompletableFuture<Path> {
        val basePath = project.basePath
            ?: return CompletableFuture.failedFuture(IllegalStateException("Project has no base path"))

        val libsDir = File(basePath, "libs")
        if (!libsDir.exists()) {
            libsDir.mkdirs()
        }

        val targetPath = libsDir.toPath().resolve(SERVER_JAR_NAME)

        return CompletableFuture.supplyAsync {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_JAR_URL))
                .GET()
                .build()

            val response = HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() == 200) {
                response.body().use { inputStream ->
                    java.nio.file.Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
                targetPath
            } else {
                throw RuntimeException("Failed to download server JAR: HTTP ${response.statusCode()}")
            }
        }
    }
}
