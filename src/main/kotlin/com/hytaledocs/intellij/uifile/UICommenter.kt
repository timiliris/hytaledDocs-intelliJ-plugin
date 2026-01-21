package com.hytaledocs.intellij.uifile

import com.intellij.lang.Commenter

/**
 * Commenter for UI files.
 * Supports // line comments and /* */ block comments.
 */
class UICommenter : Commenter {

    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}
