package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        // Build the same app list that the home screen shows, so the reorder
        // screen matches what is actually visible in the dock row.
        Config config = Config.load(this);
        PackageManager pm = getPackageManager();

        // Gather all launchable apps, same as AppAdapter.reload()
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<String> allPkgs = new ArrayList<>();
        List<String> allLabels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (String h : config.hiddenApps) seen.add(h);

        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (seen.contains(pkg)) continue;
            seen.add(pkg);
            allPkgs.add(pkg);
            allLabels.add(String.valueOf(ri.loadLabel(pm)));
        }

        // Sort: dock favourites first (by config order), then alphabetical — same as AppAdapter
        List<String> favs = config.dockApps;
        List<int[]> indices = new ArrayList<>();
        for (int i = 0; i < allPkgs.size(); i++) indices.add(new int[]{i});
        Collections.sort(indices, (a, b) -> {
            int fa = favs.indexOf(allPkgs.get(a[0])), fb = favs.indexOf(allPkgs.get(b[0]));
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return allLabels.get(a[0]).compareToIgnoreCase(allLabels.get(b[0]));
        });

        // Take the first COLUMNS items — that's what the dock row shows
        int dockSize = config.columns;
        int count = Math.min(dockSize, indices.size());
        for (int i = 0; i < count; i++) {
            int idx = indices.get(i)[0];
            packages.add(allPkgs.get(idx));
            labels.add(allLabels.get(idx));
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
        // Save the full dock order so Config.load() uses it on the next refresh.
        // Also pin any non-favourite apps so they stay in the dock row.
        Config config = Config.load(this);
        StringBuilder pins = new StringBuilder();
        for (String pkg : packages) {
            if (!config.dockApps.contains(pkg)) {
                if (pins.length() > 0) pins.append("|");
                pins.append(pkg);
            }
        }
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .edit()
                .putString(DOCK_ORDER_KEY, sb.toString())
                .putString("dock_pins", pins.toString())
                .apply();
        setResult(RESULT_OK);
        finish();
    }
}
