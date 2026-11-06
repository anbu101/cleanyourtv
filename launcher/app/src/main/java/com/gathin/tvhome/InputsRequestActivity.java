package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Answers com.android.tv.action.VIEW_INPUTS, the system input switcher that the remote's Input button turns into
 * (through the TV's Live TV app, or InputKeyService), by bringing Home forward with its Inputs menu open. The
 * component is disabled in the manifest and can be enabled via ADB when handle_view_inputs is needed,
 * so a TV with its own input picker is never offered a chooser. No UI of its own (Theme.NoDisplay).
 */
public class InputsRequestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
