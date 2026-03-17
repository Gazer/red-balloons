package com.redballoons.plugin.extensions

import com.redballoons.plugin.model.AutoCompleteSuggestion

interface CompletionProvider {

    val trigger: Char
    val name: String

    fun isValid(token: String): Boolean
    fun resolve(token: String): String?
    fun getFiles(prefix: String): List<AutoCompleteSuggestion>
}