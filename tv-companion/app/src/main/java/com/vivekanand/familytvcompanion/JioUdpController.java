package com.vivekanand.familytvcompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

final class JioUdpController {
    private static final String TAG = "JioUdpController";
    private static final int CONTROL_PORT = 4445;
    private static final int BEACON_PORT = 5775;
    private static final int MAX_PACKET_BYTES = 1024;
    private static final int MAX_EVENTS = 20;
    private static final long HOME_SETTLE_MS = 2_000;
    private static final long LIVE_TV_SETTLE_MS = 8_000;
    private static final long DIGIT_SETTLE_MS = 400;
    private static final String PREFS = "jio_udp_controller";

    private final Context context;
    private final String clientId;
    private final SharedPreferences preferences;
    private final Object socketLock = new Object();
    private final Object eventLock = new Object();
    private final Deque<JSONObject> events = new ArrayDeque<>();

    private volatile boolean running = true;
    private volatile String targetAddress;
    private volatile String dnsName;
    private volatile String pairCode;
    private volatile String serverPairCode;
    private volatile String lastResponse;
    private volatile String lastError;
    private volatile Boolean screenOn;
    private DatagramSocket controlSocket;
    private DatagramSocket beaconSocket;
    private WifiManager.MulticastLock multicastLock;

    JioUdpController(Context context) {
        Context application = context.getApplicationContext();
        this.context = context.isDeviceProtectedStorage() ? context : application;
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        clientId = loadClientId(this.context);
        targetAddress = preferences.getString("targetAddress", null);
        dnsName = preferences.getString("dnsName", null);
        pairCode = preferences.getString("pairCode", null);
        acquireMulticastLock();
        startListener("jio-control-4445", CONTROL_PORT, true);
        startListener("jio-beacon-5775", BEACON_PORT, false);
    }

    JSONObject connect(String address) {
        if (address != null && !address.isBlank()) {
            targetAddress = address.trim();
            preferences.edit().putString("targetAddress", targetAddress).apply();
        }
        serverPairCode = null;
        return send("CONNECT:" + clientId);
    }

    JSONObject submitPin(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            return result(false, "PIN must contain four digits");
        }
        return send("PAIRCODE:" + clientId + ':' + pin);
    }

    JSONObject sendKey(int keyCode, boolean longPress) {
        if (keyCode < 0) return result(false, "A non-negative Android key code is required");
        if (pairCode == null || pairCode.isBlank()) return result(false, "JioSTB is not paired");
        String message = "KEYCODE:" + clientId + ':' + pairCode + ':' + keyCode;
        if (longPress) message += ":longpress";
        return send(message);
    }

    JSONObject sendText(String text) {
        if (text == null || text.isEmpty()) return result(false, "Text is required");
        if (pairCode == null || pairCode.isBlank()) return result(false, "JioSTB is not paired");
        return send("KEYINPUTTEXT::" + clientId + "::" + pairCode + "::" + text);
    }

    JSONObject cancel() {
        return send("CANCELLED");
    }

    synchronized JSONObject openChannel(int channelNumber) {
        if (!ChannelNumber.isValid(channelNumber)) {
            return result(false, "Channel number must contain one to four digits");
        }
        if (pairCode == null || pairCode.isBlank()) return result(false, "JioSTB is not paired");
        try {
            int[] keyCodes = ChannelNumber.tuneKeyCodes(channelNumber, screenOn);
            for (int index = 0; index < keyCodes.length; index += 1) {
                requireSent(sendKey(keyCodes[index], false));
                if (index == 0 && keyCodes[index] == 26) {
                    if (!waitForScreenOn(10_000)) {
                        throw new IllegalStateException("Jio box did not restore its HDMI signal");
                    }
                    Thread.sleep(750);
                } else if (keyCodes[index] == 3) {
                    Thread.sleep(HOME_SETTLE_MS);
                } else if (keyCodes[index] == 170) {
                    Thread.sleep(LIVE_TV_SETTLE_MS);
                } else {
                    Thread.sleep(index == 0 ? 750 : DIGIT_SETTLE_MS);
                }
            }
            // JioHome's own number pad sends only digit keycodes. JioTV+
            // auto-tunes after its numeric-entry timeout; Enter/DPAD_CENTER
            // can instead activate whichever control currently has focus.
            Thread.sleep(4000);
            return result(true, "Channel number sent; JioTV+ auto-tune timeout elapsed");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return result(false, "Channel selection was interrupted");
        } catch (IllegalStateException error) {
            return result(false, error.getMessage());
        }
    }

    private boolean waitForScreenOn(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(screenOn)) return true;
            Thread.sleep(200);
        }
        return Boolean.TRUE.equals(screenOn);
    }

    private static void requireSent(JSONObject result) {
        if (!result.optBoolean("ok")) {
            throw new IllegalStateException(result.optString("message", "JioSTB command failed"));
        }
    }

    JSONObject status() {
        JSONObject value = new JSONObject();
        put(value, "ready", running && controlSocket != null && beaconSocket != null);
        put(value, "clientId", clientId);
        put(value, "targetAddress", targetAddress == null ? JSONObject.NULL : targetAddress);
        put(value, "dnsName", dnsName == null ? JSONObject.NULL : dnsName);
        put(value, "paired", pairCode != null && !pairCode.isBlank());
        put(value, "pairingCodeReceived", serverPairCode != null && !serverPairCode.isBlank());
        put(value, "lastResponse", lastResponse == null ? JSONObject.NULL : redact(lastResponse));
        put(value, "lastError", lastError == null ? JSONObject.NULL : lastError);
        put(value, "screenOn", screenOn == null ? JSONObject.NULL : screenOn);
        put(value, "controlPort", CONTROL_PORT);
        put(value, "beaconPort", BEACON_PORT);
        JSONArray recent = new JSONArray();
        synchronized (eventLock) {
            for (JSONObject event : events) recent.put(event);
        }
        put(value, "recentEvents", recent);
        return value;
    }

    void close() {
        running = false;
        synchronized (socketLock) {
            if (controlSocket != null) controlSocket.close();
            if (beaconSocket != null) beaconSocket.close();
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    private JSONObject send(String message) {
        String address = targetAddress;
        if (address == null || address.isBlank()) return result(false, "No JioSTB has been discovered or selected");
        try {
            DatagramSocket socket;
            synchronized (socketLock) {
                socket = controlSocket;
            }
            if (socket == null || socket.isClosed()) return result(false, "UDP control socket is not ready");
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(address), CONTROL_PORT));
            record("sent", address, message);
            return result(true, "Sent");
        } catch (Exception error) {
            lastError = error.toString();
            Log.w(TAG, "Could not send Jio UDP packet", error);
            return result(false, lastError);
        }
    }

    private void startListener(String name, int port, boolean control) {
        Thread thread = new Thread(() -> listen(port, control), name);
        thread.setDaemon(true);
        thread.start();
    }

    private void listen(int port, boolean control) {
        try {
            DatagramSocket socket = createSocket(port);
            synchronized (socketLock) {
                if (control) controlSocket = socket;
                else beaconSocket = socket;
            }
            while (running) {
                DatagramPacket packet = new DatagramPacket(new byte[MAX_PACKET_BYTES], MAX_PACKET_BYTES);
                socket.receive(packet);
                String message = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8
                );
                String sender = packet.getAddress().getHostAddress();
                record(control ? "response" : "beacon", sender, message);
                if (control) handleResponse(sender, message);
                else handleBeacon(sender, message);
            }
        } catch (Exception error) {
            if (running) {
                lastError = "UDP " + port + ": " + error;
                Log.e(TAG, "UDP listener failed on " + port, error);
            }
        }
    }

    private static DatagramSocket createSocket(int port) throws Exception {
        DatagramSocket socket = new DatagramSocket((SocketAddress) null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(port));
        return socket;
    }

    private void handleResponse(String sender, String message) {
        lastResponse = message;
        targetAddress = sender;
        String[] fields = message.trim().split(":", 3);
        if (fields.length >= 3 && "PAIR".equalsIgnoreCase(fields[0])) {
            dnsName = fields[1];
            serverPairCode = fields[2];
            preferences.edit()
                .putString("targetAddress", targetAddress)
                .putString("dnsName", dnsName)
                .apply();
        } else if (fields.length >= 3 && "CONNECTED".equalsIgnoreCase(fields[0])) {
            dnsName = fields[1];
            pairCode = fields[2];
            serverPairCode = null;
            preferences.edit()
                .putString("targetAddress", targetAddress)
                .putString("dnsName", dnsName)
                .putString("pairCode", pairCode)
                .apply();
        }
    }

    private void handleBeacon(String sender, String message) {
        String[] fields = message.trim().split(":");
        if (fields.length < 2 || !"beacon".equalsIgnoreCase(fields[0])) return;
        targetAddress = sender;
        dnsName = fields[1];
        screenOn = ChannelNumber.screenOnFromBeacon(message);
        preferences.edit()
            .putString("targetAddress", targetAddress)
            .putString("dnsName", dnsName)
            .apply();
    }

    private void record(String type, String sender, String message) {
        JSONObject event = new JSONObject();
        put(event, "time", System.currentTimeMillis());
        put(event, "type", type);
        put(event, "sender", sender);
        put(event, "message", redact(message));
        synchronized (eventLock) {
            events.addFirst(event);
            while (events.size() > MAX_EVENTS) events.removeLast();
        }
    }

    private static String redact(String message) {
        if (message.startsWith("PAIRCODE:")) {
            String[] fields = message.split(":", 3);
            return fields.length == 3 ? fields[0] + ':' + fields[1] + ":<redacted>" : "PAIRCODE:<redacted>";
        }
        if (message.startsWith("PAIR:") || message.startsWith("CONNECTED:")) {
            String[] fields = message.split(":", 3);
            return fields.length == 3 ? fields[0] + ':' + fields[1] + ":<redacted>" : message;
        }
        if (message.startsWith("KEYCODE:")) {
            String[] fields = message.split(":", 4);
            return fields.length == 4 ? fields[0] + ':' + fields[1] + ":<redacted>:" + fields[3] : "KEYCODE:<redacted>";
        }
        if (message.startsWith("KEYINPUTTEXT::")) return "KEYINPUTTEXT::<redacted>";
        return message;
    }

    private JSONObject result(boolean ok, String message) {
        JSONObject value = status();
        put(value, "ok", ok);
        put(value, "message", message);
        return value;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            if (wifi == null) return;
            multicastLock = wifi.createMulticastLock("family-tv-jio-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception error) {
            lastError = "Multicast lock: " + error;
        }
    }

    private static String loadClientId(Context context) {
        try {
            Process process = new ProcessBuilder("/system/bin/getprop", "net.hostname").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String hostname = reader.readLine();
                if (hostname != null && !hostname.isBlank() && !"NA".equals(hostname)) return hostname.trim();
            }
        } catch (Exception ignored) { }
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return androidId == null || androidId.isBlank() ? "family-tv-remote" : androidId;
    }
}
