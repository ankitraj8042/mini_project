package com.yourapp.webrtcapp.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Simple Graph View for displaying line charts
 * Shows network statistics over time
 */
class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<Float> = emptyList()
    private var label: String = ""
    private var lineColor: Int = Color.parseColor("#4CAF50")
    
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 32f
        isAntiAlias = true
    }
    
    private val gridPaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    
    private val axisPaint = Paint().apply {
        color = Color.GRAY
        textSize = 24f
        isAntiAlias = true
    }

    fun setData(newData: List<Float>, newLabel: String, color: Int) {
        data = newData
        label = newLabel
        lineColor = color
        linePaint.color = color
        fillPaint.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (data.isEmpty()) {
            canvas.drawText("No data", width / 2f - 40f, height / 2f, textPaint)
            return
        }

        val padding = 60f
        val graphWidth = width - padding * 2
        val graphHeight = height - padding * 2
        
        // Draw background
        canvas.drawColor(Color.WHITE)
        
        // Draw label
        canvas.drawText(label, padding, 40f, textPaint)
        
        // Calculate data bounds
        val maxVal = data.maxOrNull() ?: 1f
        val minVal = data.minOrNull() ?: 0f
        val range = if (maxVal == minVal) 1f else maxVal - minVal
        
        // Draw grid lines (horizontal)
        for (i in 0..4) {
            val y = padding + graphHeight * i / 4
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
            
            // Y-axis labels
            val value = maxVal - (range * i / 4)
            canvas.drawText("${value.toInt()}", 5f, y + 8f, axisPaint)
        }
        
        // Draw the data line and fill
        if (data.size > 1) {
            val path = Path()
            val fillPath = Path()
            
            val stepX = graphWidth / (data.size - 1)
            
            // Move to first point
            val firstX = padding
            val firstY = padding + graphHeight * (1 - (data[0] - minVal) / range)
            path.moveTo(firstX, firstY)
            fillPath.moveTo(firstX, padding + graphHeight)
            fillPath.lineTo(firstX, firstY)
            
            // Draw lines to each subsequent point
            for (i in 1 until data.size) {
                val x = padding + stepX * i
                val y = padding + graphHeight * (1 - (data[i] - minVal) / range)
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            
            // Complete fill path
            val lastX = padding + stepX * (data.size - 1)
            fillPath.lineTo(lastX, padding + graphHeight)
            fillPath.close()
            
            // Draw fill first, then line
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, linePaint)
        }
        
        // Draw current/last value
        val lastValue = data.lastOrNull() ?: 0f
        val avgValue = data.average()
        val statsText = "Current: ${lastValue.toInt()} | Avg: ${"%.1f".format(avgValue)}"
        canvas.drawText(statsText, padding, height - 10f, axisPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = 200
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
