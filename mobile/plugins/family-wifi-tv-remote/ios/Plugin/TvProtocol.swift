import Foundation

struct TvRemoteError: LocalizedError {
    let message: String
    var code = "REMOTE_UNAVAILABLE"
    var errorDescription: String? { message }
}

// The small v2/Polo subset used by the Android implementation, without a protobuf dependency.
enum TvProto {
    struct Message {
        var numbers: [Int: UInt64] = [:]
        var fields: [Int: Data] = [:]
        func number(_ field: Int) -> UInt64 { numbers[field] ?? 0 }
        func text(_ field: Int) -> String { fields[field].flatMap { String(data: $0, encoding: .utf8) } ?? "" }
        func nested(_ field: Int) throws -> Message? { try fields[field].map { try TvProto.parse($0) } }
    }
    static func varint(_ value: UInt64) -> Data {
        var value = value
        var result = Data()
        repeat {
            let byte = UInt8(value & 127)
            value >>= 7
            result.append(byte | (value > 0 ? 128 : 0))
        } while value > 0
        return result
    }
    static func number(_ field: Int, _ value: UInt64) -> Data { varint(UInt64(field << 3)) + varint(value) }
    static func bytes(_ field: Int, _ value: Data) -> Data { varint(UInt64(field << 3 | 2)) + varint(UInt64(value.count)) + value }
    static func text(_ field: Int, _ value: String) -> Data { bytes(field, Data(value.utf8)) }
    static func polo(_ field: Int, _ value: Data) -> Data { number(1, 2) + number(2, 200) + bytes(field, value) }
    static func parse(_ data: Data) throws -> Message {
        let raw = [UInt8](data)
        var offset = 0
        func readNumber() throws -> UInt64 {
            var value: UInt64 = 0
            for shift in stride(from: 0, through: 63, by: 7) {
                guard offset < raw.count else { throw TvRemoteError(message: "Truncated TV message.") }
                let byte = raw[offset]; offset += 1
                guard shift < 63 || byte <= 1 else { throw TvRemoteError(message: "Invalid TV message integer.") }
                value |= UInt64(byte & 127) << shift
                if byte & 128 == 0 { return value }
            }
            throw TvRemoteError(message: "Invalid TV message integer.")
        }
        var message = Message()
        while offset < raw.count {
            let tag = try readNumber()
            guard tag >> 3 > 0, tag >> 3 <= UInt64(Int32.max) else { throw TvRemoteError(message: "Invalid TV message field.") }
            let field = Int(tag >> 3)
            switch tag & 7 {
            case 0: message.numbers[field] = try readNumber()
            case 2:
                let length = try readNumber()
                guard length <= UInt64(raw.count - offset) else { throw TvRemoteError(message: "Truncated TV message field.") }
                message.fields[field] = Data(raw[offset..<(offset + Int(length))]); offset += Int(length)
            case 1, 5:
                let size = tag & 7 == 1 ? 8 : 4
                guard offset + size <= raw.count else { throw TvRemoteError(message: "Truncated TV message field.") }
                offset += size
            default: throw TvRemoteError(message: "Unsupported TV message encoding.")
            }
        }
        return message
    }
}

struct TvRemoteState {
    var connected = false
    var ip = ""
    var manufacturer = ""
    var model = ""
    var serviceVersion = ""
    var power: Bool?
    var currentApp = ""
    var features: UInt64 = 0
    var keyboardEnabled = false
    var keyboardActive = false
    var imeCounter: UInt64 = 0
    var fieldCounter: UInt64 = 0
    var keyboardTextLength: Int? = nil
    var volume: [String: Any] = ["available": false]

    mutating func update(_ message: TvProto.Message) throws -> Bool {
        var changed = false
        if let configure = try message.nested(1) {
            features = configure.number(1)
            if let info = try configure.nested(2) {
                model = info.text(1); manufacturer = info.text(2); serviceVersion = info.text(6)
            }
            changed = true
        }
        if let start = try message.nested(40) { power = start.number(1) != 0; changed = true }
        if let value = try message.nested(50) {
            let maximum = value.number(6), level = value.number(7)
            volume = maximum > 0 && maximum <= UInt64(Int32.max) && level <= maximum
                ? ["available": true, "level": Int(level), "max": Int(maximum), "muted": value.number(8) != 0]
                : ["available": false]
            changed = true
        }
        if let ime = try message.nested(20) {
            if let app = try ime.nested(1) {
                let next = app.text(12)
                if next != currentApp { keyboardActive = false; keyboardTextLength = nil }
                currentApp = next
            }
            if let field = try ime.nested(2) {
                keyboardActive = true
                fieldCounter = field.number(1)
                keyboardTextLength = field.fields[2] != nil ? field.text(2).utf16.count : nil
            }
            changed = true
        }
        if let edit = try message.nested(21) {
            if fieldCounter != edit.number(2) { keyboardTextLength = nil }
            imeCounter = edit.number(1); fieldCounter = edit.number(2); keyboardActive = true; changed = true
        }
        if let show = try message.nested(22) {
            keyboardActive = show.fields[2] != nil
            keyboardTextLength = nil
            if let field = try show.nested(2) { fieldCounter = field.number(1); keyboardTextLength = field.fields[2] != nil ? field.text(2).utf16.count : nil }
            changed = true
        }
        return changed
    }
    func result() -> [String: Any] {
        ["connected": connected, "ip": ip, "manufacturer": manufacturer, "model": model,
         "serviceVersion": serviceVersion, "power": power.map { $0 as Any } ?? NSNull(), "currentApp": currentApp,
         "keyboardEnabled": keyboardEnabled, "keyboardActive": keyboardActive,
         "volume": volume, "capabilities": ["keys": features & 2 != 0, "volume": features & 64 != 0,
             "power": features & 32 != 0, "appLinks": features & 512 != 0, "keyboard": features & 4 != 0, "voice": features & 8 != 0]]
    }
}

enum TvCommands {
    static let keys: [String: UInt64] = [
        "home": 3, "back": 4, "up": 19, "down": 20, "left": 21, "right": 22, "ok": 23,
        "volumeUp": 24, "volumeDown": 25, "power": 26, "menu": 82, "search": 84, "playPause": 85,
        "stop": 86, "next": 87, "previous": 88, "rewind": 89, "fastForward": 90, "mute": 164,
        "info": 165, "channelUp": 166, "channelDown": 167, "guide": 172, "captions": 175,
        "settings": 176, "tvPower": 177, "input": 178, "appSwitch": 187, "sleep": 223,
        "wake": 224, "allApps": 284, "enter": 66, "delete": 67, "play": 126, "pause": 127,
        "red": 183, "green": 184, "yellow": 185, "blue": 186, "digit0": 7, "digit1": 8,
        "digit2": 9, "digit3": 10, "digit4": 11, "digit5": 12, "digit6": 13, "digit7": 14, "digit8": 15, "digit9": 16
    ]
    static let links = [
        "youtube": "https://www.youtube.com/tv",
        "hotstar": "https://www.hotstar.com/",
        "netflix": "https://www.netflix.com/",
        "hdmi1": "content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHW2"
    ]
    static func feature(_ command: String) -> UInt64 {
        if links[command] != nil { return 512 }
        if command == "mute" || command.hasPrefix("volume") { return 64 }
        if ["power", "tvPower", "sleep", "wake"].contains(command) { return 32 }
        return 2
    }
}
