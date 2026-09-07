package com.dopamide.motoanc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class BatteryView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int level = 0;
    private boolean charging = false;
    private boolean reported = false;
    private int ringColor = 0xFF7C4DFF;
    private int emptyColor = 0x335F6368;

    public BatteryView(Context context) {
        super(context);
        init();
    }

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setColors(int accent, int empty, int text) {
        ringColor = text;
        emptyColor = empty;
        textPaint.setColor(text);
        invalidate();
    }

    public void setLevel(int level, boolean charging, boolean reported) {
        this.level = level;
        this.charging = charging;
        this.reported = reported;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);
        float stroke = size * 0.12f;
        float pad = stroke / 2f + size * 0.06f;
        rect.set(pad, pad, size - pad, size - pad);
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setColor(emptyColor);
        canvas.drawArc(rect, 0, 360, false, ringPaint);
        fillPaint.setStrokeWidth(stroke);
        fillPaint.setColor(charging ? 0xFFFFD600 : ringColor);
        float sweep = reported ? (level / 100f) * 360f : 0;
        canvas.drawArc(rect, -90, sweep, false, fillPaint);
        textPaint.setTextSize(size * 0.20f);
        String txt = reported ? (level + "%") : "—";
        canvas.drawText(txt, w / 2f, h / 2f + textPaint.getTextSize() / 3f, textPaint);
    }
}
