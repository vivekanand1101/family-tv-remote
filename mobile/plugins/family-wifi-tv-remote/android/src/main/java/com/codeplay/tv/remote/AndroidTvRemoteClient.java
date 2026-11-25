package com.codeplay.tv.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Android TV Remote Service v2 client.
 *
 * Pairing uses the Google Polo protocol over mutual TLS on port 6467. The
 * paired certificate is then reused for the remote-control channel on port
 * 6466. Protobuf messages are deliberately encoded here because only a small
 * subset of the protocol is required and this avoids shipping a protobuf
 * runtime solely for a handful of messages.
 */
final class AndroidTvRemoteClient implements Closeable {
    private static final String TAG = "AndroidTvRemote";
    // v2 also permits the raw RSA operation Conscrypt uses for TLS client auth.
    private static final String KEY_ALIAS = "codeplay_tv_remote_client_v2";
    private static final String PIN_PREFS = "android_tv_server_cert_pins";
    private static final int PAIRING_PORT = 6467;
    private static final int REMOTE_PORT = 6466;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int IO_TIMEOUT_MS = 10_000;
    private static final int MAX_FRAME_SIZE = 65_536;

    private final Context context;
    private final SharedPreferences pins;
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    private final Object pairingLock = new Object();
    private final Object remoteWriteLock = new Object();

    private SSLSocket pairingSocket;
    private X509Certificate pairingServerCertificate;
    private String pairingHost;

    private volatile SSLSocket remoteSocket;
    private volatile String remoteHost;
    private volatile boolean closed;
    private VolumeListener volumeListener;
    private StateListener stateListener;
    private RemoteState remoteState = new RemoteState();
    private boolean keyboardEnabled;

    interface StateListener {
        void onStateChanged(RemoteState state);
    }

    static final class UnsupportedFeatureException extends IOException {
        UnsupportedFeatureException(String message) { super(message); }
    }

    static final class RemoteState {
        boolean connected;
        String ip = "";
        String manufacturer = "";
        String model = "";
        String serviceVersion = "";
        String currentApp = "";
        Boolean power;
        int features;
        boolean keyboardEnabled;
        boolean keyboardActive;
        long imeCounter;
        long fieldCounter;
        int keyboardTextLength = -1;
        VolumeState volume;

        RemoteState copy() {
            RemoteState copy = new RemoteState();
            copy.connected = connected;
            copy.ip = ip;
            copy.manufacturer = manufacturer;
            copy.model = model;
            copy.serviceVersion = serviceVersion;
            copy.currentApp = currentApp;
            copy.power = power;
            copy.features = features;
            copy.keyboardEnabled = keyboardEnabled;
            copy.keyboardActive = keyboardActive;
            copy.imeCounter = imeCounter;
            copy.fieldCounter = fieldCounter;
            copy.keyboardTextLength = keyboardTextLength;
            copy.volume = volume;
            return copy;
        }

        boolean update(ParsedMessage envelope) throws IOException {
            boolean changed = false;
            byte[] configure = envelope.bytesField(1);
            if (configure != null) {
                ParsedMessage config = ParsedMessage.parse(configure);
                features = (int) config.varintField(1, 0);
                byte[] info = config.bytesField(2);
                if (info != null) {
                    ParsedMessage device = ParsedMessage.parse(info);
                    model = device.stringField(1);
                    manufacturer = device.stringField(2);
                    serviceVersion = device.stringField(6);
                }
                changed = true;
            }
            byte[] start = envelope.bytesField(40);
            if (start != null) { power = ParsedMessage.parse(start).varintField(1, 0) != 0; changed = true; }
            byte[] volumePayload = envelope.bytesField(50);
            if (volumePayload != null) { volume = VolumeState.parse(volumePayload); changed = true; }
            byte[] ime = envelope.bytesField(20);
            if (ime != null) {
                ParsedMessage injection = ParsedMessage.parse(ime);
                byte[] app = injection.bytesField(1);
                if (app != null) {
                    String nextApp = ParsedMessage.parse(app).stringField(12);
                    if (!nextApp.equals(currentApp)) { keyboardActive = false; keyboardTextLength = -1; }
                    currentApp = nextApp;
                }
                // Some TVs report focused text fields here without a separate show/batch message.
                byte[] field = injection.bytesField(2);
                if (field != null) {
                    keyboardActive = true;
                    ParsedMessage status = ParsedMessage.parse(field);
                    fieldCounter = status.varintField(1, 0);
                    keyboardTextLength = status.bytesField(2) != null ? status.stringField(2).length() : -1;
                }
                changed = true;
            }
            byte[] edit = envelope.bytesField(21);
            if (edit != null) {
                ParsedMessage batch = ParsedMessage.parse(edit);
                imeCounter = batch.varintField(1, 0);
                if (fieldCounter != batch.varintField(2, 0)) keyboardTextLength = -1;
                fieldCounter = batch.varintField(2, 0);
                keyboardActive = true;
                changed = true;
            }
            byte[] show = envelope.bytesField(22);
            if (show != null) {
                byte[] field = ParsedMessage.parse(show).bytesField(2);
                keyboardActive = field != null;
                keyboardTextLength = -1;
                if (field != null) {
                    ParsedMessage status = ParsedMessage.parse(field);
                    fieldCounter = status.varintField(1, 0);
                    keyboardTextLength = status.bytesField(2) != null ? status.stringField(2).length() : -1;
                }
                changed = true;
            }
            return changed;
        }
    }

    void setStateListener(StateListener listener) { stateListener = listener; }

    RemoteState getState() {
        synchronized (remoteWriteLock) { return remoteState.copy(); }
    }

    synchronized void setKeyboardEnabled(boolean enabled) throws Exception {
        if (keyboardEnabled == enabled) return;
        boolean previous = keyboardEnabled;
        keyboardEnabled = enabled;
        try { if (remoteHost != null) connectRemote(remoteHost); }
        catch (Exception error) { keyboardEnabled = previous; throw error; }
    }

    interface VolumeListener {
        void onVolumeChanged(VolumeState volume);
    }

    static final class VolumeState {
        final int level;
        final int max;
        final boolean muted;

        private VolumeState(int level, int max, boolean muted) {
            this.level = level;
            this.max = max;
            this.muted = muted;
        }

        static VolumeState parse(byte[] payload) throws IOException {
            ParsedMessage message = ParsedMessage.parse(payload);
            long max = message.varintField(6, 0);
            long level = message.varintField(7, 0);
            if (max <= 0 || max > Integer.MAX_VALUE || level < 0 || level > max) return null;
            return new VolumeState((int) level, (int) max, message.varintField(8, 0) != 0);
        }
    }

    void setVolumeListener(VolumeListener listener) {
        volumeListener = listener;
    }

    AndroidTvRemoteClient(Context context) {
        this.context = context.getApplicationContext();
        this.pins = this.context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE);
    }

    boolean isPaired(String host) {
        return pins.contains(host);
    }

    void cancelPairing() {
        synchronized (pairingLock) { closePairingLocked(); }
    }

    /** Starts pairing and returns after the TV has displayed its PIN. */
    void beginPairing(String host) throws Exception {
        requireHost(host);
        synchronized (pairingLock) {
            closePairingLocked();

            KeyMaterial material = getOrCreateClientCertificate();
            SSLSocket socket = connectTls(host, PAIRING_PORT, material, null);
            try {
                socket.setSoTimeout(IO_TIMEOUT_MS);
                X509Certificate serverCert = peerCertificate(socket);

                // PairingRequest(service_name="atvremote", client_name="TV Remote")
                byte[] request = message(
                    stringField(1, "atvremote"),
                    stringField(2, "TV Remote")
                );
                writeFrame(socket.getOutputStream(), outerMessage(10, request));
                expectOuter(socket.getInputStream(), 11);

                // Advertise a six-symbol hexadecimal code accepted as input.
                byte[] encoding = message(varintField(1, 3), varintField(2, 6));
                byte[] options = message(bytesField(1, encoding), varintField(3, 1));
                writeFrame(socket.getOutputStream(), outerMessage(20, options));
                expectOuter(socket.getInputStream(), 20);

                byte[] configuration = message(bytesField(1, encoding), varintField(2, 1));
                writeFrame(socket.getOutputStream(), outerMessage(30, configuration));
                expectOuter(socket.getInputStream(), 31);

                // No read is in progress while JavaScript displays its prompt.
                socket.setSoTimeout(0);
                pairingSocket = socket;
                pairingServerCertificate = serverCert;
                pairingHost = host;
                Log.i(TAG, "TV is showing a pairing PIN for " + host);
            } catch (Exception error) {
                closeQuietly(socket);
                throw error;
            }
        }
    }

    /** Finishes the pending PIN exchange, pins the TV certificate, and connects. */
    void finishPairing(String pin) throws Exception {
        String normalized = pin == null ? "" : pin.trim().toUpperCase(Locale.US);
        if (!normalized.matches("[0-9A-F]{6}")) {
            throw new IllegalArgumentException("Enter the 6-character hexadecimal code shown on the TV.");
        }

        final String host;
        final SSLSocket socket;
        final X509Certificate serverCert;
        synchronized (pairingLock) {
            if (pairingSocket == null || pairingServerCertificate == null || pairingHost == null) {
                throw new IllegalStateException("No pairing is in progress. Select the TV again.");
            }
            socket = pairingSocket;
            serverCert = pairingServerCertificate;
            host = pairingHost;

            try {
                KeyMaterial material = getOrCreateClientCertificate();
                byte[] secret = pairingSecret(material.certificate, serverCert, normalized);
                int checkByte = Integer.parseInt(normalized.substring(0, 2), 16);
                if ((secret[0] & 0xff) != checkByte) {
                    throw new IllegalArgumentException("The pairing code is incorrect. Please use the current code shown on the TV.");
                }

                socket.setSoTimeout(IO_TIMEOUT_MS);
                writeFrame(socket.getOutputStream(), outerMessage(40, bytesField(1, secret)));
                ParsedMessage ack = expectOuter(socket.getInputStream(), 41);
                byte[] returnedSecret = ack.bytesField(41);
                if (returnedSecret != null) {
                    ParsedMessage secretAck = ParsedMessage.parse(returnedSecret);
                    byte[] returnedHash = secretAck.bytesField(1);
                    if (returnedHash != null && !MessageDigest.isEqual(secret, returnedHash)) {
                        throw new SecurityException("The TV returned an invalid pairing response.");
                    }
                }

                pins.edit().putString(host, sha256Hex(serverCert.getEncoded())).commit();
                Log.i(TAG, "Pairing completed for " + host);
            } finally {
                closePairingLocked();
            }
        }
        connectRemote(host);
    }

    /** Connects an already-paired TV and activates its remote session. */
    synchronized void connectRemote(String host) throws Exception {
        requireHost(host);
        String expectedPin = pins.getString(host, null);
        if (expectedPin == null) {
            throw new IllegalStateException("This TV has not been paired yet.");
        }

        KeyMaterial material = getOrCreateClientCertificate();
        SSLSocket socket = connectTls(host, REMOTE_PORT, material, expectedPin);
        try {
            byte[] deviceInfo = message(
                stringField(1, Build.MODEL == null ? "Android" : Build.MODEL),
                stringField(2, "CodePlay"),
                varintField(3, 1),
                stringField(4, "1"),
                stringField(5, context.getPackageName()),
                stringField(6, "1.0")
            );
            RemoteState initialState = new RemoteState();
            initialState.ip = host;
            initialState.keyboardEnabled = keyboardEnabled;
            VolumeState initialVolume = activateRemote(socket, deviceInfo, initialState);

            SSLSocket previous;
            synchronized (remoteWriteLock) {
                previous = remoteSocket;
                remoteSocket = socket;
                remoteHost = host;
                initialState.connected = true;
                remoteState = initialState;
                if (volumeListener != null) volumeListener.onVolumeChanged(initialVolume);
                if (stateListener != null) stateListener.onStateChanged(remoteState.copy());
            }
            closeQuietly(previous);
            startReader(socket);
            Log.i(TAG, "Remote session active for " + host);
        } catch (Exception error) {
            closeQuietly(socket);
            throw error;
        }
    }

    /** Local handshake fix 0.1: publish the socket only after RemoteStart. */
    private static VolumeState activateRemote(SSLSocket socket, byte[] deviceInfo) throws IOException {
        return activateRemote(socket, deviceInfo, new RemoteState());
    }

    private static VolumeState activateRemote(SSLSocket socket, byte[] deviceInfo, RemoteState state) throws IOException {
        // Only request features implemented here: ping, keys, power, volume, app links.
        int features = 1 | 2 | 32 | 64 | 512;
        if (state.keyboardEnabled) features |= 4;
        boolean configured = false;
        boolean activated = false;
        VolumeState volume = null;
        long deadline = System.nanoTime() + IO_TIMEOUT_MS * 1_000_000L;
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        while (true) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) throw new IOException("Timed out waiting for the TV remote to become ready.");
            socket.setSoTimeout((int) Math.max(1, remainingMs));
            ParsedMessage envelope = ParsedMessage.parse(readFrame(input));
            state.update(envelope);
            if (envelope.bytesField(3) != null) {
                throw new IOException("The TV rejected the remote handshake.");
            }
            byte[] volumePayload = envelope.bytesField(50);
            if (volumePayload != null) volume = VolumeState.parse(volumePayload);
            byte[] configure = envelope.bytesField(1);
            if (configure != null) {
                features &= (int) ParsedMessage.parse(configure).varintField(1, 0);
                if ((features & 2) == 0) {
                    throw new IOException("The TV remote service does not allow key commands.");
                }
                writeFrame(output, bytesField(1, message(varintField(1, features), bytesField(2, deviceInfo))));
                configured = true;
                activated = false;
            }
            if (envelope.bytesField(2) != null) {
                if (!configured) throw new IOException("The TV requested activation before configuration.");
                writeFrame(output, bytesField(2, varintField(1, features)));
                activated = true;
            }
            byte[] ping = envelope.bytesField(8);
            if (ping != null) {
                long token = ParsedMessage.parse(ping).varintField(1, 0);
                writeFrame(output, bytesField(9, varintField(1, token)));
            }
            // The 'started' value is TV power state; false still means the session is ready.
            if (envelope.bytesField(40) != null) {
                if (!activated) throw new IOException("The TV started a remote session before activation.");
                socket.setSoTimeout(0);
                state.keyboardEnabled = state.keyboardEnabled && (features & 4) != 0;
                return volume;
            }
        }
    }

    void send(String command) throws Exception {
        if ("keyboardSearch".equals(command)) {
            ensureRemoteConnected();
            synchronized (remoteWriteLock) {
                if (!remoteState.keyboardEnabled || (remoteState.features & 4) == 0) {
                    throw new UnsupportedFeatureException("Enable keyboard mode and open a TV search field first.");
                }
                // IME editor action, not a key press that selects the highlighted TV key.
                byte[] action = bytesField(5, varintField(1, android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH));
                byte[] batch = message(varintField(1, remoteState.imeCounter), varintField(2, remoteState.fieldCounter), bytesField(3, action));
                if (!isOpen(remoteSocket)) throw new IOException("TV connection was lost.");
                writeFrame(remoteSocket.getOutputStream(), bytesField(21, batch));
            }
            return;
        }
        Integer keyCode = keyCodeFor(command);
        String appLink = appLinkFor(command);
        if (keyCode == null && appLink == null) {
            throw new IllegalArgumentException("Unsupported remote command: " + command);
        }

        ensureRemoteConnected();
        synchronized (remoteWriteLock) {
            int required = appLink != null ? 512 : command.equals("mute") || command.startsWith("volume") ? 64
                : command.equals("power") || command.equals("tvPower") || command.equals("sleep") || command.equals("wake") ? 32 : 2;
            if ((remoteState.features & required) == 0) {
                throw new UnsupportedFeatureException("This TV remote service does not support this control.");
            }
        }
        byte[] payload;
        if (appLink != null) {
            payload = bytesField(90, stringField(1, appLink));
        } else {
            byte[] key = message(varintField(1, keyCode), varintField(2, 3));
            payload = bytesField(10, key);
        }

        synchronized (remoteWriteLock) {
            SSLSocket socket = remoteSocket;
            if (!isOpen(socket)) {
                throw new IOException("TV connection was lost. Tap Connect and try again.");
            }
            try {
                writeFrame(socket.getOutputStream(), payload);
            } catch (IOException firstError) {
                String host = remoteHost;
                closeRemoteLocked(socket);
                if (host == null) throw firstError;
                // Leave the retry to a fresh connection outside the write lock.
                throw new RetryableSendException(host, firstError);
            }
        }
    }

    void sendWithReconnect(String command) throws Exception {
        try {
            send(command);
        } catch (RetryableSendException retry) {
            connectRemote(retry.host);
            send(command);
        }
    }

    private void ensureRemoteConnected() throws Exception {
        if (isOpen(remoteSocket)) return;
        String host = remoteHost;
        if (host == null) throw new IllegalStateException("Connect to a TV first.");
        connectRemote(host);
    }

    private void startReader(final SSLSocket socket) {
        readerExecutor.execute(() -> {
            try {
                InputStream input = socket.getInputStream();
                while (!closed && socket == remoteSocket && isOpen(socket)) {
                    byte[] frame = readFrame(input);
                    ParsedMessage envelope = ParsedMessage.parse(frame);
                    if (envelope.bytesField(3) != null) {
                        throw new IOException("The TV rejected a remote message.");
                    }
                    synchronized (remoteWriteLock) {
                        if (socket == remoteSocket && remoteState.update(envelope) && stateListener != null) {
                            stateListener.onStateChanged(remoteState.copy());
                        }
                    }
                    byte[] volumePayload = envelope.bytesField(50);
                    if (volumePayload != null) {
                        VolumeState volume = VolumeState.parse(volumePayload);
                        synchronized (remoteWriteLock) {
                            if (socket == remoteSocket && volumeListener != null) {
                                volumeListener.onVolumeChanged(volume);
                            }
                        }
                    }
                    byte[] ping = envelope.bytesField(8);
                    if (ping != null) {
                        long token = ParsedMessage.parse(ping).varintField(1, 0);
                        synchronized (remoteWriteLock) {
                            if (socket == remoteSocket && isOpen(socket)) {
                                writeFrame(socket.getOutputStream(), bytesField(9, varintField(1, token)));
                            }
                        }
                    }
                }
            } catch (Exception error) {
                if (!closed && socket == remoteSocket) {
                    Log.w(TAG, "Remote reader stopped: " + error.getMessage());
                }
            } finally {
                synchronized (remoteWriteLock) {
                    if (socket == remoteSocket) closeRemoteLocked(socket);
                }
            }
        });
    }

    void sendText(String text) throws Exception {
        if (text == null || text.isEmpty() || text.length() > 2000) {
            throw new IllegalArgumentException("Enter between 1 and 2000 characters.");
        }
        ensureRemoteConnected();
        synchronized (remoteWriteLock) {
            if (!remoteState.keyboardEnabled || (remoteState.features & 4) == 0) {
                throw new UnsupportedFeatureException("Keyboard input is not enabled or supported on this TV.");
            }
            // Focus notifications are optional on some TV keyboards. An explicit send can use
            // the latest counters (protobuf defaults are zero) without a focus announcement.
            byte[] field = message(varintField(1, text.length() - 1), varintField(2, text.length() - 1), stringField(3, text));
            byte[] edit = message(varintField(1, 1), bytesField(2, field));
            byte[] batch = message(varintField(1, remoteState.imeCounter), varintField(2, remoteState.fieldCounter), bytesField(3, edit));
            if (!isOpen(remoteSocket)) throw new IOException("TV connection was lost.");
            writeFrame(remoteSocket.getOutputStream(), bytesField(21, batch));
            remoteState.keyboardTextLength = -1;
        }
    }

    void replaceText(String text) throws Exception {
        if (text == null || text.length() > 2000) {
            throw new IllegalArgumentException("Enter up to 2000 characters.");
        }
        ensureRemoteConnected();
        synchronized (remoteWriteLock) {
            if (!remoteState.keyboardEnabled || (remoteState.features & 4) == 0) {
                throw new UnsupportedFeatureException("Keyboard input is not enabled or supported on this TV.");
            }
            if (remoteState.keyboardTextLength < 0) {
                throw new IllegalStateException("This TV does not report its text length. Clear the TV field and use sendText instead.");
            }
            // Do not guess an out-of-bounds end: some TV apps silently reject the entire edit.
            int end = remoteState.keyboardTextLength;
            byte[] field = message(varintField(1, 0), varintField(2, end), stringField(3, text));
            byte[] edit = bytesField(2, field);
            byte[] batch = message(varintField(1, remoteState.imeCounter), varintField(2, remoteState.fieldCounter), bytesField(3, edit));
            if (!isOpen(remoteSocket)) throw new IOException("TV connection was lost.");
            writeFrame(remoteSocket.getOutputStream(), bytesField(21, batch));
            // No application acknowledgement is available: discard the old length after writing.
            remoteState.keyboardTextLength = -1;
        }
    }

    private SSLSocket connectTls(String host, int port, KeyMaterial material, String expectedFingerprint) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
            new KeyManager[]{new ClientKeyManager(material.certificate, material.privateKey)},
            new javax.net.ssl.TrustManager[]{new LocalTvTrustManager()},
            new SecureRandom()
        );

        SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(IO_TIMEOUT_MS);
            socket.startHandshake();
            X509Certificate peer = peerCertificate(socket);
            if (expectedFingerprint != null) {
                String actual = sha256Hex(peer.getEncoded());
                if (!MessageDigest.isEqual(
                    expectedFingerprint.toLowerCase(Locale.US).getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII)
                )) {
                    throw new SecurityException("The TV identity changed. Pair this TV again.");
                }
            }
            return socket;
        } catch (Exception error) {
            closeQuietly(socket);
            throw error;
        }
    }

    KeyMaterial getOrCreateClientCertificate() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            Calendar start = Calendar.getInstance();
            start.add(Calendar.DAY_OF_YEAR, -1);
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 20);

            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE,
                    KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
                )
                .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=TV Remote,O=CodePlay"))
                .setCertificateSerialNumber(new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE))
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .build();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(spec);
            generator.generateKeyPair();
            store.load(null);
        }

        X509Certificate certificate = (X509Certificate) store.getCertificate(KEY_ALIAS);
        PrivateKey privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
        if (certificate == null || privateKey == null) {
            throw new IllegalStateException("Could not load the TV remote security certificate.");
        }
        return new KeyMaterial(certificate, privateKey);
    }

    static byte[] pairingSecret(X509Certificate client, X509Certificate server, String pin) throws Exception {
        RSAPublicKey clientKey = (RSAPublicKey) client.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) server.getPublicKey();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsigned(clientKey.getModulus()));
        digest.update(unsigned(clientKey.getPublicExponent()));
        digest.update(unsigned(serverKey.getModulus()));
        digest.update(unsigned(serverKey.getPublicExponent()));
        digest.update(hexToBytes(pin.substring(2)));
        return digest.digest();
    }

    private static byte[] unsigned(BigInteger number) {
        byte[] raw = number.toByteArray();
        return raw.length > 1 && raw[0] == 0 ? Arrays.copyOfRange(raw, 1, raw.length) : raw;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] output = new byte[hex.length() / 2];
        for (int i = 0; i < output.length; i++) {
            output[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return output;
    }

    private static ParsedMessage expectOuter(InputStream input, int expectedField) throws IOException {
        ParsedMessage response = ParsedMessage.parse(readFrame(input));
        long status = response.varintField(2, 0);
        if (status != 200) {
            if (status == 402) throw new IOException("The TV rejected the pairing code.");
            throw new IOException("TV pairing failed with status " + status + ".");
        }
        if (response.bytesField(expectedField) == null) {
            throw new IOException("Unexpected response from TV while pairing.");
        }
        return response;
    }

    private static byte[] outerMessage(int field, byte[] inner) {
        return message(varintField(1, 2), varintField(2, 200), bytesField(field, inner));
    }

    private static byte[] message(byte[]... fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] field : fields) output.write(field, 0, field.length);
        return output.toByteArray();
    }

    private static byte[] stringField(int field, String value) {
        return bytesField(field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] varintField(int field, long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) field << 3));
        writeVarint(output, value);
        return output.toByteArray();
    }

    private static byte[] bytesField(int field, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) field << 3) | 2);
        writeVarint(output, value.length);
        output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static void writeFrame(OutputStream output, byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME_SIZE) throw new IOException("Remote message is too large.");
        writeVarint(output, payload.length);
        output.write(payload);
        output.flush();
    }

    private static byte[] readFrame(InputStream input) throws IOException {
        long length = readVarint(input);
        if (length < 0 || length > MAX_FRAME_SIZE) throw new IOException("Invalid TV response length: " + length);
        byte[] payload = new byte[(int) length];
        int offset = 0;
        while (offset < payload.length) {
            int count = input.read(payload, offset, payload.length - offset);
            if (count < 0) throw new EOFException("TV closed the connection.");
            offset += count;
        }
        return payload;
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static void writeVarint(OutputStream output, long value) throws IOException {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static long readVarint(InputStream input) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int value = input.read();
            if (value < 0) throw new EOFException("TV closed the connection.");
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("Malformed varint in TV response.");
    }

    private static Integer keyCodeFor(String command) {
        if (command == null) return null;
        switch (command) {
            case "home": return 3;
            case "back": return 4;
            case "up": return 19;
            case "down": return 20;
            case "left": return 21;
            case "right": return 22;
            case "ok": return 23;
            case "volumeUp": return 24;
            case "volumeDown": return 25;
            case "power": return 26;
            case "menu": return 82;
            case "search": return 84;
            case "playPause": return 85;
            case "stop": return 86;
            case "next": return 87;
            case "previous": return 88;
            case "rewind": return 89;
            case "fastForward": return 90;
            case "mute": return 164;
            case "info": return 165;
            case "channelUp": return 166;
            case "channelDown": return 167;
            case "guide": return 172;
            case "captions": return 175;
            case "settings": return 176;
            case "tvPower": return 177;
            case "input": return 178;
            case "appSwitch": return 187;
            case "sleep": return 223;
            case "wake": return 224;
            case "allApps": return 284;
            case "enter": return 66;
            case "delete": return 67;
            case "play": return 126;
            case "pause": return 127;
            case "red": return 183;
            case "green": return 184;
            case "yellow": return 185;
            case "blue": return 186;
            case "digit0": return 7;
            case "digit1": return 8;
            case "digit2": return 9;
            case "digit3": return 10;
            case "digit4": return 11;
            case "digit5": return 12;
            case "digit6": return 13;
            case "digit7": return 14;
            case "digit8": return 15;
            case "digit9": return 16;
            default: return null;
        }
    }

    private static String appLinkFor(String command) {
        if ("youtube".equals(command)) return "https://www.youtube.com/tv";
        if ("hotstar".equals(command)) return "https://www.hotstar.com/";
        if ("netflix".equals(command)) return "https://www.netflix.com/";
        if ("hdmi1".equals(command)) {
            return "content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHW2";
        }
        return null;
    }

    static X509Certificate peerCertificate(SSLSocket socket) throws Exception {
        SSLSession session = socket.getSession();
        java.security.cert.Certificate[] chain = session.getPeerCertificates();
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            throw new SecurityException("The TV did not provide a certificate.");
        }
        return (X509Certificate) chain[0];
    }

    static String sha256Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder text = new StringBuilder(hash.length * 2);
        for (byte value : hash) text.append(String.format(Locale.US, "%02x", value & 0xff));
        return text.toString();
    }

    private static boolean isOpen(Socket socket) {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private static void requireHost(String host) {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("TV address is missing.");
    }

    private void closePairingLocked() {
        closeQuietly(pairingSocket);
        pairingSocket = null;
        pairingServerCertificate = null;
        pairingHost = null;
    }

    private void closeRemoteLocked(SSLSocket expected) {
        if (expected != null && expected != remoteSocket) {
            closeQuietly(expected);
            return;
        }
        closeQuietly(remoteSocket);
        remoteSocket = null;
        remoteState.connected = false;
        remoteState.power = null;
        remoteState.currentApp = "";
        remoteState.keyboardActive = false;
        remoteState.keyboardTextLength = -1;
        remoteState.volume = null;
        if (volumeListener != null) volumeListener.onVolumeChanged(null);
        if (stateListener != null) stateListener.onStateChanged(remoteState.copy());
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        closed = true;
        synchronized (pairingLock) { closePairingLocked(); }
        synchronized (remoteWriteLock) { closeRemoteLocked(remoteSocket); }
        readerExecutor.shutdownNow();
    }

    static final class KeyMaterial {
        final X509Certificate certificate;
        final PrivateKey privateKey;

        KeyMaterial(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }
    }

    private static final class RetryableSendException extends IOException {
        final String host;

        RetryableSendException(String host, Throwable cause) {
            super("TV connection was interrupted.", cause);
            this.host = host;
        }
    }

    /** Small schema-less protobuf reader for the few fields we consume. */
    private static final class ParsedMessage {
        private final java.util.Map<Integer, Long> varints = new java.util.HashMap<>();
        private final java.util.Map<Integer, byte[]> bytes = new java.util.HashMap<>();

        static ParsedMessage parse(byte[] payload) throws IOException {
            ParsedMessage parsed = new ParsedMessage();
            int[] offset = {0};
            while (offset[0] < payload.length) {
                long tag = readVarint(payload, offset);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (field <= 0) throw new IOException("Invalid protobuf field from TV.");
                switch (wire) {
                    case 0:
                        parsed.varints.put(field, readVarint(payload, offset));
                        break;
                    case 1:
                        requireAvailable(payload, offset[0], 8);
                        offset[0] += 8;
                        break;
                    case 2:
                        long length = readVarint(payload, offset);
                        if (length < 0 || length > Integer.MAX_VALUE) throw new IOException("Invalid protobuf length.");
                        requireAvailable(payload, offset[0], (int) length);
                        parsed.bytes.put(field, Arrays.copyOfRange(payload, offset[0], offset[0] + (int) length));
                        offset[0] += (int) length;
                        break;
                    case 5:
                        requireAvailable(payload, offset[0], 4);
                        offset[0] += 4;
                        break;
                    default:
                        throw new IOException("Unsupported protobuf wire type " + wire + ".");
                }
            }
            return parsed;
        }

        long varintField(int field, long fallback) {
            Long value = varints.get(field);
            return value == null ? fallback : value;
        }

        byte[] bytesField(int field) {
            return bytes.get(field);
        }

        String stringField(int field) {
            byte[] value = bytes.get(field);
            return value == null ? "" : new String(value, StandardCharsets.UTF_8);
        }

        private static long readVarint(byte[] data, int[] offset) throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (offset[0] >= data.length) throw new EOFException("Truncated protobuf value.");
                int value = data[offset[0]++] & 0xff;
                result |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) return result;
            }
            throw new IOException("Malformed protobuf varint.");
        }

        private static void requireAvailable(byte[] data, int offset, int count) throws IOException {
            if (count < 0 || offset < 0 || offset + count < offset || offset + count > data.length) {
                throw new EOFException("Truncated protobuf message.");
            }
        }
    }

    static final class ClientKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "android-tv-remote";
        private final X509Certificate certificate;
        private final PrivateKey privateKey;

        ClientKeyManager(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{ALIAS}; }
        @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[]{certificate}; }
        @Override public PrivateKey getPrivateKey(String alias) { return privateKey; }
    }

    @SuppressWarnings("CustomX509TrustManager")
    private static final class LocalTvTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
