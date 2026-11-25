package com.memorymat.app.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorymat.app.ui.theme.CuttingMatGreenBase
import com.memorymat.app.ui.theme.CuttingMatInk
import com.memorymat.app.ui.theme.GoogleSansFontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Solid cutting mat surface with a prominent tactile grain overlay.
 */
private const val FINE_GRAIN_MAT_AGSL = """
    uniform float3 baseColor;
    uniform float grainStrength;
    uniform float grainScalePx;

    float hash12(float2 p) {
        float3 p3 = fract(float3(p.xyx) * 0.1031);
        p3 += dot(p3, p3.yzx + 33.33);
        return fract((p3.x + p3.y) * p3.z);
    }

    float valueNoise(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        float2 u = f * f * (3.0 - 2.0 * f);
        float a = hash12(i);
        float b = hash12(i + float2(1.0, 0.0));
        float c = hash12(i + float2(0.0, 1.0));
        float d = hash12(i + float2(1.0, 1.0));
        return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
    }

    half4 main(float2 fragCoord) {
        float scale = max(grainScalePx, 1.0);
        float coarse = valueNoise(fragCoord / scale) - 0.5;
        float fine = valueNoise(fragCoord / (scale * 0.52) + float2(19.7, 43.1)) - 0.5;
        float grain = (coarse * 0.65 + fine * 0.35) * grainStrength;
        float3 color = clamp(baseColor + float3(grain), 0.0, 1.0);
        return half4(color, 1.0);
    }
"""

private data class CuttingMatGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
    val columns: Int,
    val rows: Int,
    val cellSize: Float,
)

data class CuttingMatStyle(
    val matColor: Color = CuttingMatGreenBase,
    val grainStrength: Float = 0.18f,
    val grainSize: Dp = 0.5.dp,
    val targetMajorCellSize: Dp = 68.dp,
    val subdivisionsPerBlock: Int = 5,
    val sideScaleWidth: Dp = 32.dp,
    val topBottomScaleHeight: Dp = 18.dp,
    val outerBorderColor: Color = CuttingMatInk.copy(alpha = 0.88f),
    val outerBorderStroke: Dp = 1.25.dp,
    val majorLineColor: Color = CuttingMatInk.copy(alpha = 0.64f),
    val majorLineStroke: Dp = 1.0.dp,
    val minorLineColor: Color = CuttingMatInk.copy(alpha = 0.16f),
    val minorLineStroke: Dp = 0.6.dp,
    val diagonalPrimaryColor: Color = CuttingMatInk.copy(alpha = 0.22f),
    val diagonalPrimaryStroke: Dp = 0.85.dp,
    val angleGuideColor: Color = CuttingMatInk.copy(alpha = 0.13f),
    val angleGuideStroke: Dp = 0.65.dp,
    val angleLabelColor: Color = CuttingMatInk.copy(alpha = 0.28f),
    val plusMarkColor: Color = CuttingMatInk.copy(alpha = 0.85f),
    val plusMarkHalfLength: Dp = 3.6.dp,
    val plusMarkStroke: Dp = 1.3.dp,
    val scaleMajorTickColor: Color = CuttingMatInk.copy(alpha = 0.80f),
    val scaleMinorTickColor: Color = CuttingMatInk.copy(alpha = 0.36f),
    val scaleLabelColor: Color = CuttingMatInk.copy(alpha = 0.68f),
)

@Composable
fun CuttingMatBackground(
    modifier: Modifier = Modifier,
    style: CuttingMatStyle = CuttingMatStyle(),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing
    val textMeasurer = rememberTextMeasurer()

    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(FINE_GRAIN_MAT_AGSL)
        } else {
            null
        }
    }

    val labelStyle = remember(style.scaleLabelColor) {
        TextStyle(
            color = style.scaleLabelColor,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = GoogleSansFontFamily,
            fontFeatureSettings = "tnum",
        )
    }

    val angleLabelStyle = remember(style.angleLabelColor) {
        TextStyle(
            color = style.angleLabelColor,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = GoogleSansFontFamily,
            fontFeatureSettings = "tnum",
        )
    }

    val matLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }

    val insetLeft = safeInsets.getLeft(density, layoutDirection).toFloat()
    val insetRight = safeInsets.getRight(density, layoutDirection).toFloat()
    val insetTop = safeInsets.getTop(density).toFloat()
    val insetBottom = safeInsets.getBottom(density).toFloat()

    Box(modifier = modifier.fillMaxSize()) {
        // Record the static cutting mat once into an offscreen GPU GraphicsLayer so animated
        // overlays above never re-run text measurement, grid lines, or grain shader per frame.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    matLayer.record {
                        drawMatSurface(
                            runtimeShader = runtimeShader,
                            baseColor = style.matColor,
                            grainStrength = style.grainStrength,
                            grainScalePx = style.grainSize.toPx(),
                        )

                        val geometry = computeMatGeometry(
                            canvasWidth = size.width,
                            canvasHeight = size.height,
                            insetLeft = insetLeft,
                            insetRight = insetRight,
                            insetTop = insetTop,
                            insetBottom = insetBottom,
                            sideMargin = style.sideScaleWidth.toPx(),
                            topBottomMargin = style.topBottomScaleHeight.toPx(),
                            targetCellPx = style.targetMajorCellSize.toPx(),
                        )

                        drawMinorGrid(
                            geometry = geometry,
                            subdivisions = style.subdivisionsPerBlock,
                            color = style.minorLineColor,
                            strokeWidth = style.minorLineStroke.toPx(),
                        )

                        drawSubtleDiagonalsAndAngles(
                            geometry = geometry,
                            primaryDiagonalColor = style.diagonalPrimaryColor,
                            primaryDiagonalStroke = style.diagonalPrimaryStroke.toPx(),
                            angleGuideColor = style.angleGuideColor,
                            angleGuideStroke = style.angleGuideStroke.toPx(),
                            textMeasurer = textMeasurer,
                            angleLabelStyle = angleLabelStyle,
                        )

                        drawMajorGrid(
                            geometry = geometry,
                            color = style.majorLineColor,
                            strokeWidth = style.majorLineStroke.toPx(),
                        )

                        drawOuterBorder(
                            geometry = geometry,
                            color = style.outerBorderColor,
                            strokeWidth = style.outerBorderStroke.toPx(),
                        )

                        drawIntersectionPlusMarks(
                            geometry = geometry,
                            color = style.plusMarkColor,
                            halfLength = style.plusMarkHalfLength.toPx(),
                            strokeWidth = style.plusMarkStroke.toPx(),
                        )

                        drawPerimeterScales(
                            geometry = geometry,
                            subdivisions = style.subdivisionsPerBlock,
                            majorTickColor = style.scaleMajorTickColor,
                            minorTickColor = style.scaleMinorTickColor,
                            textMeasurer = textMeasurer,
                            labelStyle = labelStyle,
                        )
                    }

                    onDrawBehind {
                        drawLayer(matLayer)
                    }
                },
        )

        content()
    }
}

private fun computeMatGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    insetLeft: Float,
    insetRight: Float,
    insetTop: Float,
    insetBottom: Float,
    sideMargin: Float,
    topBottomMargin: Float,
    targetCellPx: Float,
): CuttingMatGeometry {
    val availableWidth = (canvasWidth - insetLeft - insetRight - sideMargin * 2f)
        .coerceAtLeast(1f)
    val availableHeight = (canvasHeight - insetTop - insetBottom - topBottomMargin * 2f)
        .coerceAtLeast(1f)

    // Dynamically adapt column & row counts so every block stays a true square across any device size
    val columns = max(3, (availableWidth / targetCellPx).roundToInt())
    val cellSize = availableWidth / columns
    val rows = max(3, floor(availableHeight / cellSize).toInt())

    val gridWidth = columns * cellSize
    val gridHeight = rows * cellSize

    val gridLeft = insetLeft + sideMargin + (availableWidth - gridWidth) / 2f
    val gridTop = insetTop + topBottomMargin + (availableHeight - gridHeight) / 2f

    return CuttingMatGeometry(
        left = gridLeft,
        top = gridTop,
        right = gridLeft + gridWidth,
        bottom = gridTop + gridHeight,
        width = gridWidth,
        height = gridHeight,
        columns = columns,
        rows = rows,
        cellSize = cellSize,
    )
}

private fun DrawScope.drawMatSurface(
    runtimeShader: RuntimeShader?,
    baseColor: Color,
    grainStrength: Float,
    grainScalePx: Float,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
        runtimeShader.setFloatUniform("baseColor", baseColor.red, baseColor.green, baseColor.blue)
        runtimeShader.setFloatUniform("grainStrength", grainStrength)
        runtimeShader.setFloatUniform("grainScalePx", grainScalePx)
        drawRect(brush = ShaderBrush(runtimeShader), size = size)
    } else {
        drawRect(color = baseColor, size = size)
    }
}

private fun DrawScope.drawMinorGrid(
    geometry: CuttingMatGeometry,
    subdivisions: Int,
    color: Color,
    strokeWidth: Float,
) {
    if (subdivisions <= 1) return
    val step = geometry.cellSize / subdivisions
    val totalSubCols = geometry.columns * subdivisions
    val totalSubRows = geometry.rows * subdivisions

    for (i in 1 until totalSubCols) {
        if (i % subdivisions == 0) continue
        val x = geometry.left + i * step
        drawLine(
            color = color,
            start = Offset(x, geometry.top),
            end = Offset(x, geometry.bottom),
            strokeWidth = strokeWidth,
        )
    }

    for (j in 1 until totalSubRows) {
        if (j % subdivisions == 0) continue
        val y = geometry.top + j * step
        drawLine(
            color = color,
            start = Offset(geometry.left, y),
            end = Offset(geometry.right, y),
            strokeWidth = strokeWidth,
        )
    }
}

private fun DrawScope.drawSubtleDiagonalsAndAngles(
    geometry: CuttingMatGeometry,
    primaryDiagonalColor: Color,
    primaryDiagonalStroke: Float,
    angleGuideColor: Color,
    angleGuideStroke: Float,
    textMeasurer: TextMeasurer,
    angleLabelStyle: TextStyle,
) {
    clipRect(
        left = geometry.left,
        top = geometry.top,
        right = geometry.right,
        bottom = geometry.bottom,
    ) {
        val blOrigin = Offset(geometry.left, geometry.bottom)

        // Subtle 30°, 45°, and 60° angle rays from bottom-left corner
        val raySpecs = listOf(
            30.0 to false,
            45.0 to true,
            60.0 to false,
        )
        for ((angleDeg, isPrimary) in raySpecs) {
            val end = rayEndpointInGrid(
                origin = blOrigin,
                angleFromHorizontalUpDeg = angleDeg,
                geometry = geometry,
            )
            drawLine(
                color = if (isPrimary) primaryDiagonalColor else angleGuideColor,
                start = blOrigin,
                end = end,
                strokeWidth = if (isPrimary) primaryDiagonalStroke else angleGuideStroke,
                cap = StrokeCap.Butt,
            )
        }

        // Complementary very subtle 45° bias diagonal from top-left going down-right
        val tlOrigin = Offset(geometry.left, geometry.top)
        val tlEnd = rayEndpointInGrid(
            origin = tlOrigin,
            angleFromHorizontalUpDeg = -45.0,
            geometry = geometry,
        )
        drawLine(
            color = angleGuideColor,
            start = tlOrigin,
            end = tlEnd,
            strokeWidth = angleGuideStroke,
            cap = StrokeCap.Butt,
        )

        // Delicate dynamic quarter-circle protractor arc anchored to bottom-left
        val arcRadius = (geometry.cellSize * 1.8f)
            .coerceAtMost(min(geometry.width, geometry.height) * 0.42f)

        drawArc(
            color = angleGuideColor,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(blOrigin.x - arcRadius, blOrigin.y - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = angleGuideStroke),
        )

        // Fine radial degree ticks along the protractor arc every 15°
        val tickLen = 4.dp.toPx()
        for (deg in 15..75 step 15) {
            val rad = deg * PI / 180.0
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val innerR = arcRadius - tickLen
            val isMajorAngle = deg == 30 || deg == 45 || deg == 60
            drawLine(
                color = if (isMajorAngle) primaryDiagonalColor else angleGuideColor,
                start = Offset(blOrigin.x + innerR * cosA, blOrigin.y - innerR * sinA),
                end = Offset(blOrigin.x + arcRadius * cosA, blOrigin.y - arcRadius * sinA),
                strokeWidth = angleGuideStroke,
                cap = StrokeCap.Butt,
            )

            if (isMajorAngle) {
                val label = "${deg}°"
                val layout = textMeasurer.measure(label, style = angleLabelStyle)
                val labelRadius = arcRadius + 10.dp.toPx()
                val lx = blOrigin.x + labelRadius * cosA - layout.size.width / 2f
                val ly = blOrigin.y - labelRadius * sinA - layout.size.height / 2f
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(lx, ly),
                )
            }
        }
    }
}

private fun rayEndpointInGrid(
    origin: Offset,
    angleFromHorizontalUpDeg: Double,
    geometry: CuttingMatGeometry,
): Offset {
    val rad = angleFromHorizontalUpDeg * PI / 180.0
    val dx = cos(rad).toFloat()
    val dy = -sin(rad).toFloat()

    var tMax = Float.POSITIVE_INFINITY
    if (dx > 1e-5f) {
        tMax = min(tMax, (geometry.right - origin.x) / dx)
    } else if (dx < -1e-5f) {
        tMax = min(tMax, (geometry.left - origin.x) / dx)
    }

    if (dy > 1e-5f) {
        tMax = min(tMax, (geometry.bottom - origin.y) / dy)
    } else if (dy < -1e-5f) {
        tMax = min(tMax, (geometry.top - origin.y) / dy)
    }

    val safeT = if (tMax.isFinite() && tMax > 0f) tMax else 0f
    return Offset(origin.x + dx * safeT, origin.y + dy * safeT)
}

private fun DrawScope.drawMajorGrid(
    geometry: CuttingMatGeometry,
    color: Color,
    strokeWidth: Float,
) {
    for (col in 1 until geometry.columns) {
        val x = geometry.left + col * geometry.cellSize
        drawLine(
            color = color,
            start = Offset(x, geometry.top),
            end = Offset(x, geometry.bottom),
            strokeWidth = strokeWidth,
        )
    }

    for (row in 1 until geometry.rows) {
        val y = geometry.top + row * geometry.cellSize
        drawLine(
            color = color,
            start = Offset(geometry.left, y),
            end = Offset(geometry.right, y),
            strokeWidth = strokeWidth,
        )
    }
}

private fun DrawScope.drawOuterBorder(
    geometry: CuttingMatGeometry,
    color: Color,
    strokeWidth: Float,
) {
    drawRect(
        color = color,
        topLeft = Offset(geometry.left, geometry.top),
        size = Size(geometry.width, geometry.height),
        style = Stroke(width = strokeWidth),
    )
}

private fun DrawScope.drawIntersectionPlusMarks(
    geometry: CuttingMatGeometry,
    color: Color,
    halfLength: Float,
    strokeWidth: Float,
) {
    for (col in 1 until geometry.columns) {
        val cx = geometry.left + col * geometry.cellSize
        for (row in 1 until geometry.rows) {
            val cy = geometry.top + row * geometry.cellSize
            drawLine(
                color = color,
                start = Offset(cx - halfLength, cy),
                end = Offset(cx + halfLength, cy),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Butt,
            )
            drawLine(
                color = color,
                start = Offset(cx, cy - halfLength),
                end = Offset(cx, cy + halfLength),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Butt,
            )
        }
    }
}

private fun DrawScope.drawPerimeterScales(
    geometry: CuttingMatGeometry,
    subdivisions: Int,
    majorTickColor: Color,
    minorTickColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val majorTickLen = 7.dp.toPx()
    val minorTickLen = 3.5.dp.toPx()
    val majorStroke = 1.05.dp.toPx()
    val minorStroke = 0.65.dp.toPx()
    val labelGap = 4.dp.toPx()
    val step = geometry.cellSize / subdivisions

    // Left & right vertical scales connected directly to the outer border
    val totalSubRows = geometry.rows * subdivisions
    for (i in 0..totalSubRows) {
        val y = geometry.top + i * step
        val isMajor = i % subdivisions == 0
        val tickLen = if (isMajor) majorTickLen else minorTickLen
        val tickColor = if (isMajor) majorTickColor else minorTickColor
        val tickStroke = if (isMajor) majorStroke else minorStroke

        drawLine(
            color = tickColor,
            start = Offset(geometry.left, y),
            end = Offset(geometry.left - tickLen, y),
            strokeWidth = tickStroke,
            cap = StrokeCap.Butt,
        )

        drawLine(
            color = tickColor,
            start = Offset(geometry.right, y),
            end = Offset(geometry.right + tickLen, y),
            strokeWidth = tickStroke,
            cap = StrokeCap.Butt,
        )

        if (isMajor && i > 0 && i < totalSubRows) {
            val majorIndex = i / subdivisions
            val textLayout = textMeasurer.measure(majorIndex.toString(), style = labelStyle)
            val textY = y - textLayout.size.height / 2f

            val leftLabelX = geometry.left - majorTickLen - labelGap - textLayout.size.width
            if (leftLabelX >= 2.dp.toPx()) {
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(leftLabelX, textY),
                )
            }

            val rightLabelX = geometry.right + majorTickLen + labelGap
            if (rightLabelX + textLayout.size.width <= size.width - 2.dp.toPx()) {
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(rightLabelX, textY),
                )
            }
        }
    }

    // Top & bottom ruler ticks connected directly to the outer border
    val totalSubCols = geometry.columns * subdivisions
    for (i in 0..totalSubCols) {
        val x = geometry.left + i * step
        val isMajor = i % subdivisions == 0
        val tickLen = if (isMajor) majorTickLen * 0.85f else minorTickLen * 0.85f
        val tickColor = if (isMajor) majorTickColor else minorTickColor
        val tickStroke = if (isMajor) majorStroke else minorStroke

        drawLine(
            color = tickColor,
            start = Offset(x, geometry.top),
            end = Offset(x, geometry.top - tickLen),
            strokeWidth = tickStroke,
            cap = StrokeCap.Butt,
        )

        drawLine(
            color = tickColor,
            start = Offset(x, geometry.bottom),
            end = Offset(x, geometry.bottom + tickLen),
            strokeWidth = tickStroke,
            cap = StrokeCap.Butt,
        )
    }
}
