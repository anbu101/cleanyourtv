package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches live cricket scores from Cricbuzz's live-scores page (HTML scraping).
 * Polls every 2 minutes when a match is live, every 5 minutes otherwise.
 * Prefers India matches; falls back to any live international match.
 *
 * Delivers a formatted score string on the main thread, e.g.:
 *   "T20I · IND 285/4 (19.6) v NZ 11/2 (3.1)"
 * Returns null when no live match is in progress.
 */
public final class CricketFetcher {

    public interface Callback {
        /** Called on the main thread. score is non-null when live, null otherwise. */
        void onScore(String score);
    }

    private static final String TAG          = "TvHome.Cricket";
    private static final long   POLL_LIVE_MS = 2 * 60_000L;
    private static final long   POLL_IDLE_MS = 5 * 60_000L;
    private static final int    TIMEOUT_MS   = 10_000;
    private static final String PAGE_URL     =
            "https://www.cricbuzz.com/cricket-match/live-scores";

    // Anchors on the embedded JSON blob inside the HTML
    private static final String  MATCH_ANCHOR = "\"matchInfo\":{\"matchId\":";
    private static final Pattern PAT_STATE    = Pattern.compile("\"state\":\"([^\"]+)\"");
    private static final Pattern PAT_FORMAT   = Pattern.compile("\"matchFormat\":\"([^\"]+)\"");
    private static final Pattern PAT_SNAME    = Pattern.compile("\"teamSName\":\"([^\"]+)\"");
    // Captures runs, optional wickets, optional overs for inngs1 or inngs2
    private static final Pattern PAT_SCORE    = Pattern.compile(
            "\"team(\\d)Score\":\\{\"inngs\\d+\":\\{\"inningsId\":\\d+,\"runs\":(\\d+)" +
            "(?:,\"wickets\":(\\d+))?(?:,\"overs\":([\\d.]+))?");

    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor    = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CricketFetch");
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
                String score = fetchScore();
                mainHandler.post(() -> {
                    if (!running) return;
                    callback.onScore(score);
                    mainHandler.postDelayed(pollRunnable,
                            score != null ? POLL_LIVE_MS : POLL_IDLE_MS);
                });
            });
        }
    };

    public CricketFetcher(Callback callback) {
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

    // -------------------------------------------------------------------------

    private String fetchScore() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(PAGE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36");
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            // Cricbuzz embeds JSON in the HTML; JSON strings are JS-escaped (\")
            String html = sb.toString().replace("\\\"", "\"");
            return parseHtml(html);
        } catch (Exception e) {
            Log.d(TAG, "fetch: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // Preference priority: 0=Mumbai, 1=Chennai, 2=India, 3=other (lower = higher priority)
    private static int teamPriority(String t1, String t2) {
        if (hasTeam(t1, t2, "MI"))  return 0;
        if (hasTeam(t1, t2, "CSK")) return 1;
        if (hasTeam(t1, t2, "IND")) return 2;
        return 3;
    }

    private static boolean hasTeam(String t1, String t2, String code) {
        return code.equalsIgnoreCase(t1) || code.equalsIgnoreCase(t2);
    }

    private String parseHtml(String html) {
        String bestScore    = null;
        int    bestPriority = Integer.MAX_VALUE;
        int pos = 0;
        while (true) {
            int idx = html.indexOf(MATCH_ANCHOR, pos);
            if (idx < 0) break;
            pos = idx + MATCH_ANCHOR.length();

            // Look at a 1500-char window around this match block
            int end = Math.min(idx + 1500, html.length());
            String chunk = html.substring(idx, end);

            // Must be In Progress
            Matcher mState = PAT_STATE.matcher(chunk);
            boolean inProgress = false;
            while (mState.find()) {
                if ("In Progress".equalsIgnoreCase(mState.group(1))) { inProgress = true; break; }
            }
            if (!inProgress) continue;

            // Match format
            String fmt = "";
            Matcher mFmt = PAT_FORMAT.matcher(chunk);
            if (mFmt.find()) fmt = fmtLabel(mFmt.group(1));

            // Team short names (first two occurrences = team1, team2)
            Matcher mName = PAT_SNAME.matcher(chunk);
            String t1 = "?", t2 = "?";
            if (mName.find()) t1 = mName.group(1);
            if (mName.find()) t2 = mName.group(1);

            // Scores
            String s1 = "", s2 = "";
            Matcher mScore = PAT_SCORE.matcher(chunk);
            while (mScore.find()) {
                String teamNum = mScore.group(1);
                String scoreStr = buildScore(mScore.group(2), mScore.group(3), mScore.group(4));
                if ("1".equals(teamNum) && s1.isEmpty()) s1 = scoreStr;
                else if ("2".equals(teamNum) && s2.isEmpty()) s2 = scoreStr;
            }

            StringBuilder result = new StringBuilder();
            if (!fmt.isEmpty()) result.append(fmt).append(" · ");
            result.append(t1);
            if (!s1.isEmpty()) result.append(" ").append(s1);
            result.append(" v ").append(t2);
            if (!s2.isEmpty()) result.append(" ").append(s2);
            String score = result.toString();

            int priority = teamPriority(t1, t2);
            if (priority < bestPriority) {
                bestPriority = priority;
                bestScore    = score;
            }
        }
        return bestScore;
    }

    private static String buildScore(String runsStr, String wicketsStr, String oversStr) {
        if (runsStr == null) return "";
        StringBuilder sb = new StringBuilder(runsStr);
        if (wicketsStr != null) {
            int wkts = Integer.parseInt(wicketsStr);
            if (wkts < 10) sb.append("/").append(wkts);
        }
        if (oversStr != null) {
            sb.append(" (").append(overs(Double.parseDouble(oversStr))).append(")");
        }
        return sb.toString();
    }

    private static String overs(double ov) {
        int full  = (int) ov;
        int balls = (int) Math.round((ov - full) * 10);
        return balls == 0 ? String.valueOf(full) : full + "." + balls;
    }

    private static String fmtLabel(String f) {
        if (f == null) return "";
        switch (f.toUpperCase()) {
            case "ODI":  return "ODI";
            case "T20":
            case "T20I": return "T20I";
            case "TEST": return "TEST";
            case "T10":  return "T10";
            default:     return f;
        }
    }
}
