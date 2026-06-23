package com.kline.trainer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 透明覆盖层：绘制可拖动水平分析线
 * 坐标系与下方 CombinedChart 的 Y 轴对齐，由外部传入映射函数
 */
class DraggableLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class DragLine(
        var priceValue: Double,   // 真实价格（用于外部读取）
        var yPixel: Float,        // 当前像素位置
        var id: Int               // 唯一ID
    )

    private val lines = mutableListOf<DragLine>()
    private var nextId = 0

    // Y轴映射：像素 <-> 价格
    private var yMin = 0.0
    private var yMax = 1.0
    private var chartTop = 0f
    private var chartBottom = 0f

    // 拖动状态
    private var draggingLine: DragLine? = null
    private val TOUCH_SLOP = 28f   // 触摸判定范围（像素）

    // 画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E67E22")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E67E22")
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val touchHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80E67E22")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    // 点击回调
    var onLineClicked: ((price: Double, lineId: Int) -> Unit)? = null

    // ── 公开 API ──────────────────────────────────────────────────────────

    fun updateYRange(yMin: Double, yMax: Double, chartTop: Float, chartBottom: Float) {
        this.yMin = yMin; this.yMax = yMax
        this.chartTop = chartTop; this.chartBottom = chartBottom
        // 重新计算所有线的像素位置
        lines.forEach { it.yPixel = priceToY(it.priceValue) }
        invalidate()
    }

    fun addLine(price: Double): Int {
        val id = nextId++
        lines.add(DragLine(price, priceToY(price), id))
        invalidate()
        return id
    }

    fun removeLine(id: Int) {
        lines.removeAll { it.id == id }
        invalidate()
    }

    fun clearAll() { lines.clear(); nextId = 0; invalidate() }

    fun getLines(): List<Pair<Int, Double>> = lines.map { it.id to it.priceValue }

    fun setLines(data: List<Pair<Int, Double>>) {
        lines.clear()
        data.forEach { (id, price) ->
            lines.add(DragLine(price, priceToY(price), id))
            if (id >= nextId) nextId = id + 1
        }
        invalidate()
    }

    // ── 坐标转换 ──────────────────────────────────────────────────────────

    private fun priceToY(price: Double): Float {
        if (yMax == yMin) return (chartTop + chartBottom) / 2
        val ratio = (yMax - price) / (yMax - yMin)
        return chartTop + ratio.toFloat() * (chartBottom - chartTop)
    }

    private fun yToPrice(y: Float): Double {
        if (chartBottom == chartTop) return yMin
        val ratio = (y - chartTop) / (chartBottom - chartTop)
        return yMax - ratio * (yMax - yMin)
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        lines.forEach { line ->
            val y = line.yPixel
            if (y < chartTop - 2 || y > chartBottom + 2) return@forEach

            // 实线
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)

            // 价格标签（右侧）
            val label = " ${"%.2f".format(line.priceValue)} "
            val textWidth = labelTextPaint.measureText(label)
            val labelH = 40f
            val labelX = width - textWidth - 8f
            val rect = RectF(labelX - 4, y - labelH / 2, labelX + textWidth + 4, y + labelH / 2)
            canvas.drawRoundRect(rect, 6f, 6f, labelBgPaint)
            canvas.drawText(label, labelX, y + labelTextPaint.textSize / 3, labelTextPaint)

            // 左侧小圆点（拖动把手提示）
            canvas.drawCircle(24f, y, 12f, labelBgPaint)
            canvas.drawCircle(24f, y, 12f, linePaint.apply { strokeWidth = 2f })
            linePaint.strokeWidth = 3f
        }
    }

    // ── 触摸处理 ──────────────────────────────────────────────────────────

    private var touchStartY = 0f
    private var didDrag = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                didDrag = false
                draggingLine = lines.minByOrNull { abs(it.yPixel - event.y) }
                    ?.takeIf { abs(it.yPixel - event.y) < TOUCH_SLOP }
                return draggingLine != null
            }
            MotionEvent.ACTION_MOVE -> {
                draggingLine?.let { line ->
                    if (abs(event.y - touchStartY) > 4f) didDrag = true
                    val clampedY = event.y.coerceIn(chartTop, chartBottom)
                    line.yPixel = clampedY
                    line.priceValue = yToPrice(clampedY)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val line = draggingLine
                if (line != null && !didDrag) {
                    // 点击（未拖动）→ 触发回调
                    onLineClicked?.invoke(line.priceValue, line.id)
                }
                draggingLine = null
                return true
            }
        }
        return false
    }
}
