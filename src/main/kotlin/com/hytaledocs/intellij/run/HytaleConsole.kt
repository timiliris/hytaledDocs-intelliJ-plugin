package com.hytaledocs.intellij.run

interface HytaleConsole {
    fun println(text: String)
    fun printInfo(text: String)
    fun printSuccess(text: String)
    fun printError(text: String)
}
