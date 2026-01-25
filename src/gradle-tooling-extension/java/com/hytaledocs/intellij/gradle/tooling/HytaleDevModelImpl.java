package com.hytaledocs.intellij.gradle.tooling;

import java.io.Serializable;

/**
 * Serializable implementation of HytaleDevModel.
 * This class is instantiated in the Gradle process and serialized to IntelliJ.
 */
public class HytaleDevModelImpl implements HytaleDevModel, Serializable {
    private static final long serialVersionUID = 1L;

    private final boolean hasPlugin;
    private final String version;

    public HytaleDevModelImpl(boolean hasPlugin, String version) {
        this.hasPlugin = hasPlugin;
        this.version = version;
    }

    @Override
    public boolean hasHytaleDevPlugin() {
        return hasPlugin;
    }

    @Override
    public String getPluginVersion() {
        return version;
    }
}
