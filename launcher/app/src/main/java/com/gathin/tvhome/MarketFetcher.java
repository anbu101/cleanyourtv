package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches Nifty 50 and Bank Nifty quotes from the NSE India API.
 * Polls every 15 s during market hours (9:15–15:30 IST, Mon–Fri),
 * every 10 minutes outside.
 *
 * Delivers a coloured CharSequence on the main thread, e.g.:
 *   NIFTY 24,180 ▲0.54%  ·  BANKNIFTY 51,200 ▼1.16%
 * Returns null when the fetch fails.
 */
public final class MarketFetcher {

    public interface Callback {
        void onUpdate(CharSequence formatted);
    }

    private static final String TAG = "TvHome.Market";
    private static final long   POLL_MARKET_MS = 15_000L;      // 15 s during market hours
    private static final long   POLL_IDLE_MS   = 10 * 60_000L; // 10 min outside hours
    private static final int    TIMEOUT_MS     = 10_000;

    private static final String API_URL = "https://www.nseindia.com/api/allIndices";
    private static final String[][] INDICES = {
        { "NIFTY 50",    "NIFTY" },
        { "NIFTY BANK",  "BANKNIFTY" },
    };

    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor    = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MarketFetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Callback callback;
    private volatile boolean running = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            executor.submit(() -> {
                CharSequence result = fetchAll();
                mainHandler.post(() -> {
                    if (!running) return;
                    callback.onUpdate(result);
                    mainHandler.postDelayed(pollRunnable,
                            isMarketHours() ? POLL_MARKET_MS : POLL_IDLE_MS);
                });
            });
        }
    };

    public MarketFetcher(Callback callback) {
        this.callback = callback;
    }

    public void start() {
        running = true;
        mainHandler.post(pollRunnable);
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
    }

    // ── Fetching ─────────────────────────────────────────────────────────────

    private CharSequence fetchAll() {
        try {
            String json = fetch(API_URL);
            if (json == null) return null;
            JSONArray data = new JSONObject(json).getJSONArray("data");

            StringBuilder html = new StringBuilder();
            int ok = 0;
            for (String[] entry : INDICES) {
                String indexName = entry[0];
                String label = entry[1];
                for (int i = 0; i < data.length(); i++) {
                    JSONObject obj = data.getJSONObject(i);
                    if (!indexName.equals(obj.optString("index"))) continue;

                    double last = obj.optDouble("last", 0);
                    double pctChange = obj.optDouble("percentChange", 0);
                    if (last == 0) break;

                    String price = formatPrice(last);
                    String pct = String.format("%.2f%%", Math.abs(pctChange));
                    boolean negative = pctChange < 0;
                    String arrow = negative ? "▼" : "▲";
                    String color = negative ? "#F44336" : "#4CAF50";

                    if (html.length() > 0) html.append("    <font color='#55FFFFFF'>·</font>    ");
                    html.append("<font color='#B3FFFFFF'>").append(label).append(" ")
                        .append(price).append("</font> ")
                        .append("<font color='").append(color).append("'>")
                        .append(arrow).append(pct).append("</font>");
                    ok++;
                    break;
                }
            }
            if (ok == 0) return null;
            return Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_COMPACT);
        } catch (Exception e) {
            Log.d(TAG, "fetch: " + e.getMessage());
            return null;
        }
    }

    /** Formats a price like 24862.3 → "24,862" (no decimals, with comma grouping). */
    private static String formatPrice(double price) {
        long whole = (long) price;
        StringBuilder sb = new StringBuilder(Long.toString(whole));
        for (int i = sb.length() - 3; i > 0; i -= 2) {
            sb.insert(i, ',');
        }
        return sb.toString();
    }

    private static String fetch(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36");
        conn.setInstanceFollowRedirects(true);
        try {
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** True during NSE/BSE trading hours: 9:15–15:30 IST, Monday–Friday. */
    static boolean isMarketHours() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return mins >= 555 && mins <= 930;  // 9:15 = 555 min, 15:30 = 930 min
    }
}
