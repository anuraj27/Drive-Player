package com.driveplayer.ui.player.controllers

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DisplayController {
    
    // Values range generally from 0f to 2f, 1f is normal
    private val _brightness = MutableStateFlow(1f)
    val brightness: StateFlow<Float> = _brightness
    
    private val _contrast = MutableStateFlow(1f)
    val contrast: StateFlow<Float> = _contrast
    
    private val _saturation = MutableStateFlow(1f)
    val saturation: StateFlow<Float> = _saturation

    fun setBrightness(value: Float) {
        _brightness.value = value.coerceIn(0f, 3f)
    }

    fun setContrast(value: Float) {
        _contrast.value = value.coerceIn(0f, 3f)
    }

    fun setSaturation(value: Float) {
        _saturation.value = value.coerceIn(0f, 3f)
    }

    fun resetFilters() {
        _brightness.value = 1f
        _contrast.value = 1f
        _saturation.value = 1f
    }
}
