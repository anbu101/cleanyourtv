package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tracks total TV screen-on time for today and the past 7 days.
 *
 * Usage:
 *   - Call screenOn()  when the display turns on  (ACTION_SCREEN_ON).
 *   - Call screenOff() when the display turns off (ACTION_SCREEN_OFF).
 *   - Call getDisplayText() from the UI to get e.g. "2h 40m today · 50h 1m this week".
 *
 * State is stored in SharedPreferences under the "screen_time" store and
 * survives reboots. Day boundaries are detected by yyyy-MM-dd comparison so
 * the counters roll at midnight regardless of session length.
 */
public final class ScreenTimeTracker {

    private static final String PREFS       = "screen_time";
    private static final String KEY_DATE    = "date";
    private static final String KEY_TODAY   = "today_ms";
    private static final String KEY_SESSION = "session_start";
    // 7 daily buckets: day_0 = yesterday, day_1 = 2 days ago, ..., day_5 = 6 days ago.
    // "today" lives in KEY_TODAY; on day-roll it becomes day_0 and day_0 → day_1 etc.
    private static final int    HISTORY     = 6;   // days of history (+ today = 7 total)

    /** Maximum plausible screen-on time per day: 24 hours. */
    private static final long   MAX_DAY_MS  = 24L * 60 * 60 * 1000;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /** Singleton — initialised in RemoteService, read from MainActivity. */
    public static volatile ScreenTimeTracker instance;

    private final SharedPreferences prefs;

    public ScreenTimeTracker(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sanitize();
        rollIfNewDay();
    }

    /** Clears data accumulated by buggy earlier versions; bumped when the storage format changes. */
    private static final int SANITIZE_VERSION = 2;

    private void sanitize() {
        int ver = prefs.getInt("sanitize_version", 0);
        if (ver < SANITIZE_VERSION) {
            // Earlier versions could accumulate corrupt multi-day totals. Start fresh.
            prefs.edit().clear().putInt("sanitize_version", SANITIZE_VERSION).apply();
            return;
        }
        final long SANE_MAX = 20L * 60 * 60 * 1000;
        SharedPreferences.Editor ed = null;
        long today = prefs.getLong(KEY_TODAY, 0);
        if (today > SANE_MAX || today < 0) {
            ed = prefs.edit();
            ed.putLong(KEY_TODAY, 0);
        }
        for (int i = 0; i < HISTORY; i++) {
            long day = prefs.getLong("day_" + i, 0);
            if (day > SANE_MAX || day < 0) {
                if (ed == null) ed = prefs.edit();
                ed.putLong("day_" + i, 0);
            }
        }
        if (ed != null) ed.apply();
    }

    /** Display turned on. */
    public synchronized void screenOn() {
        rollIfNewDay();
        // Always reset session_start to now.  If the process was killed without
        // screenOff() (crash, APK install), the old session_start is stale and
        // would produce a bogus elapsed time.  We lose a few seconds of accuracy
        // on normal SCREEN_ON, but avoid multi-hour/multi-day corruption.
        prefs.edit().putLong(KEY_SESSION, System.currentTimeMillis()).apply();
    }

    /** Flush the running session into today's bucket and restart it. Call periodically
     *  (e.g. every minute) so a hard power-off doesn't lose more than ~1 minute. */
    public synchronized void flush() {
        long start = prefs.getLong(KEY_SESSION, 0);
        if (start == 0) return;
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, Math.min(now - start, MAX_DAY_MS));
        if (elapsed < 1000) return; // nothing meaningful
        rollIfNewDay();
        prefs.edit()
             .putLong(KEY_TODAY, prefs.getLong(KEY_TODAY, 0) + elapsed)
             .putLong(KEY_SESSION, now)
             .apply();
    }

    /** Display turned off — flushes the current session into today's bucket. */
    public synchronized void screenOff() {
        rollIfNewDay();
        long start = prefs.getLong(KEY_SESSION, 0);
        if (start == 0) return;
        long elapsed = Math.max(0, Math.min(System.currentTimeMillis() - start, MAX_DAY_MS));
        prefs.edit()
             .putLong(KEY_TODAY, prefs.getLong(KEY_TODAY, 0) + elapsed)
             .putLong(KEY_SESSION, 0)
             .apply();
    }

    /**
     * Returns a display string like "2h 40m today  ·  50h 1m this week",
     * or null if the TV has been on for less than 1 minute today.
     */
    public synchronized String getDisplayText() {
        rollIfNewDay();
        long sessionMs = 0;
        long sessionStart = prefs.getLong(KEY_SESSION, 0);
        if (sessionStart > 0) sessionMs = Math.max(0,
                Math.min(System.currentTimeMillis() - sessionStart, MAX_DAY_MS));

        long todayMs = prefs.getLong(KEY_TODAY, 0) + sessionMs;
        if (todayMs < 60_000L) return null;

        long weekMs = todayMs;
        for (int i = 0; i < HISTORY; i++) {
            long day = prefs.getLong("day_" + i, 0);
            if (day > 0 && day <= MAX_DAY_MS) weekMs += day;  // skip corrupted entries
        }

        return fmt(todayMs) + " today  ·  " + fmt(weekMs) + " this week";
    }

    // -------------------------------------------------------------------------

    private void rollIfNewDay() {
        String today = DATE_FMT.format(new Date());
        String stored = prefs.getString(KEY_DATE, null);
        if (today.equals(stored)) return;

        SharedPreferences.Editor ed = prefs.edit();
        long sessionStart = prefs.getLong(KEY_SESSION, 0);
        long oldToday = prefs.getLong(KEY_TODAY, 0);
        if (sessionStart > 0) {
            long elapsed = Math.max(0, Math.min(System.currentTimeMillis() - sessionStart, MAX_DAY_MS));
            oldToday += elapsed;
            ed.putLong(KEY_SESSION, System.currentTimeMillis());
        }
        oldToday = Math.max(0, Math.min(oldToday, MAX_DAY_MS));

        // How many days have passed since the stored date?
        int gap = daysBetween(stored, today);

        // Rotate history buckets, inserting zeros for skipped days.
        // gap=1 is normal (yesterday); gap>1 means the TV was off for multiple days.
        for (int i = HISTORY - 1; i >= 0; i--) {
            int src = i - gap;  // which old bucket feeds this slot
            if (src == -1) {
                // This slot gets the old "today" value
                ed.putLong("day_" + i, oldToday);
            } else if (src >= 0 && src < HISTORY) {
                long prev = prefs.getLong("day_" + src, 0);
                ed.putLong("day_" + i, Math.max(0, Math.min(prev, MAX_DAY_MS)));
            } else {
                // Skipped day or out-of-range — zero
                ed.putLong("day_" + i, 0);
            }
        }
        ed.putLong(KEY_TODAY, 0);
        ed.putString(KEY_DATE, today);
        ed.apply();
    }

    /** Returns the number of days between two yyyy-MM-dd strings, or 1 if unparseable. */
    private static int daysBetween(String from, String to) {
        if (from == null || to == null) return 1;
        try {
            long fromMs = DATE_FMT.parse(from).getTime();
            long toMs = DATE_FMT.parse(to).getTime();
            int days = (int) ((toMs - fromMs) / (24L * 60 * 60 * 1000));
            return Math.max(1, days);
        } catch (Exception e) {
            return 1;
        }
    }

    private static String fmt(long ms) {
        long mins  = ms / 60_000L;
        long hours = mins / 60;
        long rem   = mins % 60;
        if (hours == 0) return rem + "m";
        if (rem   == 0) return hours + "h";
        return hours + "h " + rem + "m";
    }
}
