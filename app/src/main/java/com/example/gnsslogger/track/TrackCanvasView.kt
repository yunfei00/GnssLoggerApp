package com.example.gnsslogger.track

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class TrackCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Layer(
        val name: String,
        val points: List<TrackPoint>,
        val color: Int,
        val visible: Boolean,
    )

    var layers: List<Layer> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(44, 62, 80)
        textSize = dp(13f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 229, 234)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val backgroundPaint = Paint().apply {
        color = Color.rgb(248, 250, 252)
        style = Paint.Style.FILL
    }

    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userScale = (userScale * detector.scaleFactor).coerceIn(0.5f, 30f)
                invalidate()
                return true
            }
        },
    )

    fun resetViewport() {
        userScale = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawGrid(canvas)

        val visibleLayers = layers.filter { it.visible && it.points.isNotEmpty() }
        if (visibleLayers.isEmpty()) {
            val message = "No valid track loaded"
            canvas.drawText(message, (width - textPaint.measureText(message)) / 2f, height / 2f, textPaint)
            return
        }

        val projector = createProjector(visibleLayers)
        visibleLayers.forEach { layer ->
            drawLayer(canvas, layer, projector)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    panX += event.x - lastX
                    panY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun drawLayer(canvas: Canvas, layer: Layer, projector: Projector) {
        if (layer.points.size >= 2) {
            val path = Path()
            layer.points.forEachIndexed { index, point ->
                val screen = projector.toScreen(point)
                if (index == 0) {
                    path.moveTo(screen.x, screen.y)
                } else {
                    path.lineTo(screen.x, screen.y)
                }
            }
            linePaint.color = layer.color
            canvas.drawPath(path, linePaint)
        }

        val first = projector.toScreen(layer.points.first())
        val last = projector.toScreen(layer.points.last())
        drawEndpoint(canvas, first.x, first.y, Color.rgb(22, 163, 74), "S")
        drawEndpoint(canvas, last.x, last.y, Color.rgb(220, 38, 38), "E")
    }

    private fun drawEndpoint(canvas: Canvas, x: Float, y: Float, color: Int, label: String) {
        pointPaint.color = color
        canvas.drawCircle(x, y, dp(6f), pointPaint)
        canvas.drawText(label, x + dp(8f), y - dp(8f), textPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(56f)
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
    }

    private fun createProjector(visibleLayers: List<Layer>): Projector {
        val points = visibleLayers.flatMap { it.points }
        val centerLatRad = Math.toRadians(points.map { it.latitude }.average())
        val projected = points.map { project(it, centerLatRad) }
        val minX = projected.minOf { it.x }
        val maxX = projected.maxOf { it.x }
        val minY = projected.minOf { it.y }
        val maxY = projected.maxOf { it.y }
        val padding = dp(28f)
        val rangeX = max(maxX - minX, 0.000001)
        val rangeY = max(maxY - minY, 0.000001)
        val availableW = max(width - padding * 2, 1f)
        val availableH = max(height - padding * 2, 1f)
        val scale = min(availableW / rangeX, availableH / rangeY)
        return Projector(centerLatRad, minX, minY, scale, padding)
    }

    private fun project(point: TrackPoint, centerLatitudeRad: Double): ProjectedPoint =
        ProjectedPoint(
            x = point.longitude * cos(centerLatitudeRad).coerceAtLeast(0.1),
            y = point.latitude,
        )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private inner class Projector(
        private val centerLatRad: Double,
        private val minX: Double,
        private val minY: Double,
        private val scale: Double,
        private val padding: Float,
    ) {
        fun toScreen(point: TrackPoint): ScreenPoint {
            val projected = project(point, centerLatRad)
            val baseX = padding + ((projected.x - minX) * scale).toFloat()
            val baseY = height - padding - ((projected.y - minY) * scale).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            return ScreenPoint(
                x = cx + (baseX - cx) * userScale + panX,
                y = cy + (baseY - cy) * userScale + panY,
            )
        }
    }

    private data class ProjectedPoint(val x: Double, val y: Double)
    private data class ScreenPoint(val x: Float, val y: Float)
}
