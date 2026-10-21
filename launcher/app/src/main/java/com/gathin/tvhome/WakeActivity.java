package com.gathin.tvhome;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Zero-UI activity whose only purpose is to turn the screen on.
 * Launched by RemoteService when the power button is pressed while the screen is off.
 * Sets FLAG_TURN_SCREEN_ON / setTurnScreenOn(), then immediately finishes.
 */
public final class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        finish();
    }
}
