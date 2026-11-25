package com.codeplay.tv.remote;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "RedmiRemote")
public class RedmiRemotePlugin extends Plugin {
    private static final String TAG = "RedmiRemotePlugin";
    private static final String REMOTE_SERVICE = "_androidtvremote2._tcp.";
    private static final long DISCOVERY_MS = 5_000;

    private final Object discoveryLock = new Object();
    private final Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private NsdManager nsd;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private NsdManager.DiscoveryListener discoveryListener;
    private PluginCall discoveryCall;
    private boolean discoveryStarted;
    private boolean resolving;
    private WifiManager.MulticastLock multicastLock;
    private AndroidTvRemoteClient remoteClient;

    private final Runnable discoveryTimeout = () -> finishDiscovery(null);

    @Override
    public void load() {
        Context context = getContext().getApplicationContext();
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { notifyNetworkChanged(); }
                @Override public void onLost(Network network) { notifyNetworkChanged(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { notifyNetworkChanged(); }
            };
            try {
                connectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                    networkCallback
                );
            } catch (Exception error) {
                Log.w(TAG, "Could not monitor network changes", error);
            }
        }
        remoteClient = new AndroidTvRemoteClient(context);
        remoteClient.setStateListener(state -> notifyListeners("stateChanged", stateResult(state), true));
        remoteClient.setVolumeListener(volume -> {
            JSObject result = new JSObject();
            result.put("available", volume != null);
            result.put("ip", remoteClient.getState().ip);
            if (volume != null) {
                result.put("level", volume.level);
                result.put("max", volume.max);
                result.put("muted", volume.muted);
            }
            notifyListeners("volumeChanged", result, true);
        });
    }

    @PluginMethod
    public void discover(PluginCall call) {
        mainHandler.post(() -> startDiscovery(call));
    }

    private JSObject networkStateResult() {
        boolean wifiConnected = false;
        boolean cellularConnected = false;
        boolean internetAvailable = false;
        if (connectivityManager != null) {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null) continue;
                wifiConnected |= capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                cellularConnected |= capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                internetAvailable |= capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        }
        JSObject result = new JSObject();
        result.put("wifiConnected", wifiConnected);
        result.put("cellularConnected", cellularConnected);
        result.put("internetAvailable", internetAvailable);
        return result;
    }

    private void notifyNetworkChanged() {
        mainHandler.postDelayed(() -> notifyListeners("networkChanged", networkStateResult(), true), 100);
    }

    @PluginMethod
    public void getNetworkState(PluginCall call) {
        call.resolve(networkStateResult());
    }

    @PluginMethod
    public void openWifiSettings(PluginCall call) {
        try {
            Intent intent = new Intent(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? Settings.Panel.ACTION_WIFI
                    : Settings.ACTION_WIFI_SETTINGS
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            JSObject result = new JSObject();
            result.put("opened", true);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(readableError("Could not open Wi-Fi settings", error), error);
        }
    }

    private JSObject stateResult(AndroidTvRemoteClient.RemoteState state) {
        JSObject result = new JSObject();
        result.put("connected", state.connected);
        result.put("ip", state.ip);
        result.put("manufacturer", state.manufacturer);
        result.put("model", state.model);
        result.put("serviceVersion", state.serviceVersion);
        result.put("power", state.power == null ? org.json.JSONObject.NULL : state.power);
        result.put("currentApp", state.currentApp);
        result.put("keyboardEnabled", state.keyboardEnabled);
        result.put("keyboardActive", state.keyboardActive);
        JSObject capabilities = new JSObject();
        capabilities.put("keys", (state.features & 2) != 0);
        capabilities.put("volume", (state.features & 64) != 0);
        capabilities.put("power", (state.features & 32) != 0);
        capabilities.put("appLinks", (state.features & 512) != 0);
        capabilities.put("keyboard", (state.features & 4) != 0);
        capabilities.put("voice", (state.features & 8) != 0);
        result.put("capabilities", capabilities);
        JSObject volume = new JSObject();
        volume.put("available", state.volume != null);
        if (state.volume != null) {
            volume.put("level", state.volume.level);
            volume.put("max", state.volume.max);
            volume.put("muted", state.volume.muted);
        }
        result.put("volume", volume);
        return result;
    }

    @PluginMethod
    public void getState(PluginCall call) { call.resolve(stateResult(remoteClient.getState())); }

    @PluginMethod
    public void setKeyboardEnabled(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled"));
        io.execute(() -> {
            try {
                remoteClient.setKeyboardEnabled(enabled);
                call.resolve(stateResult(remoteClient.getState()));
            } catch (Exception error) { call.reject(readableError("Keyboard mode failed", error), error); }
        });
    }

    @PluginMethod
    public void sendText(PluginCall call) {
        String text = call.getString("text");
        io.execute(() -> {
            try { remoteClient.sendText(text); call.resolve(); }
            catch (AndroidTvRemoteClient.UnsupportedFeatureException error) { call.reject(error.getMessage(), "UNSUPPORTED_FEATURE"); }
            catch (IllegalStateException error) { call.reject(error.getMessage(), "KEYBOARD_INACTIVE"); }
            catch (Exception error) { call.reject(readableError("Text input failed", error), error); }
        });
    }

    @PluginMethod
    public void replaceText(PluginCall call) {
        String text = call.getString("text");
        io.execute(() -> {
            try { remoteClient.replaceText(text); call.resolve(); }
            catch (AndroidTvRemoteClient.UnsupportedFeatureException error) { call.reject(error.getMessage(), "UNSUPPORTED_FEATURE"); }
            catch (IllegalStateException error) { call.reject(error.getMessage(), "TEXT_FIELD_UNKNOWN"); }
            catch (Exception error) { call.reject(readableError("Text replacement failed", error), error); }
        });
    }

    private void startDiscovery(PluginCall call) {
        finishDiscovery("A new TV scan was started.");
        synchronized (discoveryLock) {
            found.clear();
            resolveQueue.clear();
            resolving = false;
            discoveryCall = call;
            discoveryStarted = false;

            try {
                WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("tv-remote-discovery");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }

                discoveryListener = new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String serviceType) {
                        synchronized (discoveryLock) { discoveryStarted = true; }
                    }

                    @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                        finishDiscovery("TV discovery could not start (error " + errorCode + ").");
                    }

                    @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                        Log.w(TAG, "Could not stop NSD scan: " + errorCode);
                    }

                    @Override public void onDiscoveryStopped(String serviceType) {}

                    @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                        if (serviceInfo == null) return;
                        synchronized (discoveryLock) {
                            found.values().removeIf(value -> value.getServiceName().equals(serviceInfo.getServiceName()));
                        }
                    }

                    @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                        if (serviceInfo == null || !REMOTE_SERVICE.equals(serviceInfo.getServiceType())) return;
                        synchronized (discoveryLock) {
                            if (discoveryCall == null) return;
                            resolveQueue.add(serviceInfo);
                        }
                        resolveNext();
                    }
                };

                nsd.discoverServices(REMOTE_SERVICE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
                mainHandler.postDelayed(discoveryTimeout, DISCOVERY_MS);
            } catch (Exception error) {
                finishDiscovery(readableError("TV discovery failed", error));
            }
        }
    }

    /** Older Android releases only permit one NSD resolve at a time. */
    private void resolveNext() {
        final NsdServiceInfo service;
        synchronized (discoveryLock) {
            if (resolving || discoveryCall == null) return;
            service = resolveQueue.poll();
            if (service == null) return;
            resolving = true;
        }

        try {
            nsd.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    synchronized (discoveryLock) { resolving = false; }
                    resolveNext();
                }

                @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    synchronized (discoveryLock) {
                        if (discoveryCall != null && serviceInfo.getHost() != null) {
                            found.put(serviceInfo.getHost().getHostAddress(), serviceInfo);
                        }
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Exception error) {
            Log.w(TAG, "Could not resolve " + service.getServiceName() + ": " + error.getMessage());
            synchronized (discoveryLock) { resolving = false; }
            resolveNext();
        }
    }

    private void finishDiscovery(String error) {
        final PluginCall call;
        final JSArray devices = new JSArray();
        synchronized (discoveryLock) {
            call = discoveryCall;
            if (call == null) return;
            discoveryCall = null;
            mainHandler.removeCallbacks(discoveryTimeout);

            for (NsdServiceInfo device : found.values()) {
                if (device.getHost() == null) continue;
                JSObject item = new JSObject();
                item.put("name", device.getServiceName());
                item.put("ip", device.getHost().getHostAddress());
                item.put("port", device.getPort() > 0 ? device.getPort() : 6466);
                item.put("pairingPort", 6467);
                devices.put(item);
            }

            if (discoveryListener != null && discoveryStarted) {
                try { nsd.stopServiceDiscovery(discoveryListener); }
                catch (Exception stopError) { Log.w(TAG, "Stopping NSD scan failed", stopError); }
            }
            discoveryStarted = false;
            discoveryListener = null;
            resolveQueue.clear();
            resolving = false;
            releaseMulticastLock();
        }

        if (error != null) {
            call.reject(error);
        } else {
            JSObject result = new JSObject();
            result.put("devices", devices);
            call.resolve(result);
        }
    }

    @PluginMethod
    public void pair(PluginCall call) {
        String host = call.getString("ip");
        if (host == null || host.trim().isEmpty()) {
            call.reject("TV address is missing.");
            return;
        }

        io.execute(() -> {
            try {
                JSObject result = new JSObject();
                if (remoteClient.isPaired(host)) {
                    try {
                        remoteClient.connectRemote(host);
                        result.put("connected", true);
                        result.put("pinRequired", false);
                        call.resolve(result);
                        return;
                    } catch (Exception savedPairingError) {
                        Log.w(TAG, "Saved pairing could not reconnect; starting pairing again", savedPairingError);
                    }
                }

                remoteClient.beginPairing(host);
                result.put("connected", false);
                result.put("pinRequired", true);
                call.resolve(result);
            } catch (Exception error) {
                call.reject(readableError("Could not start pairing", error), error);
            }
        });
    }

    @PluginMethod
    public void finishPairing(PluginCall call) {
        String pin = call.getString("pin");
        io.execute(() -> {
            try {
                remoteClient.finishPairing(pin);
                JSObject result = new JSObject();
                result.put("connected", true);
                call.resolve(result);
            } catch (Exception error) {
                call.reject(readableError("Pairing failed", error), error);
            }
        });
    }

    @PluginMethod
    public void cancelPairing(PluginCall call) {
        remoteClient.cancelPairing();
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        String command = call.getString("command");
        io.execute(() -> {
            try {
                remoteClient.sendWithReconnect(command);
                JSObject result = new JSObject();
                result.put("sent", true);
                call.resolve(result);
            } catch (AndroidTvRemoteClient.UnsupportedFeatureException error) {
                call.reject(error.getMessage(), "UNSUPPORTED_FEATURE");
            } catch (Exception error) {
                call.reject(readableError("Remote command failed", error), error);
            }
        });
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) return;
        try {
            if (multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private static String readableError(String prefix, Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) message = current.getMessage();
            current = current.getCause();
        }
        return message == null ? prefix + "." : prefix + ": " + message;
    }

    @Override
    protected void handleOnDestroy() {
        mainHandler.removeCallbacks(discoveryTimeout);
        mainHandler.post(() -> finishDiscovery("TV scan was cancelled."));
        releaseMulticastLock();
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) {}
        }
        if (remoteClient != null) remoteClient.close();
        io.shutdownNow();
        super.handleOnDestroy();
    }
}
