package com.gathin.tvhome;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

/**
 * Draws a small always-on-top clock in the top-right corner of the screen,
 * visible over all apps including video players.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission, which the user grants once via
 * Settings → Special app access → Display over other apps.
 *
 * Start/stop this service to show/hide the overlay. It holds no wake locks and
 * draws nothing except one TextClock — the CPU cost is negligible.
 */
public final class OverlayClockService extends Service {

    private static final String TAG = "TvHome.OverlayClock";

    /** Checked by MainActivity before calling startService to avoid duplicate windows. */
    static volatile boolean running = false;

    private WindowManager windowManager;
    private View          overlayView;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_clock, null);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;   // legacy, API < 26
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = (int) (48 * getResources().getDisplayMetrics().density);
            params.y = (int) (28 * getResources().getDisplayMetrics().density);
            windowManager.addView(overlayView, params);
            running = true;
            Log.d(TAG, "overlay added");
        } catch (Exception e) {
            Log.w(TAG, "overlay failed: " + e.getMessage());
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
