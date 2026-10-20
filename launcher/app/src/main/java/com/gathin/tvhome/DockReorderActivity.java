package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lets the user reorder dock apps with the D-pad remote.
 * OK on a row grabs it; ↑↓ move it; OK again drops it; "Save" writes the new order to
 * SharedPreferences so Config.load() picks it up on the next home-screen refresh.
 */
public final class DockReorderActivity extends Activity {

    static final String DOCK_ORDER_KEY = "dock_order";

    private final List<String> packages = new ArrayList<>();
    private final List<String> labels   = new ArrayList<>();
    private final List<TextView> rows   = new ArrayList<>();
    private int grabbedIndex = -1;
    private TextView hintView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dock_reorder);

        hintView = findViewById(R.id.reorder_hint);

        // Current dock order (already includes any saved dock_order pref via Config.load)
        Config config = Config.load(this);
        packages.addAll(config.dockApps);

        PackageManager pm = getPackageManager();
        for (String pkg : packages) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                labels.add(String.valueOf(pm.getApplicationLabel(ai)));
            } catch (PackageManager.NameNotFoundException e) {
                labels.add(pkg);   // fallback: show package name
            }
        }

        LinearLayout listContainer = findViewById(R.id.dock_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < packages.size(); i++) {
            TextView row = (TextView) inflater.inflate(R.layout.item_dock_row, listContainer, false);
            final int pos = i;
            row.setOnClickListener(v -> onRowClick(pos));
            rows.add(row);
            listContainer.addView(row);
        }

        findViewById(R.id.reorder_done).setOnClickListener(v -> save());

        refreshViews();
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    private void onRowClick(int viewPos) {
        if (grabbedIndex == -1) {
            grabbedIndex = viewPos;
        } else if (grabbedIndex == viewPos) {
            grabbedIndex = -1;  // drop in place
        }
        // Click on a different row while grabbed: ignore (user must drop first)
        refreshViews();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && grabbedIndex >= 0) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_DPAD_UP && grabbedIndex > 0) {
                moveGrabbed(grabbedIndex - 1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_DOWN && grabbedIndex < packages.size() - 1) {
                moveGrabbed(grabbedIndex + 1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (grabbedIndex >= 0) {
            grabbedIndex = -1;   // drop without moving, stay on screen
            refreshViews();
        } else {
            super.onBackPressed();   // leave without saving
        }
    }

    private void moveGrabbed(int newIndex) {
        Collections.swap(packages, grabbedIndex, newIndex);
        Collections.swap(labels,   grabbedIndex, newIndex);
        grabbedIndex = newIndex;
        refreshViews();
    }

    private void refreshViews() {
        for (int i = 0; i < rows.size(); i++) {
            boolean grabbed = (i == grabbedIndex);
            rows.get(i).setSelected(grabbed);
            String prefix = grabbed ? "↕  " : (i + 1) + ".  ";
            rows.get(i).setText(prefix + labels.get(i));
        }
        if (grabbedIndex >= 0) {
            rows.get(grabbedIndex).requestFocus();
            hintView.setText(getString(R.string.reorder_hint_moving));
        } else {
            hintView.setText(getString(R.string.reorder_hint));
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < packages.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(packages.get(i));
        }
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .edit().putString(DOCK_ORDER_KEY, sb.toString()).apply();
        setResult(RESULT_OK);
        finish();
    }
}
