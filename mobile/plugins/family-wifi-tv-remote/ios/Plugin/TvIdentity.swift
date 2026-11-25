import Foundation
import Security
import CryptoKit

enum TvDER {
    static func value(_ tag: UInt8, _ content: Data) -> Data {
        var length = Data()
        if content.count < 128 { length.append(UInt8(content.count)) }
        else {
            var count = content.count
            var bytes = [UInt8]()
            while count > 0 { bytes.insert(UInt8(count & 255), at: 0); count >>= 8 }
            length.append(128 | UInt8(bytes.count)); length.append(contentsOf: bytes)
        }
        return Data([tag]) + length + content
    }
    static func sequence(_ content: Data) -> Data { value(0x30, content) }
    static func integer(_ content: Data) -> Data {
        var bytes = [UInt8](content)
        while bytes.count > 1 && bytes[0] == 0 { bytes.removeFirst() }
        if bytes.isEmpty { bytes = [0] }
        if bytes[0] & 128 != 0 { bytes.insert(0, at: 0) }
        return value(2, Data(bytes))
    }
    struct Reader {
        private let bytes: [UInt8]
        private var offset = 0
        init(_ data: Data) { bytes = [UInt8](data) }
        mutating func read(_ expectedTag: UInt8) throws -> Data {
            guard offset + 2 <= bytes.count, bytes[offset] == expectedTag else { throw TvRemoteError(message: "Invalid RSA certificate encoding.") }
            offset += 1
            var size = Int(bytes[offset]); offset += 1
            if size & 128 != 0 {
                let count = size & 127
                guard count > 0 && count <= 4 && offset + count <= bytes.count else { throw TvRemoteError(message: "Invalid RSA certificate length.") }
                size = 0
                for _ in 0..<count { size = size << 8 | Int(bytes[offset]); offset += 1 }
            }
            guard size <= bytes.count - offset else { throw TvRemoteError(message: "Truncated RSA certificate.") }
            let value = Data(bytes[offset..<(offset + size)]); offset += size
            return value
        }
    }
}

// Client private keys remain in this app's Keychain.
final class TvIdentity {
    let identity: SecIdentity
    let certificate: SecCertificate
    private static let label = "Codeplay TV Remote client v1"
    private static let keyTag = Data("com.codeplay.tvremote.client.v1".utf8)
    private static let credentialService = "com.codeplay.tvremote.credentials.v1"

    init() throws {
        var keyResult: CFTypeRef?
        let keyQuery: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: Self.keyTag,
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecReturnRef as String: true]
        let keyStatus = SecItemCopyMatching(keyQuery as CFDictionary, &keyResult)
        let key: SecKey
        if keyStatus == errSecSuccess, let result = keyResult { key = result as! SecKey }
        else if keyStatus == errSecItemNotFound {
            var error: Unmanaged<CFError>?
            let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048,
                kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true, kSecAttrApplicationTag as String: Self.keyTag,
                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]]
            guard let generated = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
                throw TvRemoteError(message: "Could not create the TV client key. Unlock the phone and try again.")
            }
            key = generated
        } else { throw Self.storageError(keyStatus) }

        var certificateResult: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: Self.label,
            kSecReturnRef as String: true] as CFDictionary, &certificateResult)
        let certificate: SecCertificate
        if status == errSecSuccess, let result = certificateResult { certificate = result as! SecCertificate }
        else if status == errSecItemNotFound {
            let encoded = try Self.selfSignedCertificate(key)
            guard let created = SecCertificateCreateWithData(nil, encoded as CFData) else { throw TvRemoteError(message: "Could not create the TV client certificate.") }
            let addStatus = SecItemAdd([kSecClass as String: kSecClassCertificate, kSecValueRef as String: created,
                kSecAttrLabel as String: Self.label] as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw Self.storageError(addStatus) }
            certificate = created
        } else { throw Self.storageError(status) }

        // iOS automatically associates a stored certificate with its matching private key.
        var identitiesResult: CFTypeRef?
        let identityStatus = SecItemCopyMatching([kSecClass as String: kSecClassIdentity, kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll] as CFDictionary, &identitiesResult)
        guard identityStatus == errSecSuccess, let identities = identitiesResult as? [SecIdentity],
              let matched = identities.first(where: { item in
                  var candidate: SecCertificate?
                  return SecIdentityCopyCertificate(item, &candidate) == errSecSuccess && candidate.map {
                      (SecCertificateCopyData($0) as Data) == (SecCertificateCopyData(certificate) as Data)
                  } == true
              }) else { throw TvRemoteError(message: "Could not load the TV client identity from Keychain.") }
        self.identity = matched
        self.certificate = certificate
    }

    private static func selfSignedCertificate(_ key: SecKey) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(key) else { throw TvRemoteError(message: "Missing RSA public key.") }
        var error: Unmanaged<CFError>?
        guard let exported = SecKeyCopyExternalRepresentation(publicKey, &error) else { throw TvRemoteError(message: "Could not export the RSA public key.") }
        let rsaAlgorithm = TvDER.sequence(TvDER.value(6, Data([0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 1, 1, 1])) + TvDER.value(5, Data()))
        let signatureAlgorithm = TvDER.sequence(TvDER.value(6, Data([0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 1, 1, 11])) + TvDER.value(5, Data()))
        let name = TvDER.sequence(TvDER.value(0x31, TvDER.sequence(TvDER.value(6, Data([0x55, 4, 3])) + TvDER.value(12, Data("TV Remote".utf8)))))
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMddHHmmss'Z'"
        let now = Date()
        let validity = TvDER.sequence(TvDER.value(0x18, Data(formatter.string(from: now.addingTimeInterval(-86400)).utf8))
            + TvDER.value(0x18, Data(formatter.string(from: now.addingTimeInterval(20 * 365 * 86400)).utf8)))
        var serial = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial) == errSecSuccess else { throw TvRemoteError(message: "Could not generate a certificate serial number.") }
        let publicInfo = TvDER.sequence(rsaAlgorithm + TvDER.value(3, Data([0]) + (exported as Data)))
        var certificateBody = TvDER.value(0xa0, TvDER.integer(Data([2]))) + TvDER.integer(Data(serial))
        certificateBody += signatureAlgorithm + name + validity
        certificateBody += name + publicInfo
        let body = TvDER.sequence(certificateBody)
        guard let signature = SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256, body as CFData, &error) else { throw TvRemoteError(message: "Could not sign the TV client certificate.") }
        return TvDER.sequence(body + signatureAlgorithm + TvDER.value(3, Data([0]) + (signature as Data)))
    }

    static func fingerprint(_ certificate: SecCertificate) -> String { hash(SecCertificateCopyData(certificate) as Data) }
    static func hash(_ data: Data) -> String { hex(Data(SHA256.hash(data: data))) }
    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    static func secret(client: SecCertificate, server: SecCertificate, pin: String) throws -> Data {
        let normalized = pin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard normalized.range(of: "^[0-9A-F]{6}$", options: .regularExpression) != nil else {
            throw TvRemoteError(message: "Enter the 6-character hexadecimal code shown on the TV.", code: "INVALID_PIN")
        }
        func components(_ certificate: SecCertificate) throws -> Data {
            guard let key = SecCertificateCopyKey(certificate),
                  let attributes = SecKeyCopyAttributes(key) as? [String: Any],
                  attributes[kSecAttrKeyType as String] as? String == kSecAttrKeyTypeRSA as String,
                  let raw = SecKeyCopyExternalRepresentation(key, nil) else { throw TvRemoteError(message: "TV pairing requires an RSA certificate.") }
            var outer = TvDER.Reader(raw as Data)
            var inner = TvDER.Reader(try outer.read(0x30))
            func unsigned(_ data: Data) -> Data { Data(data.drop(while: { $0 == 0 })) }
            return try unsigned(inner.read(2)) + unsigned(inner.read(2))
        }
        let chars = Array(normalized)
        let suffix = Data([UInt8(String(chars[2...3]), radix: 16)!, UInt8(String(chars[4...5]), radix: 16)!])
        let secret = Data(SHA256.hash(data: try components(client) + components(server) + suffix))
        guard secret.first == UInt8(String(chars[0...1]), radix: 16) else { throw TvRemoteError(message: "The pairing code does not match this TV. Use the current TV code.", code: "INVALID_PIN") }
        return secret
    }
    static func credential(_ account: String) throws -> String? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: credentialService,
            kSecAttrAccount as String: account, kSecReturnData as String: true] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) else { throw storageError(status) }
        return value
    }
    static func saveCredential(_ value: String, account: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: credentialService, kSecAttrAccount as String: account]
        let values: [String: Any] = [kSecValueData as String: Data(value.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw storageError(status) }
    }
    private static func storageError(_ status: OSStatus) -> TvRemoteError { TvRemoteError(message: "TV Keychain access failed (\(status)). Unlock the phone and try again.") }
}
