package io.lightknight.omniread.ui.reader

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.lightknight.omniread.data.model.PageTurnEffect
import kotlin.math.absoluteValue

fun Modifier.pageTurnTransition(
    pagerState: PagerState,
    page: Int,
    effect: PageTurnEffect
): Modifier {
    if (effect == PageTurnEffect.SLIDE) return this

    return this
        .graphicsLayer {
            val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absOffset = offset.absoluteValue.coerceIn(0f, 1f)

            when (effect) {
                PageTurnEffect.CURL_3D -> {
                    cameraDistance = 14f * density
                    if (offset > 0f) {
                        // Outgoing page on the left folds away around its left spine
                        translationX = offset * size.width * 0.35f
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = (-85f * absOffset).coerceAtLeast(-85f)
                        alpha = (1f - absOffset * 0.55f).coerceIn(0f, 1f)
                    } else {
                        // Incoming page on the right turns into view from right edge
                        translationX = offset * size.width * 0.35f
                        transformOrigin = TransformOrigin(1f, 0.5f)
                        rotationY = (65f * absOffset).coerceAtMost(65f)
                        alpha = (1f - absOffset * 0.45f).coerceIn(0f, 1f)
                    }
                }

                PageTurnEffect.COVER_FLIP -> {
                    if (offset > 0f) {
                        // Outgoing page slides cleanly to the left over the stationary next page
                        translationX = 0f
                        alpha = 1f
                    } else {
                        // Incoming page stays underneath until revealed
                        translationX = offset * size.width
                        val scale = 0.94f + (1f - absOffset) * 0.06f
                        scaleX = scale
                        scaleY = scale
                        alpha = (0.45f + (1f - absOffset) * 0.55f).coerceIn(0f, 1f)
                    }
                }

                PageTurnEffect.FADE -> {
                    translationX = offset * size.width
                    alpha = (1f - absOffset).coerceIn(0f, 1f)
                }

                PageTurnEffect.SLIDE -> Unit
            }
        }
        .drawWithContent {
            drawContent()
            val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absOffset = offset.absoluteValue.coerceIn(0f, 1f)
            if (absOffset > 0.01f && (effect == PageTurnEffect.CURL_3D || effect == PageTurnEffect.COVER_FLIP)) {
                val shadowAlpha = (absOffset * 0.36f).coerceIn(0f, 0.4f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = shadowAlpha * 0.5f),
                            Color.Black.copy(alpha = shadowAlpha)
                        )
                    )
                )
            }
        }
}

/**
 * Splits chapter paragraphs into balanced screen-sized pages while retaining each paragraph's
 * original index so TTS highlighting and reading-progress tracking remain exact.
 */
fun paginateParagraphs(
    paragraphs: List<String>,
    fontSizeSp: Float,
    lineHeightMultiplier: Float
): List<List<Pair<Int, String>>> {
    if (paragraphs.isEmpty()) return emptyList()
    val scaleFactor = (18f / fontSizeSp.coerceIn(12f, 34f)) * (1.55f / lineHeightMultiplier.coerceIn(1.1f, 2.2f))
    val charBudget = (950f * scaleFactor).toInt().coerceIn(320, 1800)

    val pages = mutableListOf<List<Pair<Int, String>>>()
    var current = mutableListOf<Pair<Int, String>>()
    var currentChars = 0

    paragraphs.forEachIndexed { idx, para ->
        val cost = para.length + 65 // account for paragraph spacing
        if (current.isNotEmpty() && currentChars + cost > charBudget) {
            pages += current
            current = mutableListOf()
            currentChars = 0
        }
        current += idx to para
        currentChars += cost
    }
    if (current.isNotEmpty()) {
        pages += current
    }
    return pages
}
