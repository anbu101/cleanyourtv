package com.gathin.tvhome;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Foreground service that runs a tiny HTTP server on port 8765.
 * Serves the remote-control PWA and handles button presses by injecting
 * key events into the TV without needing ADB or a computer.
 *
 * iPhone connects to http://<TV-IP>:8765 and saves it as a home-screen app.
 */
public final class RemoteService extends Service {

    private static final String TAG      = "TvHome.Remote";
    public  static final int    PORT     = 8765;
    private static final String CHAN_ID  = "remote_svc";

    private ServerSocket serverSocket;
    private Thread       serverThread;
    private AudioManager audioManager;
    // Sleep timer
    private final android.os.Handler sleepHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sleepRunnable;
    private long sleepEndMs = 0;
    private ScreenTimeTracker screenTracker;
    private BroadcastReceiver screenReceiver;

    /** Static flag so MainActivity can avoid starting a second copy. */
    public static volatile boolean running = false;

    // ── Key name → Android KeyEvent code ─────────────────────────────────────
    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        KEYS.put("up",          KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("down",        KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("left",        KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("right",       KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ok",          KeyEvent.KEYCODE_DPAD_CENTER);
        KEYS.put("back",        KeyEvent.KEYCODE_BACK);
        KEYS.put("home",        KeyEvent.KEYCODE_HOME);
        KEYS.put("menu",        KeyEvent.KEYCODE_MENU);
        KEYS.put("volume_up",   KeyEvent.KEYCODE_VOLUME_UP);
        KEYS.put("volume_down", KeyEvent.KEYCODE_VOLUME_DOWN);
        KEYS.put("mute",        KeyEvent.KEYCODE_VOLUME_MUTE);
        KEYS.put("play_pause",  KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KEYS.put("stop",        KeyEvent.KEYCODE_MEDIA_STOP);
        KEYS.put("next",        KeyEvent.KEYCODE_MEDIA_NEXT);
        KEYS.put("prev",        KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        KEYS.put("rewind",      KeyEvent.KEYCODE_MEDIA_REWIND);
        KEYS.put("ff",          KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        KEYS.put("power",       KeyEvent.KEYCODE_POWER);
        KEYS.put("search",      KeyEvent.KEYCODE_SEARCH);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        startForeground(42, buildNotification());
        startHttpServer();
        running = true;
        // Screen-time tracking
        screenTracker = new ScreenTimeTracker(this);
        ScreenTimeTracker.instance = screenTracker;
        screenTracker.screenOn(); // service starts = screen is on
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()))  screenTracker.screenOn();
                else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) screenTracker.screenOff();
                else if ("com.gathin.tvhome.FLUSH_SCREEN_TIME".equals(intent.getAction())) screenTracker.screenOff();
            }
        };
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        sf.addAction("com.gathin.tvhome.FLUSH_SCREEN_TIME");
        registerReceiver(screenReceiver, sf);
    }

    @Override
    public void onDestroy() {
        running = false;
        stopHttpServer();
        if (screenReceiver != null) { try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {} }
        if (screenTracker != null) { screenTracker.screenOff(); ScreenTimeTracker.instance = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── HTTP server ───────────────────────────────────────────────────────────

    private void startHttpServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "Remote server started on port " + PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) Log.w(TAG, "accept: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server failed: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void stopHttpServer() {
        if (serverThread != null) serverThread.interrupt();
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) {}
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(60000); // 60 s; screen recording takes ~4 s + transfer time
            InputStream rawIn = s.getInputStream();
            OutputStream out  = s.getOutputStream();

            // Read headers byte-by-byte until the blank line (\r\n\r\n).
            // We must NOT wrap in BufferedReader here — that would read-ahead into the binary body.
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(512);
            int b, b1 = -1, b2 = -1, b3 = -1;
            while ((b = rawIn.read()) != -1) {
                headerBuf.write(b);
                if (b3 == '\r' && b2 == '\n' && b1 == '\r' && b == '\n') break;
                b3 = b2; b2 = b1; b1 = b;
            }

            String[] lines = headerBuf.toString("UTF-8").split("\r\n");
            if (lines.length == 0) return;

            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path   = parts[1].split("\\?")[0];

            int    contentLength = 0;
            String contentType   = "";
            String xFilename     = "";
            for (int i = 1; i < lines.length; i++) {
                String lo = lines[i].toLowerCase();
                if (lo.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(lines[i].substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                } else if (lo.startsWith("content-type:")) {
                    contentType = lines[i].substring(13).trim();
                } else if (lo.startsWith("x-filename:")) {
                    xFilename = lines[i].substring(11).trim();
                }
            }

            // /api/cast: stream raw bytes directly to disk — never convert to String
            if ("POST".equals(method) && "/api/cast".equals(path)) {
                handleCast(contentType, xFilename, rawIn, contentLength, out);
                return;
            }

            // All other routes: read body as UTF-8 text (small JSON payloads only)
            String body = "";
            if (contentLength > 0 && "POST".equals(method)) {
                byte[] buf  = new byte[Math.min(contentLength, 8192)];
                int    read = 0;
                while (read < buf.length) {
                    int r = rawIn.read(buf, read, buf.length - read);
                    if (r < 0) break;
                    read += r;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            route(method, path, body, out);

        } catch (IOException e) {
            Log.w(TAG, "client: " + e.getMessage());
        }
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private void route(String method, String path, String body, OutputStream out)
            throws IOException {

        // OPTIONS preflight
        if ("OPTIONS".equals(method)) {
            respond(out, 204, "text/plain", new byte[0]);
            return;
        }

        // GET /api/status
        if ("GET".equals(method) && "/api/status".equals(path)) {
            boolean a11y = RemoteAccessibilityService.instance != null;
            String json = "{\"online\":true,\"accessibility\":" + a11y + "}";
            respond(out, 200, "application/json", json.getBytes());
            return;
        }

        // POST /api/key
        if ("POST".equals(method) && "/api/key".equals(path)) {
            String name = parseJsonString(body, "name");
            if ("long_ok".equals(name)) {
                sendLongOk();
                respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
                return;
            }
            Integer code = name != null ? KEYS.get(name) : null;
            if (code == null) {
                respond(out, 400, "application/json", "{\"error\":\"unknown key\"}".getBytes());
            } else {
                sendKey(code);
                respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
            }
            return;
        }

        // GET /api/volume — returns current and max volume
        if ("GET".equals(method) && "/api/volume".equals(path)) {
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            respond(out, 200, "application/json",
                    ("{\"current\":" + cur + ",\"max\":" + max + "}").getBytes());
            return;
        }

        // POST /api/volume — set volume to a specific level
        if ("POST".equals(method) && "/api/volume".equals(path)) {
            int level = parseJsonInt(body, "level");
            if (level >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level,
                        AudioManager.FLAG_SHOW_UI);
                respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
            } else {
                respond(out, 400, "application/json", "{\"error\":\"no level\"}".getBytes());
            }
            return;
        }

        // POST /api/launch — launch an app by package name or open TV settings
        if ("POST".equals(method) && "/api/launch".equals(path)) {
            String pkg = parseJsonString(body, "pkg");
            if (pkg != null && !pkg.isEmpty()) {
                try {
                    android.content.Intent launch = null;

                    if ("settings://tv".equals(pkg)) {
                        // Open TV system settings
                        launch = new android.content.Intent(
                                android.provider.Settings.ACTION_SETTINGS);
                    } else {
                        // Try standard launcher intent first, then TV leanback launcher
                        launch = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (launch == null) {
                            launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                        }
                    }

                    if (launch != null) {
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                        respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
                    } else {
                        respond(out, 404, "application/json",
                                "{\"error\":\"app not installed\"}".getBytes());
                    }
                } catch (Exception e) {
                    respond(out, 500, "application/json",
                            ("{\"error\":\"" + e.getMessage() + "\"}").getBytes());
                }
            } else {
                respond(out, 400, "application/json", "{\"error\":\"no pkg\"}".getBytes());
            }
            return;
        }

        // POST /api/text
        if ("POST".equals(method) && "/api/text".equals(path)) {
            String text = parseJsonString(body, "text");
            if (text == null || text.isEmpty()) {
                respond(out, 400, "application/json", "{\"error\":\"no text\"}".getBytes());
            } else {
                sendText(text);
                respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
            }
            return;
        }

        // GET /api/sleep — returns remaining seconds (0 = not set)
        if ("GET".equals(method) && "/api/sleep".equals(path)) {
            long remaining = sleepEndMs > 0 ? Math.max(0, (sleepEndMs - System.currentTimeMillis()) / 1000) : 0;
            respond(out, 200, "application/json",
                    ("{\"remaining\":" + remaining + "}").getBytes());
            return;
        }

        // POST /api/sleep — set or cancel sleep timer; minutes=0 cancels
        if ("POST".equals(method) && "/api/sleep".equals(path)) {
            int minutes = parseJsonInt(body, "minutes");
            if (sleepRunnable != null) { sleepHandler.removeCallbacks(sleepRunnable); sleepRunnable = null; sleepEndMs = 0; }
            if (minutes > 0) {
                sleepEndMs = System.currentTimeMillis() + minutes * 60_000L;
                sleepRunnable = () -> { sendKey(KeyEvent.KEYCODE_SLEEP); sleepRunnable = null; sleepEndMs = 0; };
                sleepHandler.postDelayed(sleepRunnable, minutes * 60_000L);
            }
            respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
            return;
        }

        // GET /api/screenshot — capture current screen via AccessibilityService
        if ("GET".equals(method) && "/api/screenshot".equals(path)) {
            RemoteAccessibilityService a11y = RemoteAccessibilityService.instance;
            if (a11y == null) {
                respond(out, 503, "application/json",
                        "{\"error\":\"Enable TV Remote Navigation in Accessibility settings\"}".getBytes());
                return;
            }
            android.graphics.Bitmap bmp = a11y.captureScreenshot();
            if (bmp == null) {
                respond(out, 500, "application/json",
                        "{\"error\":\"screenshot failed\"}".getBytes());
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, baos);
            bmp.recycle();
            respond(out, 200, "image/jpeg", baos.toByteArray());
            return;
        }

        // GET /api/record — 3 s screen recording via MediaProjection
        if ("GET".equals(method) && "/api/record".equals(path)) {
            if (MainActivity.projResultData == null) {
                // Need one-time user approval; trigger the system dialog on the TV
                MainActivity ma = MainActivity.instance;
                if (ma != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(
                            () -> ma.requestMediaProjection());
                    respond(out, 202, "application/json",
                            "{\"status\":\"approve\",\"message\":\"Press OK to approve recording on TV\"}".getBytes());
                } else {
                    respond(out, 503, "application/json",
                            "{\"error\":\"App not ready\"}".getBytes());
                }
                return;
            }
            File tmp = new File(getCacheDir(), "cytvtmp.mp4");
            try {
                recordScreen(tmp, 3);
                try (FileInputStream fis = new FileInputStream(tmp)) {
                    byte[] data = readAll(fis);
                    respond(out, 200, "video/mp4", data);
                }
            } catch (Exception e) {
                Log.w(TAG, "record: " + e.getMessage(), e);
                // If projection token is stale, clear it so next attempt re-asks
                MainActivity.projResultData = null;
                respond(out, 500, "application/json",
                        "{\"error\":\"record failed\"}".getBytes());
            } finally {
                tmp.delete();
            }
            return;
        }

        // Static assets
        String asset = resolveAsset(path);
        if (asset != null) {
            try (InputStream is = getAssets().open(asset)) {
                respond(out, 200, mimeFor(asset), readAll(is));
            } catch (IOException e) {
                respond(out, 404, "text/plain", "Not found".getBytes());
            }
        } else {
            respond(out, 404, "text/plain", "Not found".getBytes());
        }
    }

    private String resolveAsset(String path) {
        switch (path) {
            case "/":             return "remote/index.html";
            case "/manifest.json": return "remote/manifest.json";
            default:              return null;
        }
    }

    private String mimeFor(String asset) {
        if (asset.endsWith(".html")) return "text/html; charset=utf-8";
        if (asset.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ── Key injection ─────────────────────────────────────────────────────────

    private void sendKey(int keyCode) {
        switch (keyCode) {
            // Volume — handled natively, always works
            case KeyEvent.KEYCODE_VOLUME_UP:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return;

            // Media — dispatched via AudioManager, always works
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                return;

            // Home — intent, always works
            case KeyEvent.KEYCODE_HOME: {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                return;
            }

            // Back — tvapisvc Instrumentation injection works in any app (Netflix etc.)
            case KeyEvent.KEYCODE_BACK: {
                if (!injectKeyViaTvApiService(keyCode)) injectKey(keyCode);
                return;
            }

            // Power — toggle screen on/off:
            //   screen off → tvapisvc ModulePower cmd 6 (setScreenOn)
            //   screen on  → KEYCODE_SLEEP via tvapisvc (dims screen, keeps Android+HTTP alive)
            case KeyEvent.KEYCODE_POWER: {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                boolean interactive = pm == null || pm.isInteractive();
                Log.i(TAG, "POWER pressed: isInteractive=" + interactive);
                if (!interactive) {
                    if (!tvApiPower(6)) injectKey(KeyEvent.KEYCODE_POWER);
                } else {
                    // KEYCODE_SLEEP turns the screen off without deep-suspending Android,
                    // so the HTTP server stays reachable and Power ON works next time.
                    if (!injectKeyViaTvApiService(KeyEvent.KEYCODE_SLEEP)) injectKey(KeyEvent.KEYCODE_SLEEP);
                }
                return;
            }

            // D-pad / OK — tvapisvc Instrumentation injection works in any app (Netflix etc.)
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: {
                if (!injectKeyViaTvApiService(keyCode)) {
                    RemoteAccessibilityService a11y = RemoteAccessibilityService.instance;
                    if (a11y != null) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                            if (!a11y.clickFocused()) injectKey(keyCode);
                        } else {
                            if (!a11y.navigateFocus(dpadToFocusDir(keyCode))) injectKey(keyCode);
                        }
                    } else {
                        injectKey(keyCode);
                    }
                }
                return;
            }

            // Menu and anything else → shell
            default:
                injectKey(keyCode);
        }
    }

    /**
     * Handles long-press OK from the phone remote.
     * Uses AccessibilityService ACTION_LONG_CLICK on the focused node, which fires
     * View.onLongClick() — opens context menus, launcher settings shortcuts, etc.
     * Falls back to KEYCODE_MENU injection if the a11y service is unavailable.
     */
    private void sendLongOk() {
        RemoteAccessibilityService a11y = RemoteAccessibilityService.instance;
        if (a11y != null && a11y.longClickFocused()) return;
        // Fallback: KEYCODE_MENU opens the options menu in some apps
        if (!injectKeyViaTvApiService(KeyEvent.KEYCODE_MENU)) injectKey(KeyEvent.KEYCODE_MENU);
    }

    /** Maps KEYCODE_DPAD_* → View.FOCUS_* direction constants for node-based navigation. */
    private static int dpadToFocusDir(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:    return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:  return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:  return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return View.FOCUS_DOWN;
        }
    }

    /**
     * Calls ModulePower via tvapisvc binder.
     * cmd 6 = doScreenOn  (wake from standby)
     * cmd 7 = doScreenOff (put to standby)
     */
    private boolean tvApiPower(int cmd) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "tvapisvc");
            if (binder == null) { Log.w(TAG, "tvApiPower: binder null"); return false; }
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.oneplus.tvapi.ITvApi");
                data.writeString("ModulePower");
                data.writeInt(cmd); // 6=setScreenOn, 7=doScreenOff
                data.writeInt(0);   // empty String[]
                data.writeInt(0);   // boolean false → async via handler
                binder.transact(4, data, reply, 0);
                reply.readException();
                Log.i(TAG, "tvApiPower: cmd=" + cmd + " OK");
                return true;
            } finally { data.recycle(); reply.recycle(); }
        } catch (Exception e) {
            Log.w(TAG, "tvApiPower: " + e);
            return false;
        }
    }

    /**
     * Injects a key event via tvapisvc ModuleAppSwitch command 0, which calls
     * Instrumentation.sendKeyDownUpSync() in the tvapisvc system process.
     * This reaches any foreground app — including Netflix (Gibbon renderer) — without root.
     * @return true if the binder call was dispatched (fire-and-forget; actual injection is async)
     */
    private boolean injectKeyViaTvApiService(int keyCode) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "tvapisvc");
            if (binder == null) {
                Log.w(TAG, "injectKey tvapisvc: binder null");
                return false;
            }
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.oneplus.tvapi.ITvApi");
                data.writeString("ModuleAppSwitch");
                data.writeInt(0);                          // command 0 = sendKeyDownUpSync
                data.writeInt(1);                          // String[] length = 1
                data.writeString(String.valueOf(keyCode)); // strArr[0] = keyCode
                data.writeInt(0);                          // boolean false (sync dispatch)
                binder.transact(4, data, reply, 0);
                reply.readException();
                Log.i(TAG, "injectKey tvapisvc: keyCode=" + keyCode);
                return true;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "injectKey tvapisvc: " + e);
            return false;
        }
    }

    private void injectKey(int keyCode) {
        try {
            Runtime.getRuntime().exec(
                    new String[]{"/system/bin/input", "keyevent", String.valueOf(keyCode)});
        } catch (IOException e) {
            Log.w(TAG, "injectKey " + keyCode + ": " + e.getMessage());
        }
    }

    private void sendText(String text) {
        try {
            // ADB-style: spaces → %s, strip shell-dangerous chars
            String safe = text.replace(" ", "%s")
                              .replaceAll("[\"'`\\\\$&|;<>(){}]", "");
            Runtime.getRuntime().exec(
                    new String[]{"/system/bin/input", "text", safe});
        } catch (IOException e) {
            Log.w(TAG, "sendText: " + e.getMessage());
        }
    }

    // ── Cast (file upload → open on TV) ──────────────────────────────────────

    /**
     * Receives a raw binary body (photo or video) and streams it to disk,
     * then opens it on the TV with ACTION_VIEW.
     *
     * The PWA sends:
     *   POST /api/cast
     *   Content-Type: image/jpeg   (or video/mp4, etc.)
     *   X-Filename: IMG_1234.jpg   (URL-encoded)
     *   <raw file bytes>
     */
    private void handleCast(String contentType, String xFilename, InputStream rawIn,
                             int contentLength, OutputStream out) throws IOException {
        // Decode and sanitize filename
        String filename;
        try { filename = URLDecoder.decode(xFilename, "UTF-8"); }
        catch (Exception e) { filename = "cast_file"; }
        filename = new File(filename).getName();                          // strip any path components
        filename = filename.replaceAll("[^a-zA-Z0-9._\\- ]", "_").trim();
        if (filename.isEmpty()) filename = "cast_file";

        // MIME type (strip charset / boundary parameters)
        String mime = contentType.split(";")[0].trim();
        if (mime.isEmpty()) mime = "application/octet-stream";

        // Append extension if the filename has none
        String ext = extForMime(mime);
        if (!ext.isEmpty() && filename.lastIndexOf('.') < 0) filename += ext;

        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getCacheDir();
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);

        Log.i(TAG, "cast: receiving " + filename + " (" + contentLength + " bytes) mime=" + mime);

        // Stream body to file in 64 KB chunks — never loads the whole file into memory
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] chunk     = new byte[65536];
            int    remaining = contentLength;
            while (remaining > 0) {
                int n = rawIn.read(chunk, 0, Math.min(chunk.length, remaining));
                if (n < 0) break;
                fos.write(chunk, 0, n);
                remaining -= n;
            }
        } catch (IOException e) {
            Log.w(TAG, "cast write: " + e.getMessage());
            respond(out, 500, "application/json", "{\"error\":\"save failed\"}".getBytes());
            return;
        }

        Log.i(TAG, "cast: saved " + file.getAbsolutePath() + " (" + file.length() + " B)");
        openCastFile(file, mime);
        respond(out, 200, "application/json", "{\"ok\":true}".getBytes());
    }

    private static String extForMime(String mime) {
        switch (mime) {
            case "image/jpeg":       return ".jpg";
            case "image/png":        return ".png";
            case "image/gif":        return ".gif";
            case "image/webp":       return ".webp";
            case "image/heic":
            case "image/heif":       return ".heic";
            case "video/mp4":        return ".mp4";
            case "video/quicktime":  return ".mov";
            case "video/x-matroska": return ".mkv";
            case "video/webm":       return ".webm";
            default:                 return "";
        }
    }

    private void openCastFile(File file, String mime) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.gathin.tvhome.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "openCastFile: " + e.getMessage());
        }
    }

    // ── Screen recording (MediaProjection + MediaRecorder) ─────────────────

    private void recordScreen(File output, int durationSec) throws Exception {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjection projection = mpm.getMediaProjection(
                MainActivity.projResultCode, MainActivity.projResultData);
        if (projection == null) throw new Exception("projection unavailable");

        MediaRecorder recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(1280, 720);
        recorder.setVideoFrameRate(24);
        recorder.setVideoEncodingBitRate(3_000_000);
        recorder.setOutputFile(output.getAbsolutePath());
        recorder.prepare();

        VirtualDisplay vd = projection.createVirtualDisplay("cytvrecord",
                1280, 720, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);
        try {
            recorder.start();
            Log.i(TAG, "recording started (" + durationSec + " s)");
            Thread.sleep(durationSec * 1000L);
            recorder.stop();
            Log.i(TAG, "recording stopped, file=" + output.length() + " bytes");
        } finally {
            recorder.release();
            vd.release();
            projection.stop();
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static void respond(OutputStream out, int status, String mime, byte[] body)
            throws IOException {
        String phrase = status == 200 ? "OK"
                      : status == 202 ? "Accepted"
                      : status == 204 ? "No Content"
                      : status == 400 ? "Bad Request"
                      : status == 404 ? "Not Found"
                      : status == 500 ? "Internal Server Error"
                      : status == 503 ? "Service Unavailable"
                      : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(phrase).append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    /** Extracts an integer value from minimal JSON. Returns -1 if not found. */
    private static int parseJsonInt(String json, String key) {
        if (json == null) return -1;
        String marker = "\"" + key + "\"";
        int ki = json.indexOf(marker);
        if (ki < 0) return -1;
        int ci = json.indexOf(':', ki + marker.length());
        if (ci < 0) return -1;
        int start = ci + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return -1; }
    }

    /** Extracts a string value from minimal JSON without a library. */
    private static String parseJsonString(String json, String key) {
        if (json == null) return null;
        String marker = "\"" + key + "\"";
        int ki = json.indexOf(marker);
        if (ki < 0) return null;
        int ci = json.indexOf(':', ki + marker.length());
        if (ci < 0) return null;
        int q1 = json.indexOf('"', ci + 1);
        if (q1 < 0) return null;
        int q2 = q1 + 1;
        while (q2 < json.length() && json.charAt(q2) != '"') {
            if (json.charAt(q2) == '\\') q2++; // skip escaped char
            q2++;
        }
        return q2 <= json.length() ? json.substring(q1 + 1, q2) : null;
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHAN_ID, "TV Remote", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Keeps the phone remote server running");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? CHAN_ID : null)
                .setContentTitle("TV Remote active")
                .setContentText("Open on iPhone: http://TV-IP:" + PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true);
        return b.build();
    }
}
