package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.core.content.res.ResourcesCompat;

import java.util.Collections;
import java.util.List;

/**
 * Draws synced lyrics in a vertical stack: the active line is bright gold and large, surrounding
 * lines fade to a dim warm tone. Smoothly scrolls when the active line changes.
 *
 * Placement (left / centre / right) is set via {@link #setPlacement(int)}.
 */
public class LyricsView extends View {

    static final int PLACE_LEFT   = 0;
    static final int PLACE_CENTRE = 1;
    static final int PLACE_RIGHT  = 2;

    private static final int VISIBLE_LINES = 7;
    private static final int HALF = VISIBLE_LINES / 2;
    private static final float ACTIVE_SIZE_SP  = 18f;
    private static final float PASSIVE_SIZE_SP = 13f;
    private static final float MIN_SIZE_SP     = 10f;
    private static final long  SCROLL_DURATION = 500L;

    // Gold palette
    private static final int GOLD_BRIGHT = 0xFFD4A017;
    private static final int GOLD_DIM    = 0xFF8B7232;
    private static final int GLOW_COLOR  = 0x40D4A017;

    private final Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private List<LyricsFetcher.Line> lines = Collections.emptyList();
    private int activeLine = -1;
    private float scrollOffset;
    private ValueAnimator scrollAnim;
    private int placement = PLACE_RIGHT;

    public LyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        Typeface font = ResourcesCompat.getFont(context, R.font.outfit_medium);
        textPaint.setTypeface(font);
        glowPaint.setTypeface(font);
        glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                6 * density, android.graphics.BlurMaskFilter.Blur.NORMAL));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setPlacement(int placement) {
        this.placement = placement;
        switch (placement) {
            case PLACE_LEFT:
                textPaint.setTextAlign(Paint.Align.LEFT);
                glowPaint.setTextAlign(Paint.Align.LEFT);
                break;
            case PLACE_CENTRE:
                textPaint.setTextAlign(Paint.Align.CENTER);
                glowPaint.setTextAlign(Paint.Align.CENTER);
                break;
            default:
                textPaint.setTextAlign(Paint.Align.RIGHT);
                glowPaint.setTextAlign(Paint.Align.RIGHT);
                break;
        }
        invalidate();
    }

    void setLines(List<LyricsFetcher.Line> lines) {
        this.lines = lines != null ? lines : Collections.emptyList();
        this.activeLine = -1;
        this.scrollOffset = 0;
        invalidate();
    }

    void setPosition(long positionMs) {
        if (lines.isEmpty()) return;
        int newActive = findLine(positionMs);
        if (newActive == activeLine) return;
        activeLine = newActive;
        animateScroll();
    }

    private int findLine(long posMs) {
        int lo = 0, hi = lines.size() - 1, result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= posMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private void animateScroll() {
        if (scrollAnim != null) scrollAnim.cancel();
        scrollAnim = ValueAnimator.ofFloat(1f, 0f);
        scrollAnim.setDuration(SCROLL_DURATION);
        scrollAnim.setInterpolator(new OvershootInterpolator(0.6f));
        scrollAnim.addUpdateListener(a -> {
            scrollOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        scrollAnim.start();
    }

    /** Returns the text size (px) shrunk so the text fits within maxWidth; never below MIN_SIZE_SP. */
    private float fitTextSize(String text, float desiredPx, float maxWidth) {
        textPaint.setTextSize(desiredPx);
        float measured = textPaint.measureText(text);
        if (measured <= maxWidth) return desiredPx;
        float minPx = MIN_SIZE_SP * density;
        float scaled = desiredPx * (maxWidth / measured);
        return Math.max(minPx, scaled);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (lines.isEmpty() || activeLine < 0) return;

        int w = getWidth();
        int h = getHeight();
        float lineHeight = h / (float) VISIBLE_LINES;
        float centreY = h / 2f;
        float pad = 12 * density;
        float maxTextWidth = w - pad * 2;

        // Anchor x based on placement
        float anchorX;
        switch (placement) {
            case PLACE_LEFT:   anchorX = pad; break;
            case PLACE_CENTRE: anchorX = w / 2f; break;
            default:           anchorX = w - pad; break;
        }

        for (int offset = -HALF - 1; offset <= HALF + 1; offset++) {
            int idx = activeLine + offset;
            if (idx < 0 || idx >= lines.size()) continue;

            float y = centreY + offset * lineHeight + scrollOffset * lineHeight;
            if (y < -lineHeight || y > h + lineHeight) continue;

            boolean isActive = (offset == 0);
            float distFromCentre = Math.min(1f, Math.abs(y - centreY) / (h / 2f));

            float sizeSp = isActive ? ACTIVE_SIZE_SP
                    : PASSIVE_SIZE_SP + (ACTIVE_SIZE_SP - PASSIVE_SIZE_SP) * Math.max(0, 0.3f - distFromCentre);
            float desiredPx = sizeSp * density;

            String text = lines.get(idx).text;
            float textSizePx = fitTextSize(text, desiredPx, maxTextWidth);
            textPaint.setTextSize(textSizePx);

            // Colour and alpha
            int alpha;
            if (isActive) {
                alpha = 255;
                textPaint.setColor((alpha << 24) | (GOLD_BRIGHT & 0xFFFFFF));
            } else {
                alpha = (int) (180 * (1f - distFromCentre * 0.85f));
                alpha = Math.max(0, alpha);
                textPaint.setColor((alpha << 24) | (GOLD_DIM & 0xFFFFFF));
            }

            float textY = y + textSizePx * 0.35f;

            // Soft gold glow behind the active line
            if (isActive) {
                glowPaint.setTextSize(textSizePx);
                glowPaint.setColor(GLOW_COLOR);
                canvas.drawText(text, anchorX, textY, glowPaint);
            }

            canvas.drawText(text, anchorX, textY, textPaint);
        }
    }
}
