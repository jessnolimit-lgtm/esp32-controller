package com.example.rccarcontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * SpeedometerView
 *
 * A semi-circular gauge that shows the current drive state:
 *  – IDLE   : needle at 0 (left edge of arc)
 *  – FORWARD: needle sweeps to the right (positive direction)
 *  – REVERSE: needle sweeps to the left  (negative direction, shown separately)
 *
 * The arc spans 180° (from 180° to 0°, i.e. left→right across the top half).
 */
public class SpeedometerView extends View {

    // Gauge state constants
    public static final int STATE_IDLE    = 0;
    public static final int STATE_FORWARD = 1;
    public static final int STATE_REVERSE = 2;

    private int currentState = STATE_IDLE;

    // Paints
    private final Paint trackPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcBounds = new RectF();

    // Arc geometry
    private static final float START_ANGLE  = 180f;   // left  (west)
    private static final float SWEEP_TOTAL  = 180f;   // half circle
    private static final float STROKE_WIDTH = 14f;

    // Colours (hex literals kept to avoid context-dependency at draw time)
    private static final int COLOR_TRACK   = 0xFF1E1E38;
    private static final int COLOR_FORWARD = 0xFF3A7BF7;
    private static final int COLOR_REVERSE = 0xFFE74C3C;
    private static final int COLOR_NEEDLE  = 0xFFE74C3C;
    private static final int COLOR_DOT     = 0xFFFFFFFF;
    private static final int COLOR_TEXT    = 0xFF9BA3C2;
    private static final int COLOR_TICK    = 0xFF3A3A60;

    public SpeedometerView(Context ctx) {
        super(ctx);
        init();
    }

    public SpeedometerView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    public SpeedometerView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(STROKE_WIDTH);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(COLOR_TRACK);

        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(STROKE_WIDTH);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(COLOR_FORWARD);

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(3f);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setColor(COLOR_NEEDLE);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(COLOR_DOT);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_TEXT);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(1.5f);
        tickPaint.setColor(COLOR_TICK);
    }

    /**
     * Update the displayed state and trigger a redraw.
     * @param state one of STATE_IDLE, STATE_FORWARD, STATE_REVERSE
     */
    public void setState(int state) {
        currentState = state;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        float cx = w / 2f;
        // Place the arc centre below the view bottom so only the top half is visible
        float cy = h * 0.92f;
        float radius = Math.min(cx, cy) * 0.9f;
        float inset = STROKE_WIDTH / 2f + 2;

        arcBounds.set(cx - radius + inset, cy - radius + inset,
                      cx + radius - inset, cy + radius - inset);

        // ── Track (full 180° background arc) ──────────────────────
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_TOTAL, false, trackPaint);

        // ── Tick marks ──────────────────────────────────────────────
        drawTicks(canvas, cx, cy, radius);

        // ── Filled arc + needle ──────────────────────────────────────
        float needleAngleDeg;  // angle from the start (0 = left, 90 = top, 180 = right)

        switch (currentState) {
            case STATE_FORWARD:
                fillPaint.setColor(COLOR_FORWARD);
                // Fill right half: 90° sweep starting from the 9 o'clock position
                canvas.drawArc(arcBounds, START_ANGLE, 90f, false, fillPaint);
                needleAngleDeg = 90f;   // pointing straight up
                break;

            case STATE_REVERSE:
                fillPaint.setColor(COLOR_REVERSE);
                // Fill a small sliver to the left of center to indicate reverse
                canvas.drawArc(arcBounds, START_ANGLE + SWEEP_TOTAL, -45f, false, fillPaint);
                needleAngleDeg = 135f;  // pointing toward the lower-left
                break;

            case STATE_IDLE:
            default:
                needleAngleDeg = 0f;   // pointing left (idle)
                break;
        }

        // Convert gauge angle to canvas angle
        // Gauge 0° → canvas 180° (left),  Gauge 180° → canvas 0° (right)
        double needleRadians = Math.toRadians(START_ANGLE + needleAngleDeg);
        float needleLen = radius - STROKE_WIDTH - 10;
        float nx = (float)(cx + needleLen * Math.cos(needleRadians));
        float ny = (float)(cy + needleLen * Math.sin(needleRadians));

        canvas.drawLine(cx, cy, nx, ny, needlePaint);

        // Centre dot
        canvas.drawCircle(cx, cy, 6f, dotPaint);

        // ── Labels ─────────────────────────────────────────────────
        textPaint.setTextSize(18f);
        // "R" label at left end of arc
        float labelR = radius + 16;
        canvas.drawText("R", (float)(cx + labelR * Math.cos(Math.toRadians(180))),
                              (float)(cy + labelR * Math.sin(Math.toRadians(180))) + 6, textPaint);
        // "F" label at right end of arc
        canvas.drawText("F", (float)(cx + labelR * Math.cos(Math.toRadians(0))),
                              (float)(cy + labelR * Math.sin(Math.toRadians(0))) + 6, textPaint);
    }

    /** Draw 9 evenly-spaced tick marks around the arc. */
    private void drawTicks(Canvas canvas, float cx, float cy, float radius) {
        int ticks = 9;
        float innerR = radius - STROKE_WIDTH * 1.5f;
        float outerR = radius - 2f;
        for (int i = 0; i <= ticks; i++) {
            double angle = Math.toRadians(START_ANGLE + (SWEEP_TOTAL / ticks) * i);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            canvas.drawLine(cx + innerR * cos, cy + innerR * sin,
                            cx + outerR * cos, cy + outerR * sin,
                            tickPaint);
        }
    }
}
