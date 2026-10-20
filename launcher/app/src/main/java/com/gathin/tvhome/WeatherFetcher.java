package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches the current weather for the device's approximate location (IP geolocation) and maps it
 * to a {@link WeatherView.Scene} plus a short display label (e.g. "28° · Drizzle").
 *
 * Sources (both free, no API key, HTTPS):
 *   - Location: ipwho.is        — lat/lon from public IP, no Android permissions needed
 *   - Weather:  api.open-meteo.com — WMO weather code, is_day, temperature_2m
 *
 * Refreshes every 30 minutes on a single daemon background thread.
 */
public final class WeatherFetcher {

    public interface Callback {
        /** Called on the main thread after each successful or failed fetch. */
        void onResult(WeatherView.Scene scene, String label);
    }

    private static final String TAG       = "TvHome.Weather";
    private static final long   REFRESH_MS = 10 * 60 * 1000L;
    private static final int    TIMEOUT_MS = 8_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeatherFetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler  mainHandler     = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private volatile boolean stopped = false;
    private final Runnable refreshRunnable = this::fetchAndSchedule;

    public WeatherFetcher(Callback callback) {
        this.callback = callback;
    }

    /** Fetch immediately, then schedule a refresh every 30 minutes. */
    public void start() {
        stopped = false;
        fetchAndSchedule();
    }

    /** Cancel any pending refresh. Safe to call more than once. */
    public void stop() {
        stopped = true;
        mainHandler.removeCallbacks(refreshRunnable);
    }

    // -------------------------------------------------------------------------

    private void fetchAndSchedule() {
        executor.submit(() -> {
            Result r = fetch();
            if (!stopped) {
                mainHandler.post(() -> callback.onResult(r.scene, r.label));
                mainHandler.postDelayed(refreshRunnable, REFRESH_MS);
            }
        });
    }

    private static final class Result {
        final WeatherView.Scene scene;
        final String label;
        Result(WeatherView.Scene scene, String label) { this.scene = scene; this.label = label; }
    }

    private Result fetch() {
        try {
            // 1. IP geolocation (HTTPS, free, no permissions beyond INTERNET)
            JSONObject geo = httpGet("https://ipwho.is/");
            if (geo == null || !geo.optBoolean("success", false)) {
                Log.w(TAG, "geo lookup failed");
                return new Result(WeatherView.Scene.NONE, "");
            }
            double lat = geo.getDouble("latitude");
            double lon = geo.getDouble("longitude");

            // 2. Current conditions from Open-Meteo
            String url = "https://api.open-meteo.com/v1/forecast"
                    + "?latitude="  + lat
                    + "&longitude=" + lon
                    + "&current=weather_code,is_day,temperature_2m"
                    + "&forecast_days=1";
            JSONObject weather = httpGet(url);
            if (weather == null) {
                Log.w(TAG, "weather fetch failed");
                return new Result(WeatherView.Scene.NONE, "");
            }
            JSONObject current = weather.optJSONObject("current");
            if (current == null) return new Result(WeatherView.Scene.NONE, "");

            int     code  = current.optInt("weather_code", -1);
            boolean day   = current.optInt("is_day", 1) == 1;
            double  tempC = current.optDouble("temperature_2m", Double.NaN);

            WeatherView.Scene scene = wmoToScene(code, day);
            String label = buildLabel(tempC, code);
            Log.d(TAG, "lat=" + lat + " lon=" + lon + " code=" + code
                    + " day=" + day + " temp=" + tempC + " -> " + scene);
            return new Result(scene, label);

        } catch (Exception e) {
            Log.w(TAG, "fetch error: " + e.getMessage());
            return new Result(WeatherView.Scene.NONE, "");
        }
    }

    // -------------------------------------------------------------------------
    // HTTP helper
    // -------------------------------------------------------------------------

    private static JSONObject httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "httpGet " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // WMO code → Scene
    // -------------------------------------------------------------------------

    private static WeatherView.Scene wmoToScene(int code, boolean day) {
        if (code < 0) return WeatherView.Scene.NONE;
        if (code <= 2)              return day ? WeatherView.Scene.CLEAR_DAY : WeatherView.Scene.CLEAR_NIGHT;
        if (code == 3)              return WeatherView.Scene.CLOUDY;
        if (code == 45 || code == 48) return WeatherView.Scene.FOG;
        if (code >= 51 && code <= 57) return WeatherView.Scene.DRIZZLE;
        if (code >= 61 && code <= 65) return WeatherView.Scene.RAIN;
        if (code >= 66 && code <= 67) return WeatherView.Scene.DRIZZLE;
        if (code >= 71 && code <= 77) return WeatherView.Scene.SNOW;
        if (code >= 80 && code <= 82) return WeatherView.Scene.RAIN;
        if (code == 85 || code == 86) return WeatherView.Scene.SNOW;
        if (code >= 95)               return WeatherView.Scene.THUNDERSTORM;
        return day ? WeatherView.Scene.CLEAR_DAY : WeatherView.Scene.CLEAR_NIGHT;
    }

    // -------------------------------------------------------------------------
    // WMO code → short English label
    // -------------------------------------------------------------------------

    private static String buildLabel(double tempC, int code) {
        String cond = wmoCondition(code);
        if (Double.isNaN(tempC)) return cond;
        int t = (int) Math.round(tempC);
        return t + "\u00B0" + " \u00B7 " + cond;   // e.g.  28° · Drizzle
    }

    private static String wmoCondition(int code) {
        if (code == 0)  return "Clear";
        if (code == 1)  return "Mostly clear";
        if (code == 2)  return "Partly cloudy";
        if (code == 3)  return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 55) return "Drizzle";
        if (code == 56 || code == 57) return "Freezing drizzle";
        if (code == 61 || code == 63) return "Rain";
        if (code == 65) return "Heavy rain";
        if (code == 66 || code == 67) return "Freezing rain";
        if (code == 71 || code == 73) return "Snow";
        if (code == 75) return "Heavy snow";
        if (code == 77) return "Snow grains";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code == 95) return "Thunderstorm";
        if (code == 96 || code == 99) return "Thunderstorm";
        return "Unknown";
    }
}
