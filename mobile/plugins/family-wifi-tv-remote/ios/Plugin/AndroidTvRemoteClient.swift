import Foundation

final class AndroidTvRemoteClient {
    private let lock = NSRecursiveLock()
    private var remote: TvTLSConnection?
    private var pairing: TvTLSConnection?
    private var pairingHost = ""
    private var host = ""
    private var keyboardRequested = false
    private var identity: TvIdentity?
    private var state = TvRemoteState()
    var onState: (([String: Any]) -> Void)?
    var onVolume: (([String: Any]) -> Void)?

    func getState() -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        return state.result()
    }
    private func keys() throws -> TvIdentity {
        // All caller operations are serialized by the plugin worker queue.
        if let identity { return identity }
        let created = try TvIdentity(); identity = created; return created
    }
    func pair(_ requestedHost: String) throws -> [String: Any] {
        guard !requestedHost.isEmpty, requestedHost.rangeOfCharacter(from: .whitespacesAndNewlines) == nil else { throw TvRemoteError(message: "Enter a valid TV address.") }
        cancelPairing()
        if try TvIdentity.credential("remote:\(requestedHost)") != nil {
            do { try connect(requestedHost); return ["connected": true, "paired": true] }
            catch { /* The TV may have forgotten this phone. Offer manual pairing again. */ }
        }
        let channel = try TvTLSConnection(host: requestedHost, port: 6467, identity: keys(), pin: nil)
        do {
            try channel.writeFrame(TvProto.polo(10, TvProto.text(1, "atvremote") + TvProto.text(2, "TV Remote")))
            _ = try expect(channel, 11)
            let encoding = TvProto.number(1, 3) + TvProto.number(2, 6)
            try channel.writeFrame(TvProto.polo(20, TvProto.bytes(1, encoding) + TvProto.number(3, 1)))
            _ = try expect(channel, 20)
            try channel.writeFrame(TvProto.polo(30, TvProto.bytes(1, encoding) + TvProto.number(2, 1)))
            _ = try expect(channel, 31)
            pairing = channel; pairingHost = requestedHost
            return ["connected": false, "pinRequired": true]
        } catch { channel.close(); throw error }
    }
    func finishPairing(_ pin: String) throws -> [String: Any] {
        guard let channel = pairing, let server = channel.peer else { throw TvRemoteError(message: "No pairing is in progress. Select your TV again.") }
        let target = pairingHost
        defer { cancelPairing() }
        let secret = try TvIdentity.secret(client: keys().certificate, server: server, pin: pin)
        try channel.writeFrame(TvProto.polo(40, TvProto.bytes(1, secret)))
        let ack = try expect(channel, 41)
        if let returned = try ack.nested(41)?.fields[1], returned != secret { throw TvRemoteError(message: "The TV returned an invalid pairing response.") }
        try TvIdentity.saveCredential(TvIdentity.fingerprint(server), account: "remote:\(target)")
        try connect(target)
        return ["connected": true, "paired": true]
    }
    func cancelPairing() { pairing?.close(); pairing = nil; pairingHost = "" }
    private func expect(_ channel: TvTLSConnection, _ field: Int) throws -> TvProto.Message {
        let result = try channel.readFrame(deadline: .now() + 10)
        guard result.number(2) == 200, result.fields[field] != nil else {
            throw TvRemoteError(message: result.number(2) == 402 ? "The TV rejected the pairing code." : "Unexpected TV pairing response (\(result.number(2))).")
        }
        return result
    }
    private func connect(_ target: String) throws {
        guard let pin = try TvIdentity.credential("remote:\(target)") else { throw TvRemoteError(message: "Pair this TV first.", code: "PAIRING_REQUIRED") }
        let channel = try TvTLSConnection(host: target, port: 6466, identity: keys(), pin: pin)
        do {
            var initial = TvRemoteState()
            initial.ip = target; initial.keyboardEnabled = keyboardRequested
            var negotiated: UInt64 = 1 | 2 | 32 | 64 | 512 | (keyboardRequested ? 4 : 0)
            var configured = false, activated = false
            let info = TvProto.text(1, "iPhone") + TvProto.text(2, "CodePlay") + TvProto.number(3, 1)
                + TvProto.text(4, "1") + TvProto.text(5, Bundle.main.bundleIdentifier ?? "TV Remote") + TvProto.text(6, "1.0")
            let deadline = DispatchTime.now() + 10
            while true {
                let message = try channel.readFrame(deadline: deadline)
                if message.fields[3] != nil { throw TvRemoteError(message: "The TV rejected the remote handshake.") }
                _ = try initial.update(message)
                if let config = try message.nested(1) {
                    negotiated &= config.number(1)
                    guard negotiated & 2 != 0 else { throw TvRemoteError(message: "The TV remote service does not support key commands.", code: "UNSUPPORTED_FEATURE") }
                    try channel.writeFrame(TvProto.bytes(1, TvProto.number(1, negotiated) + TvProto.bytes(2, info)))
                    configured = true; activated = false
                }
                if message.fields[2] != nil {
                    guard configured else { throw TvRemoteError(message: "TV requested activation before configuration.") }
                    try channel.writeFrame(TvProto.bytes(2, TvProto.number(1, negotiated))); activated = true
                }
                try respondToPing(message, channel)
                if message.fields[40] != nil {
                    guard activated else { throw TvRemoteError(message: "TV started the session before activation.") }
                    break
                }
            }
            initial.connected = true; initial.keyboardEnabled = keyboardRequested && negotiated & 4 != 0
            lock.lock()
            let previous = remote
            remote = channel; host = target; state = initial
            publish(volume: true)
            lock.unlock()
            previous?.close()
            DispatchQueue.global(qos: .utility).async { [weak self] in self?.readRemote(channel) }
        } catch { channel.close(); throw error }
    }
    private func respondToPing(_ message: TvProto.Message, _ channel: TvTLSConnection) throws {
        if let ping = try message.nested(8) { try channel.writeFrame(TvProto.bytes(9, TvProto.number(1, ping.number(1)))) }
    }
    private func readRemote(_ channel: TvTLSConnection) {
        do {
            while channel.isOpen {
                let message = try channel.readFrame(deadline: .distantFuture)
                if message.fields[3] != nil { throw TvRemoteError(message: "TV rejected a remote message.") }
                lock.lock()
                if remote !== channel { lock.unlock(); break }
                do {
                    if try state.update(message) { publish(volume: message.fields[50] != nil) }
                    lock.unlock()
                } catch { lock.unlock(); throw error }
                try respondToPing(message, channel)
            }
        } catch { /* A connection failure publishes unavailable state; private input is never logged. */ }
        channel.close()
        lock.lock(); defer { lock.unlock() }
        if remote === channel {
            remote = nil; state.connected = false; state.power = nil; state.currentApp = ""
            state.keyboardActive = false; state.keyboardTextLength = nil; state.volume = ["available": false]
            publish(volume: true)
        }
    }
    private func publish(volume: Bool) {
        // Caller holds the recursive state lock; callbacks only enqueue JavaScript notifications.
        onState?(state.result())
        if volume { var value = state.volume; value["ip"] = state.ip; onVolume?(value) }
    }
    private func ensureConnected() throws {
        lock.lock(); let open = remote?.isOpen == true; let target = host; lock.unlock()
        if open { return }
        guard !target.isEmpty else { throw TvRemoteError(message: "Connect to a TV first.") }
        try connect(target)
    }
    func send(_ command: String) throws {
        if command == "keyboardSearch" {
            try ensureConnected()
            lock.lock(); defer { lock.unlock() }
            guard state.keyboardEnabled, state.features & 4 != 0 else { throw TvRemoteError(message: "Enable keyboard mode and open a TV search field first.", code: "UNSUPPORTED_FEATURE") }
            guard let channel = remote else { throw TvRemoteError(message: "TV connection was lost.") }
            // Android IME_ACTION_SEARCH (3) in the editor-action message, not KEYCODE_ENTER.
            let action = TvProto.bytes(5, TvProto.number(1, 3))
            let batch = TvProto.number(1, state.imeCounter) + TvProto.number(2, state.fieldCounter) + TvProto.bytes(3, action)
            try channel.writeFrame(TvProto.bytes(21, batch))
            return
        }
        guard TvCommands.keys[command] != nil || TvCommands.links[command] != nil else { throw TvRemoteError(message: "Unknown remote command.", code: "UNSUPPORTED_FEATURE") }
        try ensureConnected()
        lock.lock(); defer { lock.unlock() }
        guard state.features & TvCommands.feature(command) != 0 else { throw TvRemoteError(message: "This TV does not support this control.", code: "UNSUPPORTED_FEATURE") }
        guard let channel = remote else { throw TvRemoteError(message: "TV connection was lost.") }
        let message: Data
        if let link = TvCommands.links[command] { message = TvProto.bytes(90, TvProto.text(1, link)) }
        else { message = TvProto.bytes(10, TvProto.number(1, TvCommands.keys[command]!) + TvProto.number(2, 3)) }
        try channel.writeFrame(message)
    }
    func setKeyboardEnabled(_ enabled: Bool) throws -> [String: Any] {
        lock.lock(); let supported = state.features & 4 != 0; let target = host; lock.unlock()
        guard !target.isEmpty else { throw TvRemoteError(message: "Connect to a TV first.") }
        if enabled && !supported { throw TvRemoteError(message: "This TV does not support remote keyboard input.", code: "UNSUPPORTED_FEATURE") }
        let previous = keyboardRequested
        keyboardRequested = enabled
        do { try connect(target) } catch { keyboardRequested = previous; throw error }
        return getState()
    }
    func sendText(_ text: String) throws {
        guard !text.isEmpty, text.utf16.count <= 2000 else { throw TvRemoteError(message: "Enter between 1 and 2000 characters.", code: "INVALID_INPUT") }
        try ensureConnected()
        lock.lock(); defer { lock.unlock() }
        guard state.keyboardEnabled, state.features & 4 != 0 else { throw TvRemoteError(message: "Keyboard input is not enabled or supported on this TV.", code: "UNSUPPORTED_FEATURE") }
        // Some TV keyboards omit focus notifications; use the latest counters for explicit sends.
        guard let channel = remote else { throw TvRemoteError(message: "TV connection was lost.") }
        let cursor = UInt64(text.utf16.count - 1)
        let field = TvProto.number(1, cursor) + TvProto.number(2, cursor) + TvProto.text(3, text)
        let edit = TvProto.number(1, 1) + TvProto.bytes(2, field)
        try channel.writeFrame(TvProto.bytes(21, TvProto.number(1, state.imeCounter) + TvProto.number(2, state.fieldCounter) + TvProto.bytes(3, edit)))
        state.keyboardTextLength = nil
    }
    func replaceText(_ text: String) throws {
        guard text.utf16.count <= 2000 else { throw TvRemoteError(message: "Enter up to 2000 characters.", code: "INVALID_INPUT") }
        try ensureConnected()
        lock.lock(); defer { lock.unlock() }
        guard state.keyboardEnabled, state.features & 4 != 0 else { throw TvRemoteError(message: "Keyboard input is not enabled or supported on this TV.", code: "UNSUPPORTED_FEATURE") }
        guard let channel = remote else { throw TvRemoteError(message: "TV connection was lost.") }
        guard let length = state.keyboardTextLength else { throw TvRemoteError(message: "This TV does not report its text length. Clear the TV field and use sendText instead.", code: "TEXT_FIELD_UNKNOWN") }
        // Some TV apps silently reject out-of-bounds ranges. Only use a reported length.
        let end = UInt64(length)
        let field = TvProto.number(1, 0) + TvProto.number(2, end) + TvProto.text(3, text)
        let edit = TvProto.bytes(2, field)
        try channel.writeFrame(TvProto.bytes(21, TvProto.number(1, state.imeCounter) + TvProto.number(2, state.fieldCounter) + TvProto.bytes(3, edit)))
        state.keyboardTextLength = nil
    }
    func close() {
        cancelPairing()
        lock.lock(); let channel = remote; remote = nil; lock.unlock()
        channel?.close()
    }
    deinit { close() }
}
