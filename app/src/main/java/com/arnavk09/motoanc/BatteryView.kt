package com.arnavk09.motoanc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Circular battery gauge: an arc ring, a fill arc, and a centered percentage. */
class BatteryView : View {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var level = 0
    private var charging = false
    private var reported = false
    private var ringColor = 0xFF7C4DFF.toInt()
    private var emptyColor = 0x335F6368.toInt()

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeCap = Paint.Cap.ROUND
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeCap = Paint.Cap.ROUND
        textPaint.textAlign = Paint.Align.CENTER
    }

    fun setColors(
        accent: Int,
        empty: Int,
        text: Int,
    ) {
        ringColor = text
        emptyColor = empty
        textPaint.color = text
        invalidate()
    }

    fun setLevel(
        level: Int,
        charging: Boolean,
        reported: Boolean,
    ) {
        this.level = level
        this.charging = charging
        this.reported = reported
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val size = minOf(w, h)
        val stroke = size * 0.12f
        val pad = stroke / 2f + size * 0.06f
        rect.set(pad, pad, size - pad, size - pad)
        ringPaint.strokeWidth = stroke
        ringPaint.color = emptyColor
        canvas.drawArc(rect, 0f, 360f, false, ringPaint)
        fillPaint.strokeWidth = stroke
        fillPaint.color = if (charging) 0xFFFFD600.toInt() else ringColor
        val sweep = if (reported) (level / 100f) * 360f else 0f
        canvas.drawArc(rect, -90f, sweep, false, fillPaint)
        textPaint.textSize = size * 0.20f
        val txt = if (reported) "$level%" else "—"
        canvas.drawText(txt, w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
    }
}
