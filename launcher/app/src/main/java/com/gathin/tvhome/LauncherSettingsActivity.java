package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private static final int DOCK_REORDER_REQUEST = 1;
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView weatherBgChoice;
    private TextView weatherScenesChoice;
    private TextView tickerChoice;
    private TextView cricketChoice;
    private boolean cricketLiveScore;
    private TextView marketChoice;
    private boolean marketTicker;
    private TextView kidsModeChoice;
    private TextView overlayClockChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean weatherBg;
    private int tickerMode;
    private boolean kidsMode;
    private boolean overlayClockEnabled;
    /** True when we navigated to the overlay permission screen; check on resume. */
    private boolean awaitingOverlayPermission = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        weatherBg = config.weatherBg;
        tickerMode = config.tickerMode;
        cricketLiveScore = config.cricketLiveScore;
        marketTicker = config.marketTicker;
        kidsMode = config.kidsMode;
        overlayClockEnabled = config.overlayClockEnabled;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        findViewById(R.id.reorderDockChoice).setOnClickListener(v ->
                startActivityForResult(new Intent(this, DockReorderActivity.class), DOCK_REORDER_REQUEST));

        weatherBgChoice = findViewById(R.id.weatherBgChoice);
        weatherBgChoice.setOnClickListener(v -> {
            weatherBg = !weatherBg;
            choices.edit().putBoolean("weather_bg", weatherBg).apply();
            setResult(RESULT_OK);
            refresh();
        });

        weatherScenesChoice = findViewById(R.id.weatherScenesChoice);
        weatherScenesChoice.setOnClickListener(v -> showWeatherScenesDialog());

        tickerChoice = findViewById(R.id.tickerChoice);
        tickerChoice.setOnClickListener(v -> {
            tickerMode = (tickerMode == TickerFetcher.MODE_OFF)
                    ? TickerFetcher.MODE_NEWS : TickerFetcher.MODE_OFF;
            choices.edit().putInt("ticker_mode", tickerMode).apply();
            setResult(RESULT_OK);
            refresh();
        });

        cricketChoice = findViewById(R.id.cricketChoice);
        cricketChoice.setOnClickListener(v -> {
            cricketLiveScore = !cricketLiveScore;
            choices.edit().putBoolean("cricket_live", cricketLiveScore).apply();
            setResult(RESULT_OK);
            refresh();
        });

        marketChoice = findViewById(R.id.marketChoice);
        marketChoice.setOnClickListener(v -> {
            marketTicker = !marketTicker;
            choices.edit().putBoolean("market_ticker", marketTicker).apply();
            setResult(RESULT_OK);
            refresh();
        });

        kidsModeChoice = findViewById(R.id.kidsModeChoice);
        kidsModeChoice.setOnClickListener(v -> {
            if (!kidsMode) {
                // Enabling: require a PIN to be set (or re-entered if already set)
                String storedPin = choices.getString("kids_pin", "");
                if (storedPin.isEmpty()) {
                    showPinDialog(getString(R.string.kids_mode_set_pin), pin -> {
                        choices.edit().putString("kids_pin", pin).putBoolean("kids_mode", true).apply();
                        kidsMode = true;
                        setResult(RESULT_OK);
                        refresh();
                    });
                } else {
                    choices.edit().putBoolean("kids_mode", true).apply();
                    kidsMode = true;
                    setResult(RESULT_OK);
                    refresh();
                }
            } else {
                // Disabling: require PIN
                showPinDialog(getString(R.string.kids_mode_enter_pin), pin -> {
                    String storedPin = choices.getString("kids_pin", "");
                    if (pin.equals(storedPin)) {
                        choices.edit().putBoolean("kids_mode", false).apply();
                        kidsMode = false;
                        setResult(RESULT_OK);
                        refresh();
                    } else {
                        Toast.makeText(this, R.string.kids_mode_wrong_pin, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        overlayClockChoice = findViewById(R.id.overlayClockChoice);
        overlayClockChoice.setOnClickListener(v -> {
            if (!overlayClockEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.overlay_clock_permission, Toast.LENGTH_LONG).show();
                    awaitingOverlayPermission = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException ignored) { }
                } else {
                    setOverlayClock(true);
                }
            } else {
                setOverlayClock(false);
            }
        });

        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        weatherBgChoice.setText(getString(R.string.weather_bg_state,
                getString(weatherBg ? R.string.on : R.string.off)));
        updateWeatherScenesLabel();
        tickerChoice.setText(getString(R.string.ticker_state,
                getString(tickerMode == TickerFetcher.MODE_OFF
                        ? R.string.ticker_mode_off : R.string.ticker_mode_news)));
        cricketChoice.setText(getString(R.string.cricket_state,
                getString(cricketLiveScore ? R.string.on : R.string.off)));
        marketChoice.setText(getString(R.string.market_state,
                getString(marketTicker ? R.string.on : R.string.off)));
        kidsModeChoice.setText(getString(R.string.kids_mode_state,
                getString(kidsMode ? R.string.on : R.string.off)));
        overlayClockChoice.setText(getString(R.string.overlay_clock_state,
                getString(overlayClockEnabled ? R.string.on : R.string.off)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                setOverlayClock(true);
            }
        }
    }

    private void setOverlayClock(boolean enabled) {
        overlayClockEnabled = enabled;
        choices.edit().putBoolean("overlay_clock", enabled).apply();
        Intent svc = new Intent(this, OverlayClockService.class);
        if (enabled) startService(svc); else stopService(svc);
        setResult(RESULT_OK);
        refresh();
    }

    /** Shows a PIN entry dialog; calls back with the entered PIN string on confirm. */
    private void showPinDialog(String title, java.util.function.Consumer<String> onConfirm) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        et.setHint("0000");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String pin = et.getText().toString().trim();
                    if (pin.length() < 4) {
                        Toast.makeText(this, R.string.kids_mode_pin_short, Toast.LENGTH_SHORT).show();
                    } else {
                        onConfirm.accept(pin);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result == RESULT_OK) { setResult(RESULT_OK); finish(); }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
    // ── Weather scenes filter ─────────────────────────────────────────────────

    private static final String[] SCENE_KEYS = {
        "CLEAR_DAY", "CLEAR_NIGHT", "CLOUDY", "FOG", "DRIZZLE", "RAIN", "SNOW", "THUNDERSTORM"
    };

    private void showWeatherScenesDialog() {
        String saved = choices.getString("weather_scenes", "");
        java.util.Set<String> enabled = new java.util.HashSet<>();
        if (!saved.isEmpty()) {
            enabled.addAll(java.util.Arrays.asList(saved.split("\\|", -1)));
        }
        boolean allEnabled = saved.isEmpty();

        String[] labels = {
            getString(R.string.weather_scene_clear),
            getString(R.string.weather_scene_clear) + " (night)",
            getString(R.string.weather_scene_cloudy),
            getString(R.string.weather_scene_fog),
            getString(R.string.weather_scene_drizzle),
            getString(R.string.weather_scene_rain),
            getString(R.string.weather_scene_snow),
            getString(R.string.weather_scene_thunderstorm)
        };
        boolean[] checked = new boolean[SCENE_KEYS.length];
        for (int i = 0; i < SCENE_KEYS.length; i++) {
            checked[i] = allEnabled || enabled.contains(SCENE_KEYS[i]);
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.weather_scenes)
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                java.util.List<String> selected = new java.util.ArrayList<>();
                for (int i = 0; i < SCENE_KEYS.length; i++) {
                    if (checked[i]) selected.add(SCENE_KEYS[i]);
                }
                // If all selected, store "" (= no filter = all allowed)
                boolean allChosen = selected.size() == SCENE_KEYS.length;
                choices.edit().putString("weather_scenes", allChosen ? "" : android.text.TextUtils.join("|", selected)).apply();
                setResult(RESULT_OK);
                updateWeatherScenesLabel();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void updateWeatherScenesLabel() {
        String saved = choices.getString("weather_scenes", "");
        String summary;
        if (saved.isEmpty()) {
            summary = "All scenes";
        } else {
            String[] parts = saved.split("\\|", -1);
            summary = parts.length + " of " + SCENE_KEYS.length + " scenes";
        }
        weatherScenesChoice.setText(getString(R.string.weather_scenes) + ": " + summary);
    }

}
