package com.hytaledocs.intellij

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

object HytaleIcons {
    private val baseIcon = IconLoader.getIcon("/icons/hytaledocs.png", HytaleIcons::class.java)

    @JvmField
    val HYTALE: Icon = IconUtil.scale(baseIcon, null, 16f / baseIcon.iconWidth)

    @JvmField
    val HYTALE_LARGE: Icon = IconUtil.scale(baseIcon, null, 40f / baseIcon.iconWidth)

    @JvmField
    val HYTALE_WIZARD: Icon = IconUtil.scale(baseIcon, null, 64f / baseIcon.iconWidth)

    @JvmField
    val HYTALE_BANNER: Icon = IconUtil.scale(baseIcon, null, 80f / baseIcon.iconWidth)
}
