package com.muguang.album.core

import kotlin.math.hypot

data class AiSettings(
    val endpoint: String = "", val model: String = "", val hasKey: Boolean = false,
    val enabled: Boolean = false, val paused: Boolean = false,
    val maxImagesPerRun: Int = 50, val sent: Int = 0,
    val maxImageSide: Int = 1024, val timeoutSeconds: Int = 60,
    val tokenParameter: String = "max_tokens", val status: String = "尚未配置",
) {
    val configured get() = endpoint.startsWith("https://") && model.isNotBlank() && hasKey
}

fun categoryFor(medium: String, composition: String): String = when (medium) {
    "REAL" -> if (composition == "PERSON") "REAL_PERSON" else "REAL_OTHER"
    "ANIME_ILLUSTRATION" -> when (composition) { "PERSON" -> "ANIME_PERSON"; "SCENERY" -> "ANIME_SCENERY"; else -> "ANIME_OTHER" }
    "RENDERED", "DESIGN", "MIXED" -> medium
    else -> "UNKNOWN"
}

fun regionUnion(regions: List<SafeRegion>): SafeRegion {
    require(regions.isNotEmpty())
    val x = regions.minOf { it.x }; val y = regions.minOf { it.y }
    return SafeRegion(x, y, regions.maxOf { it.x + it.width } - x, regions.maxOf { it.y + it.height } - y)
}

fun regionOverlap(a: SafeRegion, b: SafeRegion): Float {
    val intersection = (minOf(a.x+a.width,b.x+b.width)-maxOf(a.x,b.x)).coerceAtLeast(0f) *
        (minOf(a.y+a.height,b.y+b.height)-maxOf(a.y,b.y)).coerceAtLeast(0f)
    return intersection / minOf(a.width*a.height,b.width*b.height).coerceAtLeast(.000001f)
}

fun subjectAgreement(a: SafeRegion, b: SafeRegion, width: Int, height: Int): Boolean {
    val distance = hypot(((a.x+a.width/2)-(b.x+b.width/2))*width.toDouble(),
        ((a.y+a.height/2)-(b.y+b.height/2))*height.toDouble()) / hypot(width.toDouble(),height.toDouble()).coerceAtLeast(1.0)
    return regionOverlap(a,b) >= .5f && distance <= .05 + 1e-7
}

/** Re-read the manual layer at commit time rather than copying the request snapshot. */
fun preserveManual(fresh: PhotoMetadata, latest: PhotoMetadata) = fresh.copy(
    manualX=latest.manualX, manualY=latest.manualY, manualCategory=latest.manualCategory,
    manualPresentation=latest.manualPresentation, customMotionMode=latest.customMotionMode,
    generation=latest.generation,
)
