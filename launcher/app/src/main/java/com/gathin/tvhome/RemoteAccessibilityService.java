package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
}
