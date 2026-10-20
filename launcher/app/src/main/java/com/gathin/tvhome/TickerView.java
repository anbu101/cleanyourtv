package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A full-width strip at the bottom of the home screen that scrolls a quote from right to left.
 * When the text has scrolled fully off screen, {@link #setOnCycleComplete} fires so the caller
 * can supply the next quote.
 *
 * Driven by postInvalidateOnAnimation() — no Handler or animator allocations.
 */
public final class TickerView extends View {

    /** Called once the current text has scrolled fully past the left edge. */
    private Runnable onCycleComplete;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint   = new Paint();

    private String  text      = "";
    private float   textW     = 0f;
    private float   offsetX   = 0f;   // current left edge of the text in view coords
    private boolean running   = false;
    private long    lastMs    = 0;

    /** Scroll speed in dp/s — tuned for TV viewing distance. */
    private static final float SPEED_DP = 90f;

    public TickerView(Context c) { super(c); init(); }
    public TickerView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        float sp      = getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTextSize(13f * sp);
        textPaint.setColor(0xCCFFFFFF);
        textPaint.setLetterSpacing(0.04f);
        bgPaint.setColor(0x33000000);
    }

    /** Load a new quote and start (or restart) the scroll. */
    public void setText(String t) {
        text    = t != null ? t : "";
        textW   = textPaint.measureText(text);
        offsetX = getWidth() > 0 ? getWidth() : 1920f;
        lastMs  = System.currentTimeMillis();
        running = !text.isEmpty();
        setVisibility(running ? VISIBLE : GONE);
        if (running) invalidate();
    }

    /** Pause the scroll without hiding the view. */
    public void pause()  { running = false; }

    /** Resume a paused scroll. */
    public void resume() {
        if (text.isEmpty()) return;
        lastMs  = System.currentTimeMillis();
        running = true;
        invalidate();
    }

    public void setOnCycleComplete(Runnable r) { this.onCycleComplete = r; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Fade the edges so the text appears to materialise rather than hard-cut.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running || text.isEmpty()) return;

        int   w       = getWidth();
        int   h       = getHeight();
        float density = getResources().getDisplayMetrics().density;
        float speed   = SPEED_DP * density;

        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastMs) * 0.001f, 0.08f);
        lastMs   = now;

        offsetX -= speed * dt;

        // Cycle: text has scrolled fully off the left edge
        if (offsetX < -textW - 60 * density) {
            offsetX = w;
            if (onCycleComplete != null) onCycleComplete.run();
        }

        // Background strip
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Edge fade — left 120dp, right 120dp
        float fade = 120f * density;
        Paint fadePaint = new Paint();
        fadePaint.setShader(new LinearGradient(0, 0, fade, 0,
                0x33000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, fade, h, fadePaint);
        fadePaint.setShader(new LinearGradient(w - fade, 0, w, 0,
                0x00000000, 0x33000000, Shader.TileMode.CLAMP));
        canvas.drawRect(w - fade, 0, w, h, fadePaint);

        // Text
        float baseline = h * 0.68f + textPaint.getTextSize() * 0.35f;
        canvas.drawText(text, offsetX, baseline, textPaint);

        postInvalidateOnAnimation();
    }
}
