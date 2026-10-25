package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Polls the active {@link MediaSessionManager} every 2 seconds and reports the currently playing
 * track (title, artist, duration, playback position) to a callback on the main thread.
 *
 * Requires {@code MediaNotificationListener} to be an enabled {@code NotificationListenerService}
 * so that {@code getActiveSessions()} returns results.
 */
final class NowPlayingListener {

    private static final String TAG = "TvHome.NowPlaying";
    private static final long POLL_MS = 2_000L;

    interface Callback {
        /** Called on the main thread. All fields may be null/0 when nothing is playing. */
        void onTrackChanged(String title, String artist, long durationMs);
        /** Called on the main thread with the current position while playing. */
        void onPositionUpdate(long positionMs, boolean playing);
    }

    private final Context ctx;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName listenerComponent;
    private volatile boolean running;

    private String lastTitle = "";
    private String lastArtist = "";

    NowPlayingListener(Context ctx, Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.callback = callback;
        listenerComponent = new ComponentName(ctx, MediaNotificationListener.class);
    }

    void start() {
        running = true;
        handler.post(pollRunnable);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void poll() {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;

            List<MediaController> controllers;
            try {
                controllers = msm.getActiveSessions(listenerComponent);
            } catch (SecurityException e) {
                Log.w(TAG, "getActiveSessions denied — notification listener not enabled");
                return;
            }
            Log.d(TAG, "sessions: " + controllers.size());
            if (controllers.isEmpty()) {
                if (!lastTitle.isEmpty()) {
                    lastTitle = "";
                    lastArtist = "";
                    callback.onTrackChanged(null, null, 0);
                    callback.onPositionUpdate(0, false);
                }
                return;
            }

            MediaController mc = controllers.get(0);
            PlaybackState state = mc.getPlaybackState();
            MediaMetadata meta = mc.getMetadata();

            boolean playing = state != null
                    && state.getState() == PlaybackState.STATE_PLAYING;
            Log.d(TAG, "state=" + (state != null ? state.getState() : "null")
                    + " meta=" + (meta != null ? meta.getDescription() : "null"));

            if (meta != null) {
                String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);

                if (title == null) title = "";
                if (artist == null) artist = "";
                Log.w(TAG, "got title=[" + title + "] artist=[" + artist
                        + "] last=[" + lastTitle + "] dur=" + duration);

                if (!title.equals(lastTitle) || !artist.equals(lastArtist)) {
                    lastTitle = title;
                    lastArtist = artist;
                    Log.d(TAG, "track: " + title + " by " + artist + " (" + duration + "ms)");
                    callback.onTrackChanged(title, artist, duration);
                }
            }

            if (state != null) {
                long pos = state.getPosition();
                // Compensate for time elapsed since last position update
                if (playing && state.getLastPositionUpdateTime() > 0) {
                    long elapsed = android.os.SystemClock.elapsedRealtime()
                            - state.getLastPositionUpdateTime();
                    pos += (long) (elapsed * state.getPlaybackSpeed());
                }
                callback.onPositionUpdate(pos, playing);
            }
        } catch (Exception e) {
            Log.w(TAG, "poll: " + e.getMessage());
        }
    }
}
