package com.hytaledocs.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object HytaleIcons {
    @JvmField
    val HYTALE: Icon = IconLoader.getIcon("/icons/hytaledocs-plugin-16.svg", HytaleIcons::class.java)

    @JvmField
    val HYTALE_13: Icon = IconLoader.getIcon("/icons/hytaledocs-plugin-13.svg", HytaleIcons::class.java)

    // For larger contexts, IntelliJ will scale the SVG appropriately
    @JvmField
    val HYTALE_LARGE: Icon = HYTALE

    @JvmField
    val HYTALE_WIZARD: Icon = HYTALE

    @JvmField
    val HYTALE_BANNER: Icon = HYTALE
}
