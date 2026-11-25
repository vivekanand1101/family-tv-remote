package com.codeplay.tv.remote;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.net.URL;
import org.json.JSONObject;

/** Optional authenticated TV companion transport; it never queries phone-installed apps. */
final class TvCompanionClient {
    private static final int PORT = 6469;
    private final AndroidTvRemoteClient remote;
    private final SharedPreferences preferences;
    private volatile String connectedHost = "";

    TvCompanionClient(Context context, AndroidTvRemoteClient remote) {
        this.remote = remote;
        preferences = context.getSharedPreferences("tv_companion_pairing_v1", Context.MODE_PRIVATE);
    }

    boolean isConnected(String host) { return host != null && host.equals(connectedHost); }

    JSONObject beginPairing(String host) throws Exception {
        connectedHost = "";
        return request(host, "/pair/start", new JSONObject(), true, null);
    }

    JSONObject finishPairing(String host, String pin) throws Exception {
        String normalized = pin == null ? "" : pin.trim().toUpperCase(Locale.US);
        if (!normalized.matches("[0-9A-F]{6}")) throw new IllegalArgumentException("Enter the 6-character code shown in TV Remote Companion.");
        X509Certificate[] server = new X509Certificate[1];
        JSONObject result = request(host, "/pair/finish", null, true, (connection, certificate) -> {
            server[0] = certificate;
            byte[] secret = AndroidTvRemoteClient.pairingSecret(remote.getOrCreateClientCertificate().certificate, certificate, normalized);
            if ((secret[0] & 255) != Integer.parseInt(normalized.substring(0, 2), 16)) {
                throw new SecurityException("The pairing code does not match this TV connection. Start pairing again.");
            }
            return new JSONObject().put("secret", hex(secret));
        });
        String token = result.getString("token");
        preferences.edit().putString(host + ":pin", AndroidTvRemoteClient.sha256Hex(server[0].getEncoded()))
            .putString(host + ":token", token).apply();
        connectedHost = host;
        return new JSONObject().put("connected", true);
    }

    JSONObject getApps(String host) throws Exception { return request(host, "/apps", null, false, null); }
    JSONObject launch(String host, String packageName) throws Exception {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Choose an installed TV app.");
        }
        return request(host, "/launch", new JSONObject().put("packageName", packageName), false, null);
    }

    private interface PairingBody {
        JSONObject create(HttpsURLConnection connection, X509Certificate certificate) throws Exception;
    }

    private JSONObject request(String host, String endpoint, JSONObject body, boolean pairing, PairingBody pairingBody) throws Exception {
        if (host == null || host.isEmpty()) throw new IllegalStateException("Connect to a TV first.");
        String pinned = preferences.getString(host + ":pin", null);
        String token = preferences.getString(host + ":token", null);
        if (!pairing && (pinned == null || token == null)) {
            throw new IllegalStateException("Install and open TV Remote Companion on your TV, then pair it here.");
        }
        AndroidTvRemoteClient.KeyMaterial keys = remote.getOrCreateClientCertificate();
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{new AndroidTvRemoteClient.ClientKeyManager(keys.certificate, keys.privateKey)},
            new X509TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String type) {}
                public void checkServerTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
                    if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Missing TV companion certificate.");
                    if (!pairing) {
                        try {
                            String actual = AndroidTvRemoteClient.sha256Hex(chain[0].getEncoded());
                            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), pinned.getBytes(StandardCharsets.US_ASCII))) {
                                throw new java.security.cert.CertificateException("TV companion identity changed. Pair it again.");
                            }
                        } catch (java.security.cert.CertificateException error) { throw error; }
                        catch (Exception error) { throw new java.security.cert.CertificateException(error); }
                    }
                }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
        String address = host.contains(":") ? "[" + host + "]" : host;
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + address + ":" + PORT + endpoint).openConnection();
        connection.setSSLSocketFactory(tls.getSocketFactory());
        // Authentication uses the paired certificate fingerprint, not a DNS name on a LAN IP.
        connection.setHostnameVerifier((name, session) -> true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Connection", "close");
        if (!pairing) connection.setRequestProperty("Authorization", "Bearer " + token);
        boolean post = body != null || pairingBody != null;
        if (post) {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
        }
        try {
            if (pairingBody != null) {
                connection.connect();
                X509Certificate certificate = (X509Certificate) connection.getServerCertificates()[0];
                body = pairingBody.create(connection, certificate);
            }
            if (post) {
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (input == null) throw new java.io.IOException("TV companion did not respond.");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 512000) throw new java.io.IOException("TV companion response is too large.");
                    output.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(output.toString("UTF-8"));
                if (status != 200) throw new java.io.IOException(result.optString("message", "TV companion request failed."));
                if (!pairing) connectedHost = host;
                return result;
            }
        } catch (Exception error) {
            connectedHost = "";
            throw error;
        } finally { connection.disconnect(); }
    }

    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) text.append(String.format(Locale.US, "%02x", item & 255));
        return text.toString();
    }
}
