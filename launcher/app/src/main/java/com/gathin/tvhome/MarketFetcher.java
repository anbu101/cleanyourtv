package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Nifty 50, Bank Nifty and Sensex quotes from Google Finance (HTML scraping).
 * Polls every 15 s during market hours (9:15–15:30 IST, Mon–Fri),
 * every 10 minutes outside.
 *
 * Delivers a coloured CharSequence on the main thread, e.g.:
 *   NIFTY 24,180 ▲0.54%  ·  BANKNIFTY 51,200 ▼0.09%  ·  SENSEX 79,500 ▲0.62%
 * Returns null when all three fetches fail.
 */
public final class MarketFetcher {

    public interface Callback {
        void onUpdate(CharSequence formatted);
    }

    private static final String TAG = "TvHome.Market";
    private static final long   POLL_MARKET_MS = 15_000L;      // 15 s during market hours
    private static final long   POLL_IDLE_MS   = 10 * 60_000L; // 10 min outside hours
    private static final int    TIMEOUT_MS     = 10_000;

    private static final String[] URLS = {
        "https://www.google.com/finance/quote/NIFTY_50:INDEXNSE",
        "https://www.google.com/finance/quote/NIFTY_BANK:INDEXNSE",
    };
    private static final String[] LABELS = { "NIFTY", "BANKNIFTY" };

    // Hero section: first match on the page is the main quote
    private static final Pattern PAT_PRICE = Pattern.compile(
            "jsname=\"Pdsbrc\"[^>]*><span>([0-9,]+(?:\\.[0-9]+)?)</span>");
    private static final Pattern PAT_PCT   = Pattern.compile(
            "jsname=\"vY9t3b\"[^>]*><span[^>]*>([^<]+)</span>");

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
        StringBuilder html = new StringBuilder();
        int ok = 0;
        for (int i = 0; i < URLS.length; i++) {
            try {
                String page = fetch(URLS[i]);
                String price = extract(PAT_PRICE, page);
                String pct   = extract(PAT_PCT,   page);
                if (price == null || pct == null) continue;

                // Drop decimals from price for compact display
                int dot = price.indexOf('.');
                if (dot > 0) price = price.substring(0, dot);

                boolean negative = pct.trim().startsWith("-");
                String arrow = negative ? "▼" : "▲";
                String color = negative ? "#F44336" : "#4CAF50";

                if (html.length() > 0) html.append("    <font color='#55FFFFFF'>·</font>    ");
                html.append("<font color='#B3FFFFFF'>").append(LABELS[i]).append(" ")
                    .append(price).append("</font> ")
                    .append("<font color='").append(color).append("'>")
                    .append(arrow).append(pct.trim()).append("</font>");
                ok++;
            } catch (Exception e) {
                Log.d(TAG, LABELS[i] + ": " + e.getMessage());
            }
        }
        if (ok == 0) return null;
        return Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_COMPACT);
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

    private static String extract(Pattern p, String html) {
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
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
