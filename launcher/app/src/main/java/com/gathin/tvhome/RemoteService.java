package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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
    }

    @Override
    public void onDestroy() {
        running = false;
        stopHttpServer();
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
            s.setSoTimeout(8000);
            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream   out = s.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path   = parts[1].split("\\?")[0];

            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            String body = "";
            if (contentLength > 0 && "POST".equals(method)) {
                char[] buf  = new char[Math.min(contentLength, 8192)];
                int    read = 0;
                while (read < buf.length) {
                    int r = in.read(buf, read, buf.length - read);
                    if (r < 0) break;
                    read += r;
                }
                body = new String(buf, 0, read);
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
                if (!injectKeyViaTvApiService(keyCode)) {
                    RemoteAccessibilityService a11y = RemoteAccessibilityService.instance;
                    if (a11y != null) {
                        a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    } else {
                        injectKey(keyCode);
                    }
                }
                return;
            }

            // Power — toggle screen on/off:
            //   screen on  → GLOBAL_ACTION_LOCK_SCREEN (accessibility) puts TV to sleep
            //   screen off → KEYCODE_WAKEUP (224) wakes the screen
            case KeyEvent.KEYCODE_POWER: {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                boolean interactive = pm == null || pm.isInteractive();
                Log.i(TAG, "POWER pressed: isInteractive=" + interactive);
                if (!interactive) {
                    // Screen is off — wake via OnePlus tvapisvc (setScreenOn).
                    // Falls back to KEYCODE_POWER injection if the binder call fails.
                    Log.i(TAG, "POWER: calling tvapisvc setScreenOn");
                    if (!wakeScreenViaTvApiService()) {
                        injectKey(KeyEvent.KEYCODE_POWER);
                    }
                } else {
                    // Screen is on — put it to sleep via accessibility
                    RemoteAccessibilityService a11y = RemoteAccessibilityService.instance;
                    if (a11y != null) {
                        if (!a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                            injectKey(keyCode);
                        }
                    } else {
                        injectKey(keyCode);
                    }
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
     * Wakes the TV screen via OnePlus's proprietary tvapisvc binder service.
     * Equivalent to ITvPowerManager.setScreenOn() which calls
     * disPatchCommand("ModulePower", 6, null, false) on the ITvApi binder.
     * Transaction code 4, descriptor com.oneplus.tvapi.ITvApi.
     */
    private boolean wakeScreenViaTvApiService() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "tvapisvc");
            if (binder == null) {
                Log.w(TAG, "wakeScreen: tvapisvc binder is null");
                return false;
            }
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.oneplus.tvapi.ITvApi");
                data.writeString("ModulePower");
                data.writeInt(6);   // command 6 = setScreenOn
                data.writeInt(0);   // empty String[] — server does strArr.length unconditionally
                data.writeInt(0);   // boolean false
                binder.transact(4, data, reply, 0);
                reply.readException();
                Log.i(TAG, "wakeScreen: tvapisvc setScreenOn OK");
                return true;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "wakeScreen: " + e);
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

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static void respond(OutputStream out, int status, String mime, byte[] body)
            throws IOException {
        String phrase = status == 200 ? "OK" : status == 204 ? "No Content"
                      : status == 400 ? "Bad Request" : "Not Found";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(phrase).append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
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
