package com.impairedVision.yolo

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

data class Detection(val box: RectF, val score: Float, val cls: Int)

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dets = mutableListOf<Detection>()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 28f
        color = Color.WHITE
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 160
    }

    // where the ImageView actually draws the bitmap
    private val imageRect = RectF(0f, 0f, 0f, 0f)
    private var srcSize = 640f
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.GREEN
    }

    fun setImageInfo(displayRect: RectF, sourceSize: Int) {
        imageRect.set(displayRect)
        srcSize = sourceSize.toFloat()
        invalidate()
    }

    fun setDetections(list: List<Detection>) {
        dets.clear()
        dets.addAll(list)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return

        // debug outline: where the image sits on screen
        canvas.drawRect(imageRect, outline)

        val sx = imageRect.width() / srcSize
        val sy = imageRect.height() / srcSize

        for ((i, d) in dets.withIndex()) {
            val color = Color.rgb((100 + 70*i) % 255, (200 + 90*i) % 255, (50 + 120*i) % 255)
            boxPaint.color = color
            bgPaint.color = color

            val mapped = RectF(
                imageRect.left + d.box.left   * sx,
                imageRect.top  + d.box.top    * sy,
                imageRect.left + d.box.right  * sx,
                imageRect.top  + d.box.bottom * sy
            )

            canvas.drawRect(mapped, boxPaint)

            val label = "id=${d.cls} ${"%.2f".format(d.score)}"
            val tw = textPaint.measureText(label)
            val th = textPaint.textSize * 1.2f
            val rx = mapped.left
            val ry = (mapped.top - th).coerceAtLeast(0f)
            canvas.drawRect(rx, ry, rx + tw + 12f, ry + th, bgPaint)
            canvas.drawText(label, rx + 6f, ry + th - 8f, textPaint)
        }
    }
}