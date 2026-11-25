package com.vivekanand.familytvcompanion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class CompanionServer extends Thread {
    static final int PORT = 6470;
    static final String TOKEN = BuildConfig.FAMILY_REMOTE_TOKEN;
    private static final String TAG = "FamilyTvCompanion";
    private static final int MAX_BODY_BYTES = 4096;
    private static final String POWER_STATE_PREFS = "power-state";
    private static final String SAVED_VOLUME = "saved-volume";
    private static final String SAVED_MUTED = "saved-muted";
    private static final String MUTE_STATE_PREFS = "mute-toggle-state";
    private static final String SAVED_UNMUTED_VOLUME = "saved-unmuted-volume";

    private final Context context;
    private final JioUdpController jioUdp;
    private final TvAppController tvApps;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    CompanionServer(Context context) {
        super("family-tv-companion-server");
        this.context = context;
        this.jioUdp = new JioUdpController(context);
        this.tvApps = new TvAppController(context);
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            while (running) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(5000);
                    handle(socket);
                } catch (IOException error) {
                    if (running) Log.w(TAG, "Request failed", error);
                }
            }
        } catch (IOException error) {
            if (running) Log.e(TAG, "Could not listen on port " + PORT, error);
        }
    }

    void close() {
        running = false;
        jioUdp.close();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
        }
        interrupt();
    }

    private void handle(Socket socket) throws IOException {
        InetAddress remote = socket.getInetAddress();
        if (!(remote.isSiteLocalAddress() || remote.isLinkLocalAddress() || remote.isLoopbackAddress())) {
            respond(socket.getOutputStream(), 403, "{\"message\":\"Local network access only\"}");
            return;
        }

        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isBlank()) return;
        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 2) {
            respond(socket.getOutputStream(), 400, "{\"message\":\"Invalid request\"}");
            return;
        }

        String method = requestParts[0].toUpperCase(Locale.ROOT);
        String path = requestParts[1];
        Map<String, String> headers = readHeaders(input);
        int contentLength = parseContentLength(headers.get("content-length"));
        String body = new String(readBody(input, contentLength), StandardCharsets.UTF_8);

        if ("OPTIONS".equals(method)) {
            respond(socket.getOutputStream(), 204, "");
            return;
        }
        if ("GET".equals(method) && "/health".equals(path)) {
            respond(socket.getOutputStream(), 200, "{\"ready\":true,\"service\":\"Family TV Companion\"}");
            return;
        }
        if (TOKEN.isBlank() || !TOKEN.equals(headers.get("x-family-remote-token"))) {
            respond(socket.getOutputStream(), 401, "{\"message\":\"Unauthorized\"}");
            return;
        }
        if ("POST".equals(method) && "/power/prepare-sleep".equals(path)) {
            rememberAudioState(socket.getOutputStream());
            return;
        }
        if ("POST".equals(method) && "/power/wake".equals(path)) {
            wakeDisplay(socket.getOutputStream());
            return;
        }
        if ("POST".equals(method) && "/audio/mute".equals(path)) {
            enforceMute(socket.getOutputStream());
            return;
        }
        if ("POST".equals(method) && "/audio/toggle-mute".equals(path)) {
            toggleMute(socket.getOutputStream());
            return;
        }
        if ("GET".equals(method) && "/tv/apps".equals(path)) {
            JSONArray apps = tvApps.list();
            respond(socket.getOutputStream(), 200, "{\"apps\":" + apps + "}");
            return;
        }
        if ("POST".equals(method) && "/tv/apps/open".equals(path)) {
            String appId = parseJson(body).optString("id", null);
            JSONObject result = tvApps.launch(appId);
            respond(socket.getOutputStream(), result.optBoolean("ok") ? 200 : 400, result.toString());
            return;
        }
        if ("POST".equals(method) && "/jio/scan".equals(path)) {
            respond(socket.getOutputStream(), 200, jioUdp.status().toString());
            return;
        }
        if ("POST".equals(method) && "/jio/connect".equals(path)) {
            String address = parseJson(body).optString("address", null);
            respond(socket.getOutputStream(), 200, jioUdp.connect(address).toString());
            return;
        }
        if ("GET".equals(method) && "/jio/status".equals(path)) {
            respond(socket.getOutputStream(), 200, jioUdp.status().toString());
            return;
        }
        if ("POST".equals(method) && "/jio/pin".equals(path)) {
            String pin = parseJson(body).optString("pin");
            respond(socket.getOutputStream(), 200, jioUdp.submitPin(pin).toString());
            return;
        }
        if ("POST".equals(method) && "/jio/key".equals(path)) {
            JSONObject request = parseJson(body);
            int keyCode = request.optInt("keyCode", -1);
            boolean longPress = request.optBoolean("longPress", false);
            respond(socket.getOutputStream(), 200, jioUdp.sendKey(keyCode, longPress).toString());
            return;
        }
        if ("POST".equals(method) && "/jio/text".equals(path)) {
            String text = parseJson(body).optString("text");
            respond(socket.getOutputStream(), 200, jioUdp.sendText(text).toString());
            return;
        }
        if ("POST".equals(method) && "/jio/channel".equals(path)) {
            int channelNumber = parseJson(body).optInt("channelNumber", -1);
            JSONObject result = jioUdp.openChannel(channelNumber);
            respond(socket.getOutputStream(), result.optBoolean("ok") ? 200 : 400, result.toString());
            return;
        }
        if ("POST".equals(method) && "/jio/cancel".equals(path)) {
            respond(socket.getOutputStream(), 200, jioUdp.cancel().toString());
            return;
        }
        respond(socket.getOutputStream(), 404, "{\"message\":\"Not found\"}");
    }

    @SuppressWarnings("deprecation")
    private void wakeDisplay(OutputStream output) throws IOException {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) {
            respond(output, 500, "{\"message\":\"Power service is unavailable\"}");
            return;
        }
        SharedPreferences savedState = context.getSharedPreferences(POWER_STATE_PREFS, Context.MODE_PRIVATE);
        if (power.isInteractive()) {
            savedState.edit().clear().apply();
            respond(output, 200, "{\"awake\":true}");
            return;
        }

        AudioManager audio = context.getSystemService(AudioManager.class);
        int currentVolume = audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean currentMuted = audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC);
        int previousVolume = savedState.getInt(SAVED_VOLUME, currentVolume);
        boolean previouslyMuted = savedState.getBoolean(SAVED_MUTED, currentMuted);
        PowerManager.WakeLock wakeLock = power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE,
            "FamilyTvCompanion:Wake"
        );
        try {
            wakeLock.acquire(5_000);
            context.startActivity(
                new Intent(context, WakeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            );
            for (int attempt = 0; attempt < 20 && !power.isInteractive(); attempt += 1) {
                SystemClock.sleep(100);
            }
            if (!power.isInteractive()) {
                respond(output, 500, "{\"message\":\"TV did not wake\"}");
                return;
            }
            SystemClock.sleep(1_800);
            restoreAudioState(audio, previousVolume, previouslyMuted);
            savedState.edit().clear().apply();
            respond(output, 200, "{\"awake\":true}");
        } catch (Exception error) {
            String message = JSONObject.quote(error.getMessage() == null ? "Could not wake TV" : error.getMessage());
            respond(output, 500, "{\"message\":" + message + "}");
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void rememberAudioState(OutputStream output) throws IOException {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            respond(output, 500, "{\"message\":\"Audio service is unavailable\"}");
            return;
        }
        int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        context.getSharedPreferences(POWER_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(SAVED_VOLUME, volume)
            .putBoolean(SAVED_MUTED, muted)
            .apply();
        respond(output, 200, "{\"ready\":true}");
    }

    private static void restoreAudioState(AudioManager audio, int volume, boolean muted) {
        if (audio == null || volume < 0) return;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE,
            0
        );
    }

    private void enforceMute(OutputStream output) throws IOException {
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) throw new IllegalStateException("Audio service is unavailable");
            rememberUnmutedVolume(audio);
            muteTv();
            respond(output, 200, "{\"muted\":true,\"volume\":0}");
        } catch (Exception error) {
            String message = JSONObject.quote(error.getMessage() == null ? "Could not mute TV" : error.getMessage());
            respond(output, 500, "{\"message\":" + message + "}");
        }
    }

    @SuppressWarnings("deprecation")
    private void toggleMute(OutputStream output) throws IOException {
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) throw new IllegalStateException("Audio service is unavailable");
            SharedPreferences state = context.getSharedPreferences(MUTE_STATE_PREFS, Context.MODE_PRIVATE);
            int currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean currentlyMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            if (currentlyMuted) {
                int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int fallback = Math.max(1, maximum / 10);
                int restoredVolume = currentVolume > 0
                    ? currentVolume
                    : state.getInt(SAVED_UNMUTED_VOLUME, fallback);
                audio.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    restoredVolume,
                    AudioManager.FLAG_SHOW_UI
                );
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                );
                state.edit().clear().apply();
                respond(output, 200, "{\"muted\":false,\"volume\":" + restoredVolume + "}");
            } else {
                rememberUnmutedVolume(audio);
                muteTv();
                respond(output, 200, "{\"muted\":true,\"volume\":0}");
            }
        } catch (Exception error) {
            String message = JSONObject.quote(error.getMessage() == null ? "Could not toggle TV mute" : error.getMessage());
            respond(output, 500, "{\"message\":" + message + "}");
        }
    }

    private void rememberUnmutedVolume(AudioManager audio) {
        int currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (currentVolume <= 0) return;
        context.getSharedPreferences(MUTE_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(SAVED_UNMUTED_VOLUME, currentVolume)
            .apply();
    }

    private void muteTv() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new IllegalStateException("Audio service is unavailable");
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            0,
            AudioManager.FLAG_SHOW_UI
        );
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            AudioManager.FLAG_SHOW_UI
        );
    }

    private static JSONObject parseJson(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (Exception error) {
            throw new IOException("Invalid JSON body", error);
        }
    }

    private static Map<String, String> readHeaders(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(
                    line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim()
                );
            }
        }
        return headers;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > 8192) throw new IOException("Header line is too long");
        }
        if (value == -1 && bytes.size() == 0) return null;
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseContentLength(String value) throws IOException {
        if (value == null || value.isBlank()) return 0;
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("Request body is too large");
            return length;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid Content-Length", error);
        }
    }

    private static byte[] readBody(BufferedInputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count == -1) throw new IOException("Incomplete request body");
            offset += count;
        }
        return body;
    }

    private static void respond(OutputStream output, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            default -> "Internal Server Error";
        };
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Content-Type, X-Family-Remote-Token\r\n"
            + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }
}
