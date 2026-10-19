package com.nz.xdsrelay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 左右腿功率平衡条。 */
public class BalanceBarView extends View {

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int cLeft, cRight, cMuted, cSurface2;

    private int leftW = -1;
    private int rightW = -1;
    private boolean available;

    public BalanceBarView(Context c) { this(c, null); }

    public BalanceBarView(Context c, AttributeSet a) {
        super(c, a);
        text.setTextSize(13f * getResources().getDisplayMetrics().density);
        sub.setTextSize(11f * getResources().getDisplayMetrics().density);
        cLeft = getResources().getColor(R.color.left_power, null);
        cRight = getResources().getColor(R.color.right_power, null);
        cMuted = getResources().getColor(R.color.muted, null);
        cSurface2 = getResources().getColor(R.color.surface2, null);
    }

    public void setBalance(int left, int right, boolean has) {
        this.leftW = left;
        this.rightW = right;
        this.available = has && (left + right) > 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float barH = 12f * d;
        float barTop = 20f * d;
        float radius = barH / 2f;

        text.setColor(cMuted);
        text.setTextAlign(Paint.Align.LEFT);
        sub.setColor(cMuted);
        sub.setTextAlign(Paint.Align.RIGHT);

        String leftLabel;
        String rightLabel;

        if (available) {
            float lp = 100f * leftW / (leftW + rightW);
            leftLabel = String.format("左 %d%%  %d W", Math.round(lp), leftW);
            rightLabel = String.format("%d W  %d%% 右", rightW, Math.round(100f - lp));
        } else {
            leftLabel = "左 --";
            rightLabel = "-- 右";
        }

        canvas.drawText(leftLabel, 0, 14f * d, text);
        canvas.drawText(rightLabel, w, 14f * d, sub);

        rect.set(0, barTop, w, barTop + barH);
        bar.setStyle(Paint.Style.FILL);
        bar.setColor(cSurface2);
        canvas.drawRoundRect(rect, radius, radius, bar);

        if (available) {
            float lp = Math.max(0f, Math.min(1f, (float) leftW / (leftW + rightW)));
            float split = w * lp;
            float min = radius;
            if (split < min) split = min;
            if (split > w - min) split = w - min;

            rect.set(0, barTop, split, barTop + barH);
            bar.setColor(cLeft);
            canvas.drawRoundRect(rect, radius, radius, bar);

            rect.set(split, barTop, w, barTop + barH);
            bar.setColor(cRight);
            canvas.drawRoundRect(rect, radius, radius, bar);

            // 中线
            bar.setColor(cMuted);
            canvas.drawRect(w / 2f - d, barTop - 2f * d, w / 2f + d, barTop + barH + 2f * d, bar);
        }
    }
}
