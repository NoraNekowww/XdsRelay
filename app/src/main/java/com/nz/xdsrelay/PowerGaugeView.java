package com.nz.xdsrelay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 大号功率环形表。 */
public class PowerGaugeView extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final int cTrack, cText, cMuted, cAccent, cAccent2, cWarn, cErr;

    private float power = 0f;
    private float maxPower = 1000f;
    private float peak = 0f;

    public PowerGaugeView(Context c) { this(c, null); }

    public PowerGaugeView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        float stroke = 18f * d;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setStrokeCap(Paint.Cap.ROUND);
        value.setStyle(Paint.Style.STROKE);
        value.setStrokeWidth(stroke);
        value.setStrokeCap(Paint.Cap.ROUND);
        dot.setStyle(Paint.Style.FILL);

        number.setFakeBoldText(true);
        number.setTextAlign(Paint.Align.CENTER);
        unit.setTextAlign(Paint.Align.CENTER);
        caption.setTextAlign(Paint.Align.CENTER);

        cTrack = getResources().getColor(R.color.surface2, null);
        cText = getResources().getColor(R.color.text, null);
        cMuted = getResources().getColor(R.color.muted, null);
        cAccent = getResources().getColor(R.color.accent, null);
        cAccent2 = getResources().getColor(R.color.accent2, null);
        cWarn = getResources().getColor(R.color.warn, null);
        cErr = getResources().getColor(R.color.err, null);
    }

    public void setPower(float w) {
        this.power = Math.max(0f, w);
        if (this.power > peak) peak = this.power;
        invalidate();
    }

    public void setMaxPower(float max) {
        this.maxPower = Math.max(100f, max);
        invalidate();
    }

    public void resetPeak() {
        peak = 0f;
        invalidate();
    }

    public float peak() { return peak; }

    private int zoneColor(float p, float max) {
        float f = max <= 0 ? 0 : p / max;
        if (f < 0.20f) return cAccent;
        if (f < 0.45f) return cAccent;
        if (f < 0.70f) return cAccent2;
        if (f < 0.90f) return cWarn;
        return cErr;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float stroke = 18f * d;
        float pad = stroke / 2f + 4f * d;
        float size = Math.min(w, h) - pad * 2f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        oval.set(left, top, left + size, top + size);

        track.setColor(cTrack);
        canvas.drawArc(oval, START_ANGLE, SWEEP, false, track);

        float frac = Math.max(0f, Math.min(1f, power / maxPower));
        float sweep = SWEEP * frac;
        int color = zoneColor(power, maxPower);
        value.setColor(color);
        if (sweep > 0.4f) {
            canvas.drawArc(oval, START_ANGLE, sweep, false, value);
        }

        // 指针端点
        double rad = Math.toRadians(START_ANGLE + sweep);
        float cx = oval.centerX() + (float) Math.cos(rad) * (size / 2f);
        float cy = oval.centerY() + (float) Math.sin(rad) * (size / 2f);
        dot.setColor(color);
        canvas.drawCircle(cx, cy, stroke * 0.42f, dot);

        // 中央数字
        number.setColor(cText);
        number.setTextSize(size * 0.30f);
        unit.setColor(cMuted);
        unit.setTextSize(size * 0.075f);
        caption.setColor(cMuted);
        caption.setTextSize(size * 0.06f);

        float cy0 = oval.centerY() + size * 0.02f;
        canvas.drawText(String.valueOf(Math.round(power)), oval.centerX(), cy0, number);
        canvas.drawText("W  瞬时功率", oval.centerX(), cy0 + size * 0.115f, unit);
        canvas.drawText(String.format("峰值 %d W  ·  满量程 %d W",
                        Math.round(peak), Math.round(maxPower)),
                oval.centerX(), oval.bottom - size * 0.10f, caption);
    }
}
