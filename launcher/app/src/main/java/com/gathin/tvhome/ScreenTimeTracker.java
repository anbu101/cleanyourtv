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
        rollIfNewDay();
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
            // Cap the stale session to at most 24h to prevent corruption when the
            // process was killed without screenOff() (crash, APK install, force-stop).
            long elapsed = Math.max(0, Math.min(System.currentTimeMillis() - sessionStart, MAX_DAY_MS));
            oldToday += elapsed;
            ed.putLong(KEY_SESSION, System.currentTimeMillis()); // start new session
        }
        // Cap today's total to 24h
        oldToday = Math.max(0, Math.min(oldToday, MAX_DAY_MS));
        // Rotate history buckets: day_5=day_4 … day_0=oldToday
        for (int i = HISTORY - 1; i > 0; i--) {
            long prev = prefs.getLong("day_" + (i - 1), 0);
            ed.putLong("day_" + i, Math.max(0, Math.min(prev, MAX_DAY_MS)));
        }
        ed.putLong("day_0", oldToday);
        ed.putLong(KEY_TODAY, 0);
        ed.putString(KEY_DATE, today);
        ed.apply();
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
