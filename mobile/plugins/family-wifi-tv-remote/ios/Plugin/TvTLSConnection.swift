import Foundation
import Network
import Security

// Blocking operations run only on plugin worker/reader queues, never the main or Network queue.
final class TvTLSConnection {
    private let connection: NWConnection
    private let networkQueue = DispatchQueue(label: "com.codeplay.tvremote.tls")
    private let lock = NSLock()
    private let writeLock = NSLock()
    private var buffer = Data()
    private var certificate: SecCertificate?
    private var failure: Error?
    private var ended = false
    private var closed = false

    init(host: String, port: UInt16, identity: TvIdentity, pin: String?) throws {
        guard !host.isEmpty, let endpointPort = NWEndpoint.Port(rawValue: port),
              let localIdentity = sec_identity_create(identity.identity) else { throw TvRemoteError(message: "Missing TV address or client identity.") }
        let options = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_local_identity(options.securityProtocolOptions, localIdentity)
        let verified = PeerBox()
        sec_protocol_options_set_verify_block(options.securityProtocolOptions, { _, trust, complete in
            let peerTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let peer = SecTrustGetCertificateAtIndex(peerTrust, 0) else { complete(false); return }
            let actual = TvIdentity.fingerprint(peer)
            guard pin == nil || actual == pin?.lowercased() else {
                verified.set(nil, error: TvRemoteError(message: "TV identity changed. Pair this TV again.", code: "PAIRING_REQUIRED"))
                complete(false); return
            }
            // During manual pairing, the certificate-bound TV PIN authenticates this self-signed peer.
            verified.set(peer, error: nil)
            complete(true)
        }, networkQueue)
        connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: NWParameters(tls: options))
        let ready = DispatchSemaphore(value: 0)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { ready.signal(); return }
            switch state {
            case .ready: ready.signal()
            case .failed(let error):
                self.lock.lock(); if self.failure == nil { self.failure = error }; self.closed = true; self.lock.unlock(); ready.signal()
            case .cancelled:
                self.lock.lock(); self.closed = true; self.lock.unlock(); ready.signal()
            default: break
            }
        }
        connection.start(queue: networkQueue)
        if ready.wait(timeout: .now() + 8) == .timedOut { close(); throw TvRemoteError(message: "TV connection timed out. Check the local network and TV.") }
        let verification = verified.get()
        lock.lock(); certificate = verification.certificate; let error = verification.error ?? failure; let connected = !closed && certificate != nil; lock.unlock()
        if let error { close(); throw error }
        guard connected else { close(); throw TvRemoteError(message: "TV TLS connection could not start.") }
    }
    var peer: SecCertificate? { lock.lock(); defer { lock.unlock() }; return certificate }
    var isOpen: Bool { lock.lock(); defer { lock.unlock() }; return !closed }
    func close() { lock.lock(); closed = true; lock.unlock(); connection.cancel() }

    func write(_ data: Data) throws {
        writeLock.lock(); defer { writeLock.unlock() }
        guard isOpen else { throw TvRemoteError(message: "TV connection was lost. Connect again.") }
        let done = DispatchSemaphore(value: 0)
        let result = ResultBox()
        connection.send(content: data, completion: .contentProcessed { error in
            result.set(Data(), error: error, complete: false); done.signal()
        })
        guard done.wait(timeout: .now() + 10) == .success else { close(); throw TvRemoteError(message: "TV command timed out.") }
        if let error = result.get().error { close(); throw error }
    }
    // A read has a single owner: pairing/HTTP worker or the active remote reader.
    func read(_ count: Int, deadline: DispatchTime) throws -> Data {
        while buffer.count < count {
            guard !ended, isOpen else { throw TvRemoteError(message: "TV closed the connection.") }
            let done = DispatchSemaphore(value: 0)
            let result = ResultBox()
            connection.receive(minimumIncompleteLength: 1, maximumLength: max(8192, count - buffer.count)) { data, _, complete, error in
                result.set(data ?? Data(), error: error, complete: complete); done.signal()
            }
            guard done.wait(timeout: deadline) == .success else { close(); throw TvRemoteError(message: "Timed out waiting for the TV.") }
            let value = result.get()
            if let error = value.error { close(); throw error }
            buffer.append(value.data); ended = value.complete
            if value.data.isEmpty && !value.complete { close(); throw TvRemoteError(message: "TV returned an empty connection response.") }
        }
        let data = Data(buffer.prefix(count)); buffer.removeFirst(count)
        return data
    }
    func writeFrame(_ data: Data) throws { try write(TvProto.varint(UInt64(data.count)) + data) }
    func readFrame(deadline: DispatchTime) throws -> TvProto.Message {
        var length: UInt64 = 0
        for shift in stride(from: 0, through: 28, by: 7) {
            let byte = try read(1, deadline: deadline)[0]
            length |= UInt64(byte & 127) << shift
            if byte & 128 == 0 {
                guard length > 0 && length <= 65536 else { throw TvRemoteError(message: "Invalid TV frame size.") }
                return try TvProto.parse(read(Int(length), deadline: deadline))
            }
        }
        throw TvRemoteError(message: "Invalid TV frame length.")
    }
    private final class ResultBox {
        private let lock = NSLock()
        private var value: (data: Data, error: Error?, complete: Bool) = (Data(), nil, false)
        func set(_ data: Data, error: Error?, complete: Bool) { lock.lock(); value = (data, error, complete); lock.unlock() }
        func get() -> (data: Data, error: Error?, complete: Bool) { lock.lock(); defer { lock.unlock() }; return value }
    }
    private final class PeerBox {
        private let lock = NSLock()
        private var certificate: SecCertificate?
        private var error: Error?
        func set(_ certificate: SecCertificate?, error: Error?) { lock.lock(); self.certificate = certificate; self.error = error; lock.unlock() }
        func get() -> (certificate: SecCertificate?, error: Error?) { lock.lock(); defer { lock.unlock() }; return (certificate, error) }
    }
}
