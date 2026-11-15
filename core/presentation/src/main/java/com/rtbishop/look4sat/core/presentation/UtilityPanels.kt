package com.rtbishop.look4sat.core.presentation

import androidx.compose.runtime.staticCompositionLocalOf

data class UtilityPanels(
    val selfCheck: () -> Unit = {},
    val backup: () -> Unit = {}
)

val LocalUtilityPanels = staticCompositionLocalOf { UtilityPanels() }
