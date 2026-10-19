package com.nz.xdsrelay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 功率/踏频滚动趋势图。 */
public class SparklineView extends View {

    private static final int CAPACITY = 180;   // 每 500ms 一个点 => 90 秒

    private final float[] power = new float[CAPACITY];
    private final float[] cadence = new float[CAPACITY];
    private int count = 0;
    private int head = 0;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final int cAccent, cAccent2, cGrid;

    private float scaleMax = 500f;

    public SparklineView(Context c) { this(c, null); }

    public SparklineView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        cad.setStyle(Paint.Style.STROKE);
        cad.setStrokeWidth(1.5f * d);
        fill.setStyle(Paint.Style.FILL);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        txt.setTextSize(9f * d);

        cAccent = getResources().getColor(R.color.accent, null);
        cAccent2 = getResources().getColor(R.color.accent2, null);
        cGrid = getResources().getColor(R.color.stroke, null);
        line.setColor(cAccent);
        cad.setColor(cAccent2);
        txt.setColor(getResources().getColor(R.color.muted, null));
    }

    public void push(float powerW, float cadenceRpm) {
        power[head] = powerW;
        cadence[head] = cadenceRpm;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
        if (powerW > scaleMax) scaleMax = powerW * 1.1f;
        invalidate();
    }

    public void reset() {
        count = 0;
        head = 0;
        scaleMax = 500f;
        invalidate();
    }

    private float powerAt(int i) {
        int idx = (head - count + i + CAPACITY * 2) % CAPACITY;
        return power[idx];
    }

    private float cadenceAt(int i) {
        int idx = (head - count + i + CAPACITY * 2) % CAPACITY;
        return cadence[idx];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float padTop = 8f * d, padBottom = 14f * d;

        grid.setColor(cGrid);
        for (int i = 0; i <= 4; i++) {
            float y = padTop + (h - padTop - padBottom) * i / 4f;
            canvas.drawLine(0, y, w, y, grid);
        }

        if (count < 2) {
            txt.setColor(getResources().getColor(R.color.muted, null));
            canvas.drawText("等待数据…", 4f * d, h / 2f, txt);
            return;
        }

        float usableH = h - padTop - padBottom;
        float stepX = w / (float) (CAPACITY - 1);
        float startX = w - stepX * (count - 1);
        if (startX < 0) startX = 0;

        // ---- 功率面积 ----
        path.reset();
        path.moveTo(startX, padTop + usableH);
        for (int i = 0; i < count; i++) {
            float x = startX + stepX * i;
            float v = Math.min(1f, powerAt(i) / scaleMax);
            path.lineTo(x, padTop + usableH * (1f - v));
        }
        path.lineTo(startX + stepX * (count - 1), padTop + usableH);
        path.close();

        fill.setShader(new LinearGradient(0, padTop, 0, padTop + usableH,
                withAlpha(cAccent, 90), withAlpha(cAccent, 0), Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);

        // ---- 功率线 ----
        path.reset();
        for (int i = 0; i < count; i++) {
            float x = startX + stepX * i;
            float v = Math.min(1f, powerAt(i) / scaleMax);
            float y = padTop + usableH * (1f - v);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);

        // ---- 踏频线（0-140 映射） ----
        path.reset();
        for (int i = 0; i < count; i++) {
            float x = startX + stepX * i;
            float v = Math.min(1f, cadenceAt(i) / 140f);
            float y = padTop + usableH * (1f - v);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, cad);

        txt.setColor(getResources().getColor(R.color.muted, null));
        canvas.drawText("0 W", 2f * d, h - 2f * d, txt);
        String maxLabel = Math.round(scaleMax) + " W";
        txt.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(maxLabel, w - 2f * d, h - 2f * d, txt);
        txt.setTextAlign(Paint.Align.LEFT);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
