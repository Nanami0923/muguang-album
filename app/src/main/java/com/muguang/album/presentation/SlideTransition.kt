package com.muguang.album.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.muguang.album.core.SlideEffect
import com.muguang.album.playback.SlideLayer
import com.muguang.album.playback.slideLayers

@Composable
internal fun SlideTransition(effect: SlideEffect, progress: Float,
    previous: (@Composable (Modifier) -> Unit)?, current: @Composable (Modifier) -> Unit) {
    val layers = slideLayers(effect, FastOutSlowInEasing.transform(progress.coerceIn(0f, 1f)))
    val density = LocalDensity.current.density
    fun layer(value: SlideLayer) = Modifier.fillMaxSize().graphicsLayer {
        alpha = value.alpha; translationX = value.x * size.width; translationY = value.y * size.height
        scaleX = value.scale; scaleY = value.scale; rotationY = value.rotationY
        transformOrigin = TransformOrigin(value.pivotX, .5f)
        cameraDistance = 12f * density
        clip = true
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (previous != null && layers.outgoingOnTop) {
            current(layer(layers.incoming)); previous(layer(layers.outgoing))
        } else {
            previous?.invoke(layer(layers.outgoing)); current(layer(layers.incoming))
        }
    }
}
