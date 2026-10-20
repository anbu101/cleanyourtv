package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Full-screen animated weather backdrop. Call {@link #setScene(Scene)} to switch; the view is
 * GONE when scene is NONE so it adds zero cost to the normal (no-weather) layout.
 *
 * Every scene drives its own particle system from System.currentTimeMillis() so there is no
 * external animator or Handler needed — just postInvalidateOnAnimation() at the end of onDraw.
 * All allocations happen in initParticles() (size-change) not in onDraw.
 */
public final class WeatherView extends View {

    public enum Scene {
        NONE,
        CLEAR_DAY,       // warm blue sky, wispy clouds, light rays
        CLEAR_NIGHT,     // star field, gentle cloud wisps, moon glow
        CLOUDY,          // heavy grey-blue cloud masses drifting
        FOG,             // layered slow horizontal bands
        DRIZZLE,         // sparse fine rain, dark blue-grey sky
        RAIN,            // heavy angled downpour, puddle ripples
        SNOW,            // drifting snowflakes with sine wobble
        THUNDERSTORM     // rain + purple-black sky + lightning flashes
    }

    // ---- scene state -------------------------------------------------------
    private Scene scene = Scene.NONE;

    // ---- paint pool (never allocate in onDraw) ------------------------------
    private final Paint fillPaint  = new Paint();
    private final Paint ptPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint();
    private final Paint lightningPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- background gradient (rebuilt on scene / size change) ---------------
    private LinearGradient bgGradient;
    private int cachedW, cachedH;

    // ---- particle buffers ---------------------------------------------------
    private static final int STAR_N  = 120;
    private static final int RAIN_N  = 750;
    private static final int DRIZZLE_N = 220;
    private static final int SNOW_N  = 160;
    private static final int CLOUD_N = 7;
    private static final int FOG_N   = 9;
    private static final int RIPPLE_N = 6;

    // stars: x,y,phase
    private final float[] sx = new float[STAR_N], sy = new float[STAR_N], sph = new float[STAR_N];
    // rain: x,y,speed
    private final float[] rx = new float[RAIN_N], ry = new float[RAIN_N], rspd = new float[RAIN_N];
    // snow: x,y,r,speed,phase
    private final float[] wx = new float[SNOW_N], wy = new float[SNOW_N];
    private final float[] wr = new float[SNOW_N], wspd = new float[SNOW_N], wph = new float[SNOW_N];
    // clouds: x,y,w,h,speed,alpha
    private final float[] cx = new float[CLOUD_N], cy = new float[CLOUD_N];
    private final float[] cw = new float[CLOUD_N], ch = new float[CLOUD_N];
    private final float[] cspd = new float[CLOUD_N], ca = new float[CLOUD_N];
    // fog bands: y,alpha,speed (full-width bands)
    private final float[] fy = new float[FOG_N], fa = new float[FOG_N], fspd = new float[FOG_N];
    // ripples: x,y,phase (pool)
    private final float[] qx = new float[RIPPLE_N], qy = new float[RIPPLE_N], qph = new float[RIPPLE_N];

    // lightning
    private float lightAlpha = 0f;
    private long  nextBolt   = 0;
    private boolean boltDrawn = false;
    private final Path boltPath = new Path();
    private final RectF tmpRect = new RectF();

    private long lastMs = 0;
    private final Random rng = new Random(7);

    // -------------------------------------------------------------------------
    public WeatherView(Context c) { super(c); init(); }
    public WeatherView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
    }

    /** Switch the weather scene. NONE hides the view entirely. */
    public void setScene(Scene s) {
        if (s == scene) return;
        scene = s;
        if (s == Scene.NONE) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        bgGradient = null;   // force rebuild
        if (getWidth() > 0) initParticles(getWidth(), getHeight());
        invalidate();
    }

    public Scene getScene() { return scene; }

    // ---- size change --------------------------------------------------------
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (scene != Scene.NONE) initParticles(w, h);
    }

    // ---- particle init (called once per scene/size change) ------------------
    private void initParticles(int w, int h) {
        rng.setSeed(31337);
        // stars
        for (int i = 0; i < STAR_N; i++) {
            sx[i]  = rng.nextFloat() * w;
            sy[i]  = rng.nextFloat() * h * 0.75f;
            sph[i] = rng.nextFloat() * TWO_PI;
        }
        // rain
        for (int i = 0; i < RAIN_N; i++) {
            rx[i]  = rng.nextFloat() * (w + 300) - 150;
            ry[i]  = rng.nextFloat() * h;
            rspd[i] = 16f + rng.nextFloat() * 12f;
        }
        // snow
        for (int i = 0; i < SNOW_N; i++) {
            wx[i]  = rng.nextFloat() * w;
            wy[i]  = rng.nextFloat() * h;
            wr[i]  = 1.8f + rng.nextFloat() * 4.5f;
            wspd[i] = 0.4f + rng.nextFloat() * 1.6f;
            wph[i] = rng.nextFloat() * TWO_PI;
        }
        // clouds
        for (int i = 0; i < CLOUD_N; i++) {
            cw[i]  = w * (0.22f + rng.nextFloat() * 0.28f);
            ch[i]  = 55f + rng.nextFloat() * 90f;
            cx[i]  = rng.nextFloat() * (w + cw[i]);
            cy[i]  = h * (0.04f + rng.nextFloat() * 0.50f);
            cspd[i] = 0.08f + rng.nextFloat() * 0.22f;
            ca[i]  = 0.45f + rng.nextFloat() * 0.45f;
        }
        // fog bands
        for (int i = 0; i < FOG_N; i++) {
            fy[i]  = h * ((float) i / FOG_N);
            fa[i]  = 0.04f + rng.nextFloat() * 0.07f;
            fspd[i] = (0.04f + rng.nextFloat() * 0.08f) * (rng.nextBoolean() ? 1 : -1);
        }
        // ripples
        for (int i = 0; i < RIPPLE_N; i++) {
            qx[i]  = w * 0.08f + rng.nextFloat() * w * 0.84f;
            qy[i]  = h * 0.68f + rng.nextFloat() * h * 0.28f;
            qph[i] = rng.nextFloat() * TWO_PI;
        }

        lightAlpha = 0f;
        nextBolt   = System.currentTimeMillis() + 2000 + rng.nextInt(3000);
        lastMs     = System.currentTimeMillis();
        buildGradient(w, h);
    }

    private void buildGradient(int w, int h) {
        cachedW = w; cachedH = h;
        int[] colors; float[] stops;
        switch (scene) {
            case CLEAR_DAY:
                colors = new int[]{ 0xFF0B2A4A, 0xFF1A5276, 0xFF2980B9, 0xFF5DADE2 };
                stops  = new float[]{ 0f, 0.3f, 0.65f, 1f };
                break;
            case CLEAR_NIGHT:
                colors = new int[]{ 0xFF000510, 0xFF020C1E, 0xFF050F22, 0xFF0A1830 };
                stops  = new float[]{ 0f, 0.35f, 0.70f, 1f };
                break;
            case CLOUDY:
                colors = new int[]{ 0xFF0F1720, 0xFF182330, 0xFF1E2C3A, 0xFF253544 };
                stops  = new float[]{ 0f, 0.35f, 0.65f, 1f };
                break;
            case FOG:
                colors = new int[]{ 0xFF1E2530, 0xFF2A3545, 0xFF2E3A48, 0xFF303C4A };
                stops  = new float[]{ 0f, 0.4f, 0.7f, 1f };
                break;
            case DRIZZLE:
                colors = new int[]{ 0xFF090F18, 0xFF0E1A28, 0xFF121F2E, 0xFF162435 };
                stops  = new float[]{ 0f, 0.35f, 0.65f, 1f };
                break;
            case RAIN:
                colors = new int[]{ 0xFF060A0F, 0xFF0A0F18, 0xFF0D1520, 0xFF101820 };
                stops  = new float[]{ 0f, 0.35f, 0.65f, 1f };
                break;
            case SNOW:
                colors = new int[]{ 0xFF080F1C, 0xFF0D1628, 0xFF121D32, 0xFF1A263C };
                stops  = new float[]{ 0f, 0.35f, 0.65f, 1f };
                break;
            case THUNDERSTORM:
                colors = new int[]{ 0xFF080410, 0xFF100818, 0xFF160C22, 0xFF1A0F28 };
                stops  = new float[]{ 0f, 0.35f, 0.65f, 1f };
                break;
            default:
                colors = new int[]{ 0xFF000000, 0xFF000000 };
                stops  = new float[]{ 0f, 1f };
        }
        bgGradient = new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP);
    }

    // ---- draw ---------------------------------------------------------------
    @Override
    protected void onDraw(Canvas canvas) {
        if (scene == Scene.NONE) return;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // Rebuild gradient if size changed or first draw
        if (bgGradient == null || cachedW != w || cachedH != h) buildGradient(w, h);

        long now  = System.currentTimeMillis();
        float dt  = Math.min((now - lastMs) * 0.001f, 0.08f);
        lastMs    = now;
        float t   = now * 0.001f;

        // Background
        fillPaint.setShader(bgGradient);
        canvas.drawRect(0, 0, w, h, fillPaint);
        fillPaint.setShader(null);

        switch (scene) {
            case CLEAR_DAY:    drawClearDay(canvas, w, h, t, dt);         break;
            case CLEAR_NIGHT:  drawClearNight(canvas, w, h, t, dt);       break;
            case CLOUDY:       drawCloudy(canvas, w, h, t, dt);           break;
            case FOG:          drawFog(canvas, w, h, t, dt);              break;
            case DRIZZLE:      drawRainLayer(canvas, w, h, dt, DRIZZLE_N, 0.35f, 18f, 0.55f, false); break;
            case RAIN:         drawRainFull(canvas, w, h, t, dt);         break;
            case SNOW:         drawSnow(canvas, w, h, t, dt);             break;
            case THUNDERSTORM: drawThunderstorm(canvas, w, h, t, dt, now); break;
        }

        postInvalidateOnAnimation();
    }

    // =========================================================================
    // CLEAR DAY — warm blue sky, soft cloud wisps, faint golden horizon rays
    // =========================================================================
    private void drawClearDay(Canvas canvas, int w, int h, float t, float dt) {
        // horizon warm glow
        fillPaint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                0x00F0C060, 0x22F0A030, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.55f, w, h, fillPaint);
        fillPaint.setShader(null);

        // light rays fanning from lower-right corner
        ptPaint.setColor(0x0FFFFF88);
        ptPaint.setStrokeWidth(h * 0.04f);
        linePaint.setStrokeWidth(h * 0.06f);
        float ox = w * 1.1f, oy = h * 1.05f;
        for (int i = 0; i < 8; i++) {
            float angle = (float) Math.toRadians(-135 + i * 14 + (float) Math.sin(t * 0.18) * 2);
            float ray = w * 1.6f;
            float a = 0.03f + 0.02f * (float) Math.sin(t * 0.4 + i * 0.8);
            linePaint.setColor(Color.argb((int)(a * 255), 255, 240, 150));
            linePaint.setStrokeWidth(h * (0.04f + 0.015f * i));
            canvas.drawLine(ox, oy, ox + (float)Math.cos(angle) * ray, oy + (float)Math.sin(angle) * ray, linePaint);
        }

        // wispy cloud puffs
        advanceClouds(w, dt);
        for (int i = 0; i < CLOUD_N; i++) {
            drawCloudPuff(canvas, cx[i], cy[i], cw[i], ch[i], 0xBBFFFFFF, ca[i] * 0.6f);
        }
    }

    // =========================================================================
    // CLEAR NIGHT — twinkling stars, faint moon glow, drifting wisps
    // =========================================================================
    private void drawClearNight(Canvas canvas, int w, int h, float t, float dt) {
        // moon glow in upper-left
        float mx = w * 0.18f, my = h * 0.14f, mr = h * 0.22f;
        fillPaint.setShader(new android.graphics.RadialGradient(mx, my, mr,
                new int[]{ 0x30E8E0C0, 0x14C0B890, 0x00000000 },
                new float[]{ 0f, 0.4f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(mx, my, mr, fillPaint);
        fillPaint.setShader(null);
        // moon disc
        ptPaint.setColor(0xFFE8DDB5);
        canvas.drawCircle(mx, my, h * 0.038f, ptPaint);
        // slight shadow to make it look like a waxing crescent
        ptPaint.setColor(0xFF050E1E);
        canvas.drawCircle(mx - h * 0.014f, my - h * 0.005f, h * 0.033f, ptPaint);

        // twinkling stars
        for (int i = 0; i < STAR_N; i++) {
            float alpha = 0.4f + 0.6f * (float)(0.5 + 0.5 * Math.sin(t * (0.6 + (i % 5) * 0.3) + sph[i]));
            float r = 1.2f + (i % 3) * 0.7f;
            ptPaint.setColor(Color.argb((int)(alpha * 255), 240, 240, 255));
            canvas.drawCircle(sx[i], sy[i], r, ptPaint);
            // occasional twinkle cross-hair
            if (i % 7 == 0 && alpha > 0.75f) {
                linePaint.setColor(Color.argb((int)(alpha * 80), 220, 220, 255));
                linePaint.setStrokeWidth(0.8f);
                canvas.drawLine(sx[i] - r * 2, sy[i], sx[i] + r * 2, sy[i], linePaint);
                canvas.drawLine(sx[i], sy[i] - r * 2, sx[i], sy[i] + r * 2, linePaint);
            }
        }

        // faint drifting wisps
        advanceClouds(w, dt);
        for (int i = 0; i < CLOUD_N; i++) {
            drawCloudPuff(canvas, cx[i], cy[i], cw[i], ch[i] * 0.5f, 0x55AABBCC, ca[i] * 0.3f);
        }
    }

    // =========================================================================
    // CLOUDY — thick layered cloud masses
    // =========================================================================
    private void drawCloudy(Canvas canvas, int w, int h, float t, float dt) {
        advanceClouds(w, dt);
        // Multiple layers: back-to-front
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < CLOUD_N; i++) {
                if (i % 3 != pass) continue;
                float shade = 0x50 + pass * 0x18;
                int col = Color.argb((int)(ca[i] * 200), (int)shade, (int)(shade + 12), (int)(shade + 20));
                drawCloudPuff(canvas, cx[i] + pass * 30, cy[i], cw[i] * (1 + pass * 0.2f), ch[i], col, ca[i]);
            }
        }
    }

    // =========================================================================
    // FOG — layered horizontal bands drifting at different speeds
    // =========================================================================
    private void drawFog(Canvas canvas, int w, int h, float t, float dt) {
        for (int i = 0; i < FOG_N; i++) {
            fy[i] += fspd[i] * dt * 60f;
            if (fy[i] > h) fy[i] -= h;
            else if (fy[i] < 0) fy[i] += h;
        }
        for (int rep = 0; rep < 2; rep++) {
            for (int i = 0; i < FOG_N; i++) {
                float bandH = h * (0.05f + (i % 3) * 0.03f);
                float yy = fy[i] + rep * h;
                if (yy > h + bandH || yy < -bandH) continue;
                float pulse = 1f + 0.25f * (float) Math.sin(t * 0.3 + i);
                fillPaint.setShader(new LinearGradient(0, yy - bandH, 0, yy + bandH,
                        0x00B8C8D8, Color.argb((int)(fa[i] * pulse * 255), 180, 200, 216),
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, yy - bandH, w, yy + bandH, fillPaint);
                fillPaint.setShader(null);
            }
        }
    }

    // =========================================================================
    // Rain helpers
    // =========================================================================
    /**
     * @param count    how many rain particles to use
     * @param angle    radians from vertical (positive = lean right)
     * @param maxLen   max streak length px
     * @param alpha    base alpha 0..1
     * @param ripples  draw puddle ripples at the bottom
     */
    private void drawRainLayer(Canvas canvas, int w, int h, float dt,
                                int count, float angle, float maxLen,
                                float alpha, boolean ripples) {
        float sinA = (float) Math.sin(angle);
        float cosA = (float) Math.cos(angle);  // cos of angle from vertical
        // dx per px of length
        float dxPerLen = sinA;
        float dyPerLen = cosA;

        linePaint.setStrokeWidth(1.1f);
        for (int i = 0; i < count; i++) {
            ry[i] += rspd[i] * cosA * dt * 60f;
            rx[i] += rspd[i] * sinA  * dt * 60f;
            if (ry[i] > h + maxLen) {
                ry[i] = -(rng.nextFloat() * h * 0.3f);
                rx[i] = rng.nextFloat() * (w + 200) - 100;
            }
            float len = maxLen * (0.6f + (i % 5) * 0.08f);
            float a2 = alpha * (0.5f + 0.5f * ((i % 7) / 6f));
            linePaint.setColor(Color.argb((int)(a2 * 255), 160, 185, 210));
            canvas.drawLine(
                    rx[i], ry[i],
                    rx[i] - dxPerLen * len, ry[i] - dyPerLen * len,
                    linePaint);
        }

        if (ripples) drawRipples(canvas, w, h, dt);
    }

    private void drawRipples(Canvas canvas, int w, int h, float dt) {
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(1.2f);
        for (int i = 0; i < RIPPLE_N; i++) {
            qph[i] += dt * (1.2f + (i % 3) * 0.4f);
            float phase = qph[i] % (float)(Math.PI * 2);
            float frac  = phase / (float)(Math.PI * 2);   // 0..1
            float maxR  = 18f + i * 4f;
            float r     = frac * maxR;
            float a     = (1f - frac) * 0.4f;
            glowPaint.setColor(Color.argb((int)(a * 255), 140, 170, 200));
            canvas.drawOval(qx[i] - r, qy[i] - r * 0.35f, qx[i] + r, qy[i] + r * 0.35f, glowPaint);
        }
    }

    private void drawRainFull(Canvas canvas, int w, int h, float t, float dt) {
        drawRainLayer(canvas, w, h, dt, RAIN_N, 0.25f, 38f, 0.7f, true);
    }

    // =========================================================================
    // SNOW
    // =========================================================================
    private void drawSnow(Canvas canvas, int w, int h, float t, float dt) {
        // faint horizon glow (cold blue-white)
        fillPaint.setShader(new LinearGradient(0, h * 0.6f, 0, h,
                0x00C0D8F0, 0x18C0D8F8, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.6f, w, h, fillPaint);
        fillPaint.setShader(null);

        for (int i = 0; i < SNOW_N; i++) {
            float wobble = 20f * (float) Math.sin(t * 0.7 + wph[i]);
            wx[i] += wobble * dt;
            wy[i] += wspd[i] * dt * 60f;
            if (wy[i] > h + wr[i] * 2) {
                wy[i] = -wr[i] * 2;
                wx[i] = rng.nextFloat() * w;
            }
            if (wx[i] < -wr[i] * 3) wx[i] += w + wr[i] * 6;
            if (wx[i] > w + wr[i] * 3) wx[i] -= w + wr[i] * 6;

            float drift = (float) Math.sin(t * 0.5 + i) * 0.3f + 0.7f;
            int col = Color.argb((int)(drift * 200), 220, 235, 255);
            ptPaint.setColor(col);
            canvas.drawCircle(wx[i], wy[i], wr[i], ptPaint);
            // soft halo on larger flakes
            if (wr[i] > 4f) {
                ptPaint.setColor(Color.argb((int)(drift * 40), 200, 220, 255));
                canvas.drawCircle(wx[i], wy[i], wr[i] * 2f, ptPaint);
            }
        }
    }

    // =========================================================================
    // THUNDERSTORM
    // =========================================================================
    private void drawThunderstorm(Canvas canvas, int w, int h, float t, float dt, long now) {
        // base rain
        drawRainLayer(canvas, w, h, dt, RAIN_N, 0.30f, 42f, 0.75f, true);

        // lightning logic
        if (lightAlpha > 0f) {
            lightAlpha = Math.max(0f, lightAlpha - dt * 4.5f);
        }
        if (now > nextBolt) {
            lightAlpha = 1f;
            boltDrawn   = false;
            nextBolt    = now + 2500 + rng.nextInt(5000);
            buildBoltPath(w, h);
        }

        if (lightAlpha > 0f) {
            // full-screen flash
            flashPaint.setColor(Color.argb((int)(lightAlpha * 0.22f * 255), 220, 210, 255));
            canvas.drawRect(0, 0, w, h, flashPaint);
            // zigzag bolt
            lightningPaint.setColor(Color.argb((int)(lightAlpha * 255), 230, 220, 255));
            lightningPaint.setStrokeWidth(3f + lightAlpha * 4f);
            lightningPaint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(boltPath, lightningPaint);
            // glow around bolt
            lightningPaint.setColor(Color.argb((int)(lightAlpha * 80), 180, 160, 255));
            lightningPaint.setStrokeWidth(14f);
            canvas.drawPath(boltPath, lightningPaint);
        }
    }

    private void buildBoltPath(int w, int h) {
        boltPath.reset();
        float bx = w * (0.2f + rng.nextFloat() * 0.6f);
        float by = 0f;
        boltPath.moveTo(bx, by);
        float segH = h * 0.08f;
        while (by < h * 0.75f) {
            bx += (rng.nextFloat() - 0.5f) * w * 0.18f;
            bx  = Math.max(w * 0.1f, Math.min(w * 0.9f, bx));
            by += segH + rng.nextFloat() * segH;
            boltPath.lineTo(bx, by);
        }
    }

    // =========================================================================
    // Cloud helpers
    // =========================================================================
    private void advanceClouds(int w, float dt) {
        for (int i = 0; i < CLOUD_N; i++) {
            cx[i] += cspd[i] * dt * 60f;
            if (cx[i] > w + cw[i]) cx[i] = -cw[i];
        }
    }

    /** Draws a soft cloud puff as overlapping ovals. */
    private void drawCloudPuff(Canvas canvas, float x, float y, float pw, float ph,
                                int baseColor, float alpha) {
        int a = (int)(Color.alpha(baseColor) * alpha);
        int r = Color.red(baseColor), g = Color.green(baseColor), b = Color.blue(baseColor);
        ptPaint.setColor(Color.argb(a, r, g, b));
        // main body
        tmpRect.set(x, y - ph * 0.4f, x + pw, y + ph * 0.4f);
        canvas.drawOval(tmpRect, ptPaint);
        // upper bumps
        ptPaint.setColor(Color.argb((int)(a * 0.9f), r, g, b));
        canvas.drawCircle(x + pw * 0.25f, y - ph * 0.3f, ph * 0.45f, ptPaint);
        canvas.drawCircle(x + pw * 0.55f, y - ph * 0.38f, ph * 0.55f, ptPaint);
        canvas.drawCircle(x + pw * 0.75f, y - ph * 0.22f, ph * 0.35f, ptPaint);
    }

    private static final float TWO_PI = (float) (Math.PI * 2);
}
