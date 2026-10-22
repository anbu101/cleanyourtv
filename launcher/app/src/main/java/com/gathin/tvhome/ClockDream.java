package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private WeatherView dreamWeather;
    private WeatherFetcher dreamWeatherFetcher;
    private ImageView dreamPhoto;
    private List<File> photoFiles = Collections.emptyList();
    private int photoIndex = 0;
    private static final long PHOTO_INTERVAL_MS = 30_000L;
    private static final String PHOTO_DIR = "photos";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        dreamWeather = findViewById(R.id.dreamWeather);
        dreamPhoto = findViewById(R.id.dreamPhoto);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
        // Photo frame: scan for images in the photos subfolder.
        File photoDir = new File(getExternalFilesDir(null), PHOTO_DIR);
        if (photoDir.isDirectory()) {
            File[] imgs = photoDir.listFiles(f -> {
                String n = f.getName().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg")
                        || n.endsWith(".png") || n.endsWith(".webp");
            });
            if (imgs != null && imgs.length > 0) {
                photoFiles = new ArrayList<>(Arrays.asList(imgs));
                Collections.shuffle(photoFiles, random);
                photoIndex = 0;
                showNextPhoto();
            }
        }
        // Show weather backdrop if enabled in launcher settings.
        Config cfg = Config.load(this);
        if (cfg.weatherBg && dreamWeather != null) {
            dreamWeatherFetcher = new WeatherFetcher((scene, label) -> {
                WeatherView.Scene effective = (cfg.allowedWeatherScenes.isEmpty()
                        || cfg.allowedWeatherScenes.contains(scene.name()))
                        ? scene : WeatherView.Scene.NONE;
                dreamWeather.setScene(effective);
            });
            dreamWeatherFetcher.start();
        }
    }

    @Override
    public void onDreamingStopped() {
        if (dreamWeatherFetcher != null) {
            dreamWeatherFetcher.stop();
            dreamWeatherFetcher = null;
        }
        if (dreamWeather != null) dreamWeather.setScene(WeatherView.Scene.NONE);
        if (dreamPhoto != null) { dreamPhoto.setImageBitmap(null); dreamPhoto.setVisibility(View.GONE); }
        photoFiles = Collections.emptyList();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(photoTick);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    // ── Photo frame ──────────────────────────────────────────────────────────

    private final Runnable photoTick = this::showNextPhoto;

    private void showNextPhoto() {
        if (photoFiles.isEmpty() || dreamPhoto == null) return;
        File file = photoFiles.get(photoIndex % photoFiles.size());
        photoIndex++;
        new Thread(() -> {
            Bitmap bmp = decodeSampled(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            if (bmp == null) { handler.post(photoTick); return; }
            handler.post(() -> {
                dreamPhoto.setAlpha(0f);
                dreamPhoto.setImageBitmap(bmp);
                dreamPhoto.setVisibility(View.VISIBLE);
                dreamPhoto.animate().alpha(1f).setDuration(1500).start();
                handler.postDelayed(photoTick, PHOTO_INTERVAL_MS);
            });
        }).start();
    }

    private static Bitmap decodeSampled(File file, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            opts.inSampleSize = calcSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) { return null; }
    }

    private static int calcSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int h = opts.outHeight, w = opts.outWidth, s = 1;
        if (h > reqH || w > reqW) {
            int hRatio = Math.round((float) h / reqH);
            int wRatio = Math.round((float) w / reqW);
            s = Math.min(hRatio, wRatio);
        }
        return Math.max(1, s);
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
