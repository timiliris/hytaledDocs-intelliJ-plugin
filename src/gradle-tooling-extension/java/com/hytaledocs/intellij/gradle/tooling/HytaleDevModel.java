package com.hytaledocs.intellij.gradle.tooling;

/**
 * Model interface for querying Hytale Dev plugin information during Gradle sync.
 * This interface is used by both the ModelBuilder (in Gradle process) and the
 * ProjectResolverExtension (in IntelliJ process).
 */
public interface HytaleDevModel {
    /**
     * Returns true if the net.janrupf.hytale-dev Gradle plugin is applied to this project.
     */
    boolean hasHytaleDevPlugin();

    /**
     * Returns the version of the Hytale Dev plugin, if available.
     */
    String getPluginVersion();
}
