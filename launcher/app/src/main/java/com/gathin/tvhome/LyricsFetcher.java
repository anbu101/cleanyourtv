package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches time-synced lyrics from <a href="https://lrclib.net">LRCLIB</a> (free, no key).
 * Returns a list of {@link Line} sorted by timestamp; the caller matches the playback position
 * to find the current line.
 */
final class LyricsFetcher {

    private static final String TAG = "TvHome.Lyrics";
    private static final int TIMEOUT_MS = 8_000;
    // [mm:ss.xx] line text
    private static final Pattern LRC_LINE = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]\\s?(.*)");

    /** A single lyric line with a millisecond timestamp. */
    static final class Line implements Comparable<Line> {
        final long timeMs;
        final String text;
        Line(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
        @Override public int compareTo(Line o) { return Long.compare(timeMs, o.timeMs); }
    }

    interface Callback {
        /** Called on the main thread. Lines is empty if nothing was found. */
        void onLyrics(List<Line> lines);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LyricsFetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Fetch lyrics for the given track. Result delivered on the main thread. */
    void fetch(String title, String artist, long durationMs, Callback callback) {
        Log.w(TAG, "fetching: " + title + " by " + artist);
        executor.submit(() -> {
            List<Line> lines = doFetch(title, artist, durationMs);
            Log.w(TAG, "result: " + lines.size() + " lines for " + title);
            mainHandler.post(() -> callback.onLyrics(lines));
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private List<Line> doFetch(String title, String artist, long durationMs) {
        // Try the exact match endpoint first
        List<Line> result = fetchGet(title, artist, durationMs);
        if (!result.isEmpty()) return result;
        // Retry without duration (some tracks have slightly different durations)
        if (durationMs > 0) {
            result = fetchGet(title, artist, 0);
            if (!result.isEmpty()) return result;
        }
        // Fall back to search
        return fetchSearch(title, artist);
    }

    private List<Line> fetchGet(String title, String artist, long durationMs) {
        try {
            StringBuilder url = new StringBuilder("https://lrclib.net/api/get?");
            url.append("track_name=").append(URLEncoder.encode(title, "UTF-8"));
            url.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"));
            if (durationMs > 0) {
                url.append("&duration=").append(durationMs / 1000);
            }
            String body = httpGet(url.toString());
            if (body == null) return Collections.emptyList();
            return extractLyrics(new JSONObject(body));
        } catch (Exception e) {
            Log.w(TAG, "fetchGet: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Line> fetchSearch(String title, String artist) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + URLEncoder.encode(title + " " + artist, "UTF-8");
            String body = httpGet(url);
            if (body == null) return Collections.emptyList();
            org.json.JSONArray arr = new org.json.JSONArray(body);
            for (int i = 0; i < Math.min(arr.length(), 5); i++) {
                List<Line> lines = extractLyrics(arr.getJSONObject(i));
                if (!lines.isEmpty()) return lines;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            Log.w(TAG, "fetchSearch: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<Line> extractLyrics(JSONObject json) {
        String synced = json.optString("syncedLyrics", "");
        if (!synced.isEmpty()) return parseLrc(synced);
        String plain = json.optString("plainLyrics", "");
        if (!plain.isEmpty()) return parsePlain(plain);
        return Collections.emptyList();
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (https://github.com/Gathin23/cleanyourtv)");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "LRCLIB " + conn.getResponseCode() + " for " + urlStr);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "httpGet: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<Line> parseLrc(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = LRC_LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            int frac = 0;
            String fracStr = m.group(3);
            if (fracStr != null) {
                // Normalise to milliseconds: "66" → 660, "6" → 600, "666" → 666
                while (fracStr.length() < 3) fracStr += "0";
                frac = Integer.parseInt(fracStr.substring(0, 3));
            }
            long ms = min * 60_000L + sec * 1000L + frac;
            String text = m.group(4).trim();
            if (!text.isEmpty()) lines.add(new Line(ms, text));
        }
        Collections.sort(lines);
        return lines;
    }

    /** Plain lyrics: no timestamps, just assign sequential second offsets so the view can scroll. */
    private static List<Line> parsePlain(String text) {
        List<Line> lines = new ArrayList<>();
        long ms = 0;
        for (String raw : text.split("\n")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                lines.add(new Line(ms, trimmed));
                ms += 4000; // arbitrary 4s per line
            }
        }
        return lines;
    }
}
