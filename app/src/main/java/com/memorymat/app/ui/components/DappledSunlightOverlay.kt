package com.memorymat.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight GPU wind-sway, device-tilt parallax & natural tree-canopy shadow compositor (AGSL, API 33+).
 * Samples two pre-baked procedural recursive tree-branch & clustered-leaf masks (far high canopy
 * + closer woody boughs) with coherent limb sway, multi-depth tilt parallax, and warm-sun / cool-shade blending.
 */
private const val DAPPLED_SUNLIGHT_AGSL = """
    uniform shader farMask;
    uniform shader nearMask;
    uniform float2 resolution;
    uniform float2 maskSize;
    uniform float time;
    uniform float2 tilt;
    uniform float parallaxStrength;
    uniform float shadowIntensity;
    uniform float sunWarmth;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(resolution, float2(1.0, 1.0));

        // Natural non-repeating breeze envelope (gentle lulls + lively gusts)
        float gust = 0.62 + 0.38 * sin(time * 0.27 + 0.4) * sin(time * 0.16 + 1.3);

        // Coherent limb sway anchored near the left/top trunk edge so branches rock realistically
        // instead of warping like rubber, plus a delicate high-frequency leaf shimmer.
        float limbFlex = smoothstep(0.0, 1.0, uv.x * 0.72 + uv.y * 0.38);

        float farSwayX = sin(time * 0.34 + uv.y * 1.4) * 0.026
                       + cos(time * 0.58 + uv.x * 2.2) * 0.009;
        float farSwayY = cos(time * 0.29 + uv.x * 1.3) * 0.018
                       + sin(time * 0.51 + uv.y * 2.1) * 0.006;
        float2 farOffset = float2(farSwayX, farSwayY) * (0.40 + 0.60 * limbFlex) * gust;

        float nearSwayX = sin(time * 0.52 + uv.y * 1.6 - uv.x * 0.7) * 0.032
                        + sin(time * 1.25 + uv.x * 6.5 + uv.y * 5.2) * 0.0055;
        float nearSwayY = cos(time * 0.44 + uv.x * 1.5 + uv.y * 0.8) * 0.021
                        + cos(time * 1.12 + uv.y * 6.0 - uv.x * 4.8) * 0.0045;
        float2 nearOffset = float2(nearSwayX, nearSwayY) * (0.30 + 0.70 * limbFlex) * gust;

        // Multi-depth tilt parallax: higher canopy shifts more than closer branches for real 3D depth
        float2 farParallax  = tilt * (parallaxStrength * 1.18);
        float2 nearParallax = tilt * (parallaxStrength * 0.52);

        // Overscanned UV inset keeps edges seamless at combined peak wind + tilt displacement
        float2 insetUv = 0.12 + uv * 0.76;
        float2 farCoord  = clamp(insetUv + farOffset + farParallax,   0.004, 0.996) * maskSize;
        float2 nearCoord = clamp(insetUv + nearOffset + nearParallax, 0.004, 0.996) * maskSize;

        float farShadow  = farMask.eval(farCoord).a;
        float nearShadow = nearMask.eval(nearCoord).a;

        // Layer deeper closer branches over softer high canopy so overlaps darken naturally
        float combined = clamp(
            farShadow * 0.56 + nearShadow * 0.88 - farShadow * nearShadow * 0.22,
            0.0,
            1.0
        );
        float shadow = smoothstep(0.03, 0.95, combined);

        // Warm afternoon sunlight pooling naturally through gaps, gently reacting to tilt
        float2 sunOrigin = float2(0.75, 0.08) - tilt * 0.18;
        float sunDir = smoothstep(1.25, -0.10, dot(uv - sunOrigin, float2(-0.55, 0.78)));
        float sunMask = pow(1.0 - shadow, 1.30) * (0.74 + 0.26 * sunDir);

        float shadowAlpha = shadow * shadowIntensity;
        float sunAlpha = sunMask * sunWarmth;

        // Cool deep-forest shadow vs luminous warm sunlit bounce
        float3 shadowColor = float3(0.005, 0.075, 0.056);
        float3 sunColor    = float3(0.96, 1.00, 0.78);

        float totalAlpha = clamp(shadowAlpha + sunAlpha, 0.0, 0.86);
        float3 premulRgb = shadowColor * shadowAlpha + sunColor * sunAlpha;
        return half4(premulRgb, totalAlpha);
    }
"""

data class DappledSunlightStyle(
    val shadowIntensity: Float = 0.44f,
    val sunWarmth: Float = 0.28f,
    val windSpeed: Float = 1.25f,
    val parallaxStrength: Float = 0.036f,
    val targetFps: Int = 45,
)

private class TiltSensorHolder {
    @Volatile
    var rawX: Float = 0f

    @Volatile
    var rawY: Float = 0f

    @Volatile
    var hasReading: Boolean = false
}

private class FoliageMaskSet(
    val farBitmap: Bitmap,
    val nearBitmap: Bitmap,
    val width: Int,
    val height: Int,
) {
    fun recycle() {
        if (!farBitmap.isRecycled) farBitmap.recycle()
        if (!nearBitmap.isRecycled) nearBitmap.recycle()
    }
}

@Composable
fun DappledSunlightOverlay(
    modifier: Modifier = Modifier,
    style: DappledSunlightStyle = DappledSunlightStyle(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    // Read only inside Canvas draw phase to guarantee zero Compose recompositions per frame
    val timeSeconds = remember { mutableFloatStateOf(0f) }
    val smoothTiltX = remember { mutableFloatStateOf(0f) }
    val smoothTiltY = remember { mutableFloatStateOf(0f) }
    val tiltHolder = remember { TiltSensorHolder() }

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

    LaunchedEffect(
        lifecycleOwner,
        animationsEnabled,
        style.windSpeed,
        style.parallaxStrength,
        style.targetFps,
    ) {
        if (!animationsEnabled) return@LaunchedEffect
        val minFrameStepNanos = 1_000_000_000L / style.targetFps.coerceIn(10, 60)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val tiltSensor = if (style.parallaxStrength > 0f && sensorManager != null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            null
        }

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Normalized gravity components (~[-1, 1]): +x is tilting left, +y is tilting upright
                tiltHolder.rawX = (event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                tiltHolder.rawY = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                tiltHolder.hasReading = true
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (tiltSensor != null) {
                sensorManager?.registerListener(
                    sensorListener,
                    tiltSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                )
            }
            try {
                var lastFrameNanos = 0L
                var lastEmittedNanos = 0L
                var baselineInitialized = false
                var baselineX = 0f
                var baselineY = 0f

                while (true) {
                    withFrameNanos { frameNanos ->
                        if (lastFrameNanos == 0L) {
                            lastFrameNanos = frameNanos
                            lastEmittedNanos = frameNanos
                            return@withFrameNanos
                        }
                        if (frameNanos - lastEmittedNanos >= minFrameStepNanos) {
                            val dtSec = ((frameNanos - lastFrameNanos) / 1_000_000_000f)
                                .coerceIn(0f, 0.1f)
                            if (style.windSpeed > 0f) {
                                timeSeconds.floatValue += dtSec * style.windSpeed
                            }

                            if (tiltHolder.hasReading) {
                                val rx = tiltHolder.rawX
                                val ry = tiltHolder.rawY
                                if (!baselineInitialized) {
                                    baselineX = rx
                                    baselineY = ry
                                    baselineInitialized = true
                                } else {
                                    // Gently re-center neutral posture over ~4s so any holding angle feels natural
                                    val recenterAlpha = 1f - exp(-dtSec * 0.25f)
                                    baselineX += (rx - baselineX) * recenterAlpha
                                    baselineY += (ry - baselineY) * recenterAlpha
                                }

                                // Scale ~25° tilt to full subtle parallax range [-1, 1]
                                val targetX = ((rx - baselineX) * 2.4f).coerceIn(-1f, 1f)
                                val targetY = ((ry - baselineY) * 2.4f).coerceIn(-1f, 1f)

                                // Smooth critically damped follow so hand micro-tremors never jitter shadows
                                val followAlpha = 1f - exp(-dtSec * 7.5f)
                                smoothTiltX.floatValue += (targetX - smoothTiltX.floatValue) * followAlpha
                                smoothTiltY.floatValue += (targetY - smoothTiltY.floatValue) * followAlpha
                            }

                            lastFrameNanos = frameNanos
                            lastEmittedNanos = frameNanos
                        }
                    }
                }
            } finally {
                if (tiltSensor != null) {
                    sensorManager?.unregisterListener(sensorListener)
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.roundToInt().coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.toPx() }.roundToInt().coerceAtLeast(1)

        // Low-resolution (~1/4) offscreen procedural tree-canopy masks baked once per viewport size
        val baseShortSide = 320
        val maskWidth = if (widthPx <= heightPx) {
            baseShortSide
        } else {
            (baseShortSide * (widthPx.toFloat() / heightPx)).roundToInt().coerceIn(320, 560)
        }
        val maskHeight = if (heightPx >= widthPx) {
            (baseShortSide * (heightPx.toFloat() / widthPx)).roundToInt().coerceIn(320, 740)
        } else {
            baseShortSide
        }

        val masks = remember(maskWidth, maskHeight) {
            bakeProceduralFoliageMasks(width = maskWidth, height = maskHeight)
        }

        DisposableEffect(masks) {
            onDispose { masks.recycle() }
        }

        val shaderResources = remember(masks) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val shader = RuntimeShader(DAPPLED_SUNLIGHT_AGSL)
                val farShader = BitmapShader(
                    masks.farBitmap,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP,
                )
                val nearShader = BitmapShader(
                    masks.nearBitmap,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP,
                )
                shader.setInputShader("farMask", farShader)
                shader.setInputShader("nearMask", nearShader)
                shader.setFloatUniform("maskSize", masks.width.toFloat(), masks.height.toFloat())
                shader
            } else {
                null
            }
        }

        val shaderBrush = remember(shaderResources) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaderResources != null) {
                ShaderBrush(shaderResources)
            } else {
                null
            }
        }

        val fallbackImage = remember(masks) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                masks.nearBitmap.asImageBitmap()
            } else {
                null
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaderResources != null && shaderBrush != null) {
                shaderResources.setFloatUniform("resolution", size.width, size.height)
                shaderResources.setFloatUniform("time", timeSeconds.floatValue)
                shaderResources.setFloatUniform("tilt", smoothTiltX.floatValue, smoothTiltY.floatValue)
                shaderResources.setFloatUniform("parallaxStrength", style.parallaxStrength)
                shaderResources.setFloatUniform("shadowIntensity", style.shadowIntensity)
                shaderResources.setFloatUniform("sunWarmth", style.sunWarmth)
                drawRect(brush = shaderBrush, size = size)
            } else if (fallbackImage != null) {
                drawImage(
                    image = fallbackImage,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    alpha = style.shadowIntensity * 0.65f,
                )
            }
        }
    }
}

/**
 * Procedurally generates two realistic tree-shadow masks (recursive gnarled branches + hundreds
 * of tiny clustered leaves) adapted dynamically to any viewport size:
 * 1) Far canopy: higher branches with softer penumbra blur creating lacy dappled pools of light.
 * 2) Near branches: closer crooked woody boughs and leafy twigs with crisper branch definition.
 */
private fun bakeProceduralFoliageMasks(width: Int, height: Int): FoliageMaskSet {
    val shortSide = min(width, height).toFloat()

    // --- Layer 1: High distant canopy (softer, diffused lacy foliage & twigs) ---
    val farBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val farCanvas = AndroidCanvas(farBitmap)
    val farBlur = max(4f, shortSide * 0.024f)
    val farBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(farBlur, BlurMaskFilter.Blur.NORMAL)
    }
    val farLeafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(farBlur * 1.15f, BlurMaskFilter.Blur.NORMAL)
    }

    val farRng = Random(4207)
    // Broad upper-left/top diffuse canopy
    drawOrganicTreeBranch(
        canvas = farCanvas,
        branchPaint = farBranchPaint,
        leafPaint = farLeafPaint,
        startX = -width * 0.02f,
        startY = height * 0.16f,
        angleRad = -0.20f,
        length = shortSide * 0.62f,
        thickness = shortSide * 0.022f,
        depth = 4,
        shortSide = shortSide,
        leafDensity = 1.25f,
        rng = farRng,
    )
    // Soft right-mid dappled canopy
    drawOrganicTreeBranch(
        canvas = farCanvas,
        branchPaint = farBranchPaint,
        leafPaint = farLeafPaint,
        startX = width * 1.04f,
        startY = height * 0.34f,
        angleRad = (PI - 0.18).toFloat(),
        length = shortSide * 0.56f,
        thickness = shortSide * 0.019f,
        depth = 4,
        shortSide = shortSide,
        leafDensity = 1.15f,
        rng = farRng,
    )
    // Soft lower-left canopy depth
    drawOrganicTreeBranch(
        canvas = farCanvas,
        branchPaint = farBranchPaint,
        leafPaint = farLeafPaint,
        startX = width * 0.04f,
        startY = height * 0.96f,
        angleRad = -0.68f,
        length = shortSide * 0.58f,
        thickness = shortSide * 0.024f,
        depth = 4,
        shortSide = shortSide,
        leafDensity = 1.20f,
        rng = farRng,
    )

    // --- Layer 2: Closer overhanging tree boughs & leafy twigs (crisper woody silhouette) ---
    val nearBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val nearCanvas = AndroidCanvas(nearBitmap)
    val nearBlur = max(2.2f, shortSide * 0.011f)
    val nearBranchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(nearBlur, BlurMaskFilter.Blur.NORMAL)
    }
    val nearLeafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(nearBlur * 1.25f, BlurMaskFilter.Blur.NORMAL)
    }

    val nearRng = Random(1337)

    // Hero Bough 1: Iconic upper-left tree limb sweeping diagonally up-right & forking across top
    drawOrganicTreeBranch(
        canvas = nearCanvas,
        branchPaint = nearBranchPaint,
        leafPaint = nearLeafPaint,
        startX = -width * 0.05f,
        startY = height * 0.30f,
        angleRad = -0.52f,
        length = shortSide * 0.56f,
        thickness = shortSide * 0.024f,
        depth = 4,
        shortSide = shortSide,
        leafDensity = 1.10f,
        rng = nearRng,
    )

    // Hero Bough 2: Secondary fork sweeping gently across upper-middle with open sunlit pockets
    drawOrganicTreeBranch(
        canvas = nearCanvas,
        branchPaint = nearBranchPaint,
        leafPaint = nearLeafPaint,
        startX = -width * 0.05f,
        startY = height * 0.44f,
        angleRad = -0.14f,
        length = shortSide * 0.52f,
        thickness = shortSide * 0.019f,
        depth = 4,
        shortSide = shortSide,
        leafDensity = 0.95f,
        rng = nearRng,
    )

    // Bough 3: Diagonal limb reaching in from the right-lower side (mirrors reference right limb)
    drawOrganicTreeBranch(
        canvas = nearCanvas,
        branchPaint = nearBranchPaint,
        leafPaint = nearLeafPaint,
        startX = width * 1.05f,
        startY = height * 0.56f,
        angleRad = (PI * 0.74).toFloat(),
        length = shortSide * 0.50f,
        thickness = shortSide * 0.018f,
        depth = 4,
        shortSide = shortSide,
        leafDensity = 0.90f,
        rng = nearRng,
    )

    // Bough 4: Grounded lower-left branch & leafy clusters
    drawOrganicTreeBranch(
        canvas = nearCanvas,
        branchPaint = nearBranchPaint,
        leafPaint = nearLeafPaint,
        startX = -width * 0.03f,
        startY = height * 0.84f,
        angleRad = -0.34f,
        length = shortSide * 0.46f,
        thickness = shortSide * 0.020f,
        depth = 3,
        shortSide = shortSide,
        leafDensity = 1.15f,
        rng = nearRng,
    )

    // Bough 5: Light upper-right canopy fringe leaving glowing sunlit clearings below
    drawOrganicTreeBranch(
        canvas = nearCanvas,
        branchPaint = nearBranchPaint,
        leafPaint = nearLeafPaint,
        startX = width * 0.92f,
        startY = -height * 0.03f,
        angleRad = (PI * 0.68).toFloat(),
        length = shortSide * 0.44f,
        thickness = shortSide * 0.016f,
        depth = 3,
        shortSide = shortSide,
        leafDensity = 1.05f,
        rng = nearRng,
    )

    return FoliageMaskSet(
        farBitmap = farBitmap,
        nearBitmap = nearBitmap,
        width = width,
        height = height,
    )
}

/**
 * Draws a gnarled, tapered woody branch recursively splitting into organic sub-branches and twigs,
 * scattering natural clusters of tiny irregular leaves on the fine outer shoots.
 */
private fun drawOrganicTreeBranch(
    canvas: AndroidCanvas,
    branchPaint: Paint,
    leafPaint: Paint,
    startX: Float,
    startY: Float,
    angleRad: Float,
    length: Float,
    thickness: Float,
    depth: Int,
    shortSide: Float,
    leafDensity: Float,
    rng: Random,
) {
    if (depth < 0 || length < shortSide * 0.025f) return

    val segments = if (depth >= 2) 4 else 3
    val segLen = length / segments
    var cx = startX
    var cy = startY
    var heading = angleRad
    var curWidth = thickness

    for (s in 0 until segments) {
        // Subtle organic crook per wood segment
        heading += (rng.nextFloat() - 0.5f) * (if (depth >= 2) 0.22f else 0.30f)
        val nx = cx + cos(heading) * segLen
        val ny = cy + sin(heading) * segLen

        branchPaint.strokeWidth = max(0.8f, curWidth)
        branchPaint.alpha = (185 + depth * 16).coerceAtMost(245)
        canvas.drawLine(cx, cy, nx, ny, branchPaint)

        // Spawn lateral sub-branches mid-way along main & secondary limbs
        if (depth >= 1 && s >= 1 && s < segments - 1 && rng.nextFloat() < 0.78f) {
            val side = if (rng.nextBoolean()) 1f else -1f
            val forkAngle = heading + side * (0.42f + rng.nextFloat() * 0.36f)
            drawOrganicTreeBranch(
                canvas = canvas,
                branchPaint = branchPaint,
                leafPaint = leafPaint,
                startX = nx,
                startY = ny,
                angleRad = forkAngle,
                length = length * (0.52f + rng.nextFloat() * 0.18f),
                thickness = curWidth * 0.62f,
                depth = depth - 1,
                shortSide = shortSide,
                leafDensity = leafDensity,
                rng = rng,
            )
        }

        // Scatter natural tiny leaf clusters along outer twigs (never bare thick trunks)
        if (depth <= 2) {
            val count = ((if (depth == 0) 6 else if (depth == 1) 4 else 2) * leafDensity)
                .roundToInt()
                .coerceAtLeast(1)
            drawTwigLeafCluster(
                canvas = canvas,
                leafPaint = leafPaint,
                centerX = nx,
                centerY = ny,
                twigAngleRad = heading,
                count = count,
                spreadRadius = shortSide * (if (depth == 0) 0.042f else 0.032f),
                shortSide = shortSide,
                rng = rng,
            )
        }

        cx = nx
        cy = ny
        curWidth *= 0.84f
    }

    // Fork at branch tip into 2 asymmetric continuing shoots
    if (depth > 0) {
        val leftSpread = 0.26f + rng.nextFloat() * 0.34f
        val rightSpread = 0.24f + rng.nextFloat() * 0.38f

        drawOrganicTreeBranch(
            canvas = canvas,
            branchPaint = branchPaint,
            leafPaint = leafPaint,
            startX = cx,
            startY = cy,
            angleRad = heading - leftSpread,
            length = length * (0.64f + rng.nextFloat() * 0.16f),
            thickness = curWidth * 0.72f,
            depth = depth - 1,
            shortSide = shortSide,
            leafDensity = leafDensity,
            rng = rng,
        )

        drawOrganicTreeBranch(
            canvas = canvas,
            branchPaint = branchPaint,
            leafPaint = leafPaint,
            startX = cx,
            startY = cy,
            angleRad = heading + rightSpread,
            length = length * (0.58f + rng.nextFloat() * 0.18f),
            thickness = curWidth * 0.66f,
            depth = depth - 1,
            shortSide = shortSide,
            leafDensity = leafDensity,
            rng = rng,
        )
    } else {
        // Dense airy cluster of tiny leaves right at fine twig tips
        val tipLeaves = (7 * leafDensity).roundToInt().coerceAtLeast(3)
        drawTwigLeafCluster(
            canvas = canvas,
            leafPaint = leafPaint,
            centerX = cx,
            centerY = cy,
            twigAngleRad = heading,
            count = tipLeaves,
            spreadRadius = shortSide * 0.048f,
            shortSide = shortSide,
            rng = rng,
        )
    }
}

private fun drawTwigLeafCluster(
    canvas: AndroidCanvas,
    leafPaint: Paint,
    centerX: Float,
    centerY: Float,
    twigAngleRad: Float,
    count: Int,
    spreadRadius: Float,
    shortSide: Float,
    rng: Random,
) {
    val leafPath = Path()
    for (i in 0 until count) {
        val offsetAngle = rng.nextFloat() * (2f * PI.toFloat())
        val dist = spreadRadius * (0.15f + rng.nextFloat() * 0.85f)
        val lx = centerX + cos(offsetAngle) * dist
        val ly = centerY + sin(offsetAngle) * dist

        // Tiny organic almond/oval leaf (1.8% - 3.8% of short side)
        val leafLen = shortSide * (0.018f + rng.nextFloat() * 0.022f)
        val leafWid = leafLen * (0.46f + rng.nextFloat() * 0.24f)
        val leafAngleDeg = ((twigAngleRad + (rng.nextFloat() - 0.5f) * 2.4f) * 180.0 / PI).toFloat()

        leafPaint.alpha = 135 + rng.nextInt(105)

        leafPath.reset()
        leafPath.moveTo(0f, 0f)
        leafPath.cubicTo(
            leafLen * 0.28f, -leafWid * 0.68f,
            leafLen * 0.72f, -leafWid * 0.54f,
            leafLen, 0f,
        )
        leafPath.cubicTo(
            leafLen * 0.72f, leafWid * 0.54f,
            leafLen * 0.28f, leafWid * 0.68f,
            0f, 0f,
        )
        leafPath.close()

        canvas.save()
        canvas.translate(lx, ly)
        canvas.rotate(leafAngleDeg)
        canvas.drawPath(leafPath, leafPaint)
        canvas.restore()
    }
}
