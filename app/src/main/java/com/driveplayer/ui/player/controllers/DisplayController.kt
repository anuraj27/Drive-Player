package com.driveplayer.ui.player.controllers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DisplayController {

    // -1f = use system brightness (not overridden).
    // 0.01f–1.0f = manual override applied to window.screenBrightness.
    // Gesture and settings slider both write here; PlayerScreen applies it to the window.
    private val _brightness = MutableStateFlow(-1f)
    val brightness: StateFlow<Float> = _brightness

    // 1f = normal. 0f–2f range, applied via Android ColorMatrix on the PlayerView layer.
    private val _contrast = MutableStateFlow(1f)
    val contrast: StateFlow<Float> = _contrast

    // 1f = normal. 0f–2f range, applied via Android ColorMatrix on the PlayerView layer.
    private val _saturation = MutableStateFlow(1f)
    val saturation: StateFlow<Float> = _saturation

    fun setBrightness(value: Float) {
        _brightness.value = value.coerceIn(0.01f, 1f)
    }

    fun setContrast(value: Float) {
        _contrast.value = value.coerceIn(0f, 2f)
    }

    fun setSaturation(value: Float) {
        _saturation.value = value.coerceIn(0f, 2f)
    }

    fun resetFilters() {
        _brightness.value = -1f
        _contrast.value = 1f
        _saturation.value = 1f
    }
}
