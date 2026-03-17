package com.redballoons.plugin.model

import javax.swing.Icon

data class AutoCompleteSuggestion(
    val fileName: String,
    val relativePath: String,
    val icon: Icon?,
)