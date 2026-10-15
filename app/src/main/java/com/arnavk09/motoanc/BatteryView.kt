package com.arnavk09.motoanc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

/** Circular battery gauge: an arc ring, a fill arc, and a centered percentage. */
class BatteryView : View {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var centerIcon: Drawable? = null
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
        ringColor = accent
        emptyColor = 0x80FFFFFF.toInt()
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

    fun setCenterIcon(resourceId: Int) {
        centerIcon =
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                context.getDrawable(resourceId)
            } else {
                @Suppress("DEPRECATION")
                context.resources.getDrawable(resourceId)
            }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val ringSize = 32f * density
        val stroke = 2.5f * density
        val ringTop = 1.5f * density
        rect.set(
            (w - ringSize) / 2f + stroke / 2f,
            ringTop + stroke / 2f,
            (w + ringSize) / 2f - stroke / 2f,
            ringTop + ringSize - stroke / 2f,
        )
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = stroke
        ringPaint.color = 0x42FFFFFF
        canvas.drawArc(rect, 0f, 360f, false, ringPaint)
        if (reported) {
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = stroke
            fillPaint.strokeCap = Paint.Cap.ROUND
            val low = level <= 15
            fillPaint.color =
                if (charging) {
                    0xFF3584E4.toInt()
                } else if (low) {
                    0xFFED333B.toInt()
                } else {
                    ringColor
                }
            canvas.drawArc(rect, -90f, level.coerceIn(0, 100) * 3.6f, false, fillPaint)
        }
        val iconSize = 15f * density
        centerIcon?.let { drawable ->
            drawable.setBounds(
                ((w - iconSize) / 2f).toInt(),
                (ringTop + (ringSize - iconSize) / 2f).toInt(),
                ((w + iconSize) / 2f).toInt(),
                (ringTop + (ringSize + iconSize) / 2f).toInt(),
            )
            drawable.alpha = if (reported) 220 else 100
            drawable.draw(canvas)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 12f * density
        textPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textPaint.color = if (reported) textPaint.color else emptyColor
        val txt = if (reported) "$level%" else "—"
        canvas.drawText(txt, w / 2f, h - 4f * density, textPaint)
    }
}
