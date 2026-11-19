package com.example.m_nirog.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NirogColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    background = Color.White,
    surface = Color.White,
)

@Composable
fun NirogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NirogColors, content = content)
}
