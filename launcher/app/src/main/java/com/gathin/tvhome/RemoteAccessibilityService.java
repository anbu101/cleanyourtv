package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Accessibility service used exclusively by the phone remote control.
 * Allows Back and D-pad actions to be delivered to any foreground app
 * without needing root.
 *
 * The user enables it once:
 *   TV Settings → Device Preferences → Accessibility → TV Remote Navigation → ON
 */
public final class RemoteAccessibilityService extends AccessibilityService {

    private static final String TAG = "TvHome.RemoteA11y";

    /** Non-null when the service is bound and active. */
    public static volatile RemoteAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.i(TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        Log.i(TAG, "disconnected");
        super.onDestroy();
    }

    /**
     * Moves the TV selection (input focus) in a D-pad direction.
     * Uses focusSearch() to find the next focusable view, then ACTION_FOCUS
     * which calls requestFocus() on it — this moves the TV's selection highlight,
     * unlike ACTION_ACCESSIBILITY_FOCUS which only moves the TalkBack cursor.
     *
     * @param direction View.FOCUS_UP / FOCUS_DOWN / FOCUS_LEFT / FOCUS_RIGHT
     * @return true if a node was found and focused
     */
    public boolean navigateFocus(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null) return false;
            try {
                AccessibilityNodeInfo next = focused.focusSearch(direction);
                if (next == null) return false;
                boolean ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                next.recycle();
                return ok;
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Clicks the currently focused node. Used for the OK / D-pad centre button.
     */
    public boolean clickFocused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null) return false;
            try {
                return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Takes a screenshot of the default display and returns it as a software Bitmap.
     * Uses the AccessibilityService.takeScreenshot() API (added in API 30).
     * Blocks the calling thread (max 8 s) until the screenshot is ready.
     * Returns null if the service is not connected or the capture fails.
     */
    public Bitmap captureScreenshot() {
        CountDownLatch latch = new CountDownLatch(1);
        Bitmap[] holder = new Bitmap[1];
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                android.hardware.HardwareBuffer buf = result.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buf, result.getColorSpace());
                                if (hw != null) {
                                    holder[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                }
                                buf.close();
                            } catch (Throwable t) {
                                Log.w(TAG, "screenshot convert: " + t.getMessage());
                            }
                            latch.countDown();
                        }
                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "takeScreenshot failure: " + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "captureScreenshot: " + e.getMessage());
        }
        return holder[0];
    }

    /**
     * Long-clicks the currently focused node. Used for the long-press OK button.
     * Triggers View.onLongClick() on the focused item — opens context menus,
     * launcher settings shortcuts, etc.
     */
    public boolean longClickFocused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null) return false;
            try {
                return focused.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }
}
