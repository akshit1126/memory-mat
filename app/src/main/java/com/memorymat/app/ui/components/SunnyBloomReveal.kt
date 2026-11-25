package com.memorymat.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.memorymat.app.ui.theme.CuttingMatGreenBase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Material 3 Expressive launch reveal that seamlessly hands off from Android's system splash
 * screen: [MaterialShapes.VerySunny] performs a springy anticipation spin, then blooms outward
 * as a rotating sunny-shaped aperture unveiling the live cutting mat underneath.
 *
 * Unmounts its overlay completely upon completion for zero steady-state overhead.
 */
@Composable
fun SunnyBloomRevealContainer(
    readyToReveal: Boolean,
    initialIconDiameterPx: Float?,
    modifier: Modifier = Modifier,
    skipReveal: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val animationsEnabled = remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale > 0f
    }

    var revealFinished by remember { mutableStateOf(skipReveal || !animationsEnabled) }
    var bloomStarted by remember { mutableStateOf(false) }
    var fallbackReady by remember { mutableStateOf(false) }

    // Safety net: if OS skipped splash handoff on a warm launch, still proceed after a brief moment
    LaunchedEffect(readyToReveal, revealFinished) {
        if (!readyToReveal && !revealFinished) {
            delay(350L)
            fallbackReady = true
        }
    }
    val effectiveReady = readyToReveal || fallbackReady

    val motionScheme = MaterialTheme.motionScheme
    val sunnyScale = remember { Animatable(1f) }
    val sunnyRotation = remember { Animatable(0f) }
    val sunnyMorphProgress = remember { Animatable(0f) }
    val emblemFillAlpha = remember { Animatable(1f) }
    val bloomRimAlpha = remember { Animatable(0f) }
    val contentScale = remember { Animatable(if (!skipReveal && animationsEnabled) 0.965f else 1f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        // Default matches Android 12+ 288dp splash icon container * (54/108) VerySunny ratio = 144dp
        val baseDiameterPx = remember(initialIconDiameterPx, density) {
            initialIconDiameterPx?.takeIf { it > 0f } ?: with(density) { 144.dp.toPx() }
        }

        // Factor needed for the sunny shape's inner valleys to clear all four screen corners
        val screenDiagonal = hypot(widthPx, heightPx)
        val fullRevealScale = (screenDiagonal / baseDiameterPx) * 1.45f

        LaunchedEffect(effectiveReady, animationsEnabled) {
            if (!effectiveReady || revealFinished) return@LaunchedEffect

            val fastSpatial = motionScheme.fastSpatialSpec<Float>()
            val slowSpatial = motionScheme.slowSpatialSpec<Float>()
            val fastEffects = motionScheme.fastEffectsSpec<Float>()
            val defaultEffects = motionScheme.defaultEffectsSpec<Float>()

            // Beat 1: Expressive anticipation — compact spring dip + crisp 45° sunny turn
            val anticipationScaleJob = launch {
                sunnyScale.animateTo(0.84f, animationSpec = fastSpatial)
            }
            val anticipationSpinJob = launch {
                sunnyRotation.animateTo(45f, animationSpec = fastSpatial)
            }
            delay(180L)
            anticipationScaleJob.cancel()
            anticipationSpinJob.cancel()

            // Beat 2: Sunny aperture blooms open across the cutting mat
            bloomStarted = true

            val bloomScaleJob = launch {
                sunnyScale.animateTo(fullRevealScale, animationSpec = slowSpatial)
            }
            val bloomSpinJob = launch {
                sunnyRotation.animateTo(125f, animationSpec = slowSpatial)
            }
            val bloomMorphJob = launch {
                sunnyMorphProgress.animateTo(0.28f, animationSpec = slowSpatial)
            }
            val emblemFadeJob = launch {
                emblemFillAlpha.animateTo(0f, animationSpec = fastEffects)
            }
            val rimJob = launch {
                bloomRimAlpha.animateTo(0.85f, animationSpec = fastEffects)
                delay(140L)
                bloomRimAlpha.animateTo(0f, animationSpec = defaultEffects)
            }
            val matSettleJob = launch {
                contentScale.animateTo(1f, animationSpec = slowSpatial)
            }

            bloomScaleJob.join()
            bloomSpinJob.join()
            bloomMorphJob.join()
            emblemFadeJob.join()
            rimJob.join()
            matSettleJob.join()

            revealFinished = true
        }

        // Underlying home screen settles softly into place as the sunny mask sweeps open
        Box(
            modifier = if (revealFinished) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = contentScale.value
                        scaleX = s
                        scaleY = s
                    }
            },
        ) {
            content()
        }

        if (!revealFinished) {
            val morph = remember {
                Morph(
                    start = MaterialShapes.VerySunny,
                    end = MaterialShapes.Circle,
                )
            }
            val reusablePath = remember { Path() }
            val reusableMatrix = remember { Matrix() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(),
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val diameter = baseDiameterPx * sunnyScale.value

                reusablePath.reset()
                morph.toPath(
                    progress = sunnyMorphProgress.value.coerceIn(0f, 1f),
                    path = reusablePath,
                )

                // Transform normalized [0..1] MaterialShapes polygon around center (cx, cy)
                reusableMatrix.reset()
                reusableMatrix.translate(cx, cy)
                reusableMatrix.rotateZ(sunnyRotation.value)
                reusableMatrix.scale(diameter, diameter)
                reusableMatrix.translate(-0.5f, -0.5f)
                reusablePath.transform(reusableMatrix)

                if (!bloomStarted) {
                    // Solid splash cover matching OS splash background + crisp white VerySunny
                    drawRect(color = CuttingMatGreenBase, size = size)
                    drawPath(path = reusablePath, color = Color.White)
                } else {
                    // Cut a blooming rotating sunny window right out of the splash cover
                    clipPath(path = reusablePath, clipOp = ClipOp.Difference) {
                        drawRect(color = CuttingMatGreenBase, size = size)
                    }

                    val fillAlpha = emblemFillAlpha.value
                    if (fillAlpha > 0.005f) {
                        drawPath(
                            path = reusablePath,
                            color = Color.White.copy(alpha = fillAlpha),
                        )
                    }

                    val rimAlpha = bloomRimAlpha.value
                    if (rimAlpha > 0.005f) {
                        drawPath(
                            path = reusablePath,
                            color = Color.White.copy(alpha = rimAlpha),
                            style = Stroke(width = 2.5.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}
