import Foundation

// Standalone protocol regression checks; no device, Keychain mutation, or extra dependencies.
@main
enum ProtocolChecks {
    static func check(_ passed: @autoclosure () -> Bool, _ message: String) throws {
        if !passed() { throw TvRemoteError(message: "FAIL: \(message)") }
    }
    static func main() throws {
        let maximum = UInt64.max
        let integer = try TvProto.parse(TvProto.number(1, maximum))
        try check(integer.number(1) == maximum, "64-bit varint")
        let label = "TV 🎵 日本語"
        let string = try TvProto.parse(TvProto.text(12, label))
        try check(string.text(12) == label, "UTF-8 text")

        var state = TvRemoteState()
        let device = TvProto.text(1, "TV model") + TvProto.text(2, "TV maker") + TvProto.text(6, "2.1")
        _ = try state.update(TvProto.parse(TvProto.bytes(1, TvProto.number(1, 615) + TvProto.bytes(2, device))))
        try check(state.model == "TV model" && state.manufacturer == "TV maker" && state.serviceVersion == "2.1", "TV metadata")
        let capabilities = state.result()["capabilities"] as! [String: Bool]
        try check(capabilities["keys"] == true && capabilities["keyboard"] == true && capabilities["voice"] == false, "capabilities")

        _ = try state.update(TvProto.parse(TvProto.bytes(40, Data())))
        try check(state.power == false, "protobuf default power state")
        _ = try state.update(TvProto.parse(TvProto.bytes(50, TvProto.number(6, 100) + TvProto.number(7, 30))))
        try check(state.volume["level"] as? Int == 30 && state.volume["muted"] as? Bool == false, "actual volume")
        _ = try state.update(TvProto.parse(TvProto.bytes(50, TvProto.number(6, 100) + TvProto.number(8, 1))))
        try check(state.volume["level"] as? Int == 0 && state.volume["muted"] as? Bool == true, "muted zero report")
        _ = try state.update(TvProto.parse(TvProto.bytes(50, TvProto.number(6, 100) + TvProto.number(7, 101))))
        try check(state.volume["available"] as? Bool == false, "invalid volume range")

        _ = try state.update(TvProto.parse(TvProto.bytes(21, TvProto.number(1, 11) + TvProto.number(2, 42))))
        try check(state.keyboardActive && state.imeCounter == 11 && state.fieldCounter == 42, "IME counters")
        _ = try state.update(TvProto.parse(TvProto.bytes(20, TvProto.bytes(1, TvProto.text(12, "com.example.tv")))))
        try check(state.currentApp == "com.example.tv" && !state.keyboardActive, "app change clears keyboard focus")
        _ = try state.update(TvProto.parse(TvProto.bytes(20, TvProto.bytes(1, TvProto.text(12, "com.google.android.youtube.tv")) + TvProto.bytes(2, TvProto.number(1, 73)))))
        try check(state.keyboardActive && state.fieldCounter == 73 && state.imeCounter == 11, "inline text field without show or batch message")
        _ = try state.update(TvProto.parse(TvProto.bytes(20, TvProto.bytes(2, Data()))))
        try check(state.keyboardActive && state.fieldCounter == 0, "inline empty field uses protobuf default counter")
        _ = try state.update(TvProto.parse(TvProto.bytes(22, TvProto.bytes(2, Data()))))
        try check(state.keyboardActive && state.fieldCounter == 0, "empty text field remains active")
        _ = try state.update(TvProto.parse(TvProto.bytes(22, Data())))
        try check(!state.keyboardActive, "keyboard dismissal")
        let snapshot = state
        _ = try state.update(TvProto.parse(TvProto.bytes(40, TvProto.number(1, 1))))
        try check(snapshot.power == false && state.power == true, "independent state snapshot")
        let ping = try state.update(TvProto.parse(TvProto.bytes(8, TvProto.number(1, 42))))
        try check(!ping, "ping does not emit state changes")

        for malformed in [Data([0]), Data([10, 10, 1]), Data([8] + [UInt8](repeating: 255, count: 10))] {
            var rejected = false
            do { _ = try TvProto.parse(malformed) } catch { rejected = true }
            try check(rejected, "malformed protobuf rejection")
        }
        for digit in 0...9 { try check(TvCommands.keys["digit\(digit)"] == UInt64(digit + 7), "numeric key mapping") }
        try check(TvCommands.keys["play"] == 126 && TvCommands.keys["pause"] == 127, "separate media keys")
        try check(TvCommands.links["youtube"] == "https://www.youtube.com/tv" && TvCommands.links["hotstar"] == "https://www.hotstar.com/" && TvCommands.links["netflix"] == "https://www.netflix.com/", "generic app links")
        try check(TvCommands.links["hdmi1"] == "content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHW2", "Sony HDMI 1 app link")
        try check(TvCommands.feature("mute") == 64 && TvCommands.feature("power") == 32 && TvCommands.feature("youtube") == 512 && TvCommands.feature("hdmi1") == 512, "command capability gating")
        print("PASS: protobuf, metadata, volume/mute, capabilities, IME focus, snapshots, key mappings, app links")
    }
}
