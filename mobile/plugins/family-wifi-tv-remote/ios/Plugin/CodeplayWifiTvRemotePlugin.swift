import Capacitor
import Foundation
import Darwin
import Network

@objc(CodeplayWifiTvRemotePlugin)
public class CodeplayWifiTvRemotePlugin: CAPPlugin, NetServiceBrowserDelegate, NetServiceDelegate {
    private var browser: NetServiceBrowser?
    private var pendingCall: CAPPluginCall?
    private var devices: [[String: Any]] = []
    private var services: [NetService] = []
    private var scanID = UUID()
    private let worker = DispatchQueue(label: "com.codeplay.tvremote.operations", qos: .userInitiated)
    private let client = AndroidTvRemoteClient()
    private var networkMonitor: NWPathMonitor?

    public override func load() {
        let monitor = NWPathMonitor()
        networkMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.notifyListeners("networkChanged", data: self?.networkState(path) ?? [:], retainUntilConsumed: true)
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.codeplay.tvremote.network-monitor"))
        client.onState = { [weak self] state in
            DispatchQueue.main.async { self?.notifyListeners("stateChanged", data: state, retainUntilConsumed: true) }
        }
        client.onVolume = { [weak self] volume in
            DispatchQueue.main.async { self?.notifyListeners("volumeChanged", data: volume, retainUntilConsumed: true) }
        }
    }

    private func networkState(_ path: NWPath) -> [String: Any] {
        [
            "wifiConnected": path.status == .satisfied && path.usesInterfaceType(.wifi),
            "cellularConnected": path.status == .satisfied && path.usesInterfaceType(.cellular),
            "internetAvailable": path.status == .satisfied,
        ]
    }

    @objc func getNetworkState(_ call: CAPPluginCall) {
        let monitor = NWPathMonitor()
        let queue = DispatchQueue(label: "com.codeplay.tvremote.network-state")
        monitor.pathUpdateHandler = { [weak self] path in
            monitor.cancel()
            call.resolve(self?.networkState(path) ?? [:])
        }
        monitor.start(queue: queue)
    }

    @objc func openWifiSettings(_ call: CAPPluginCall) {
        // iOS does not expose a public, App Store-safe URL for opening the
        // system Wi-Fi picker. The UI gives the user the Settings > Wi-Fi path.
        call.resolve(["opened": false])
    }

    @objc func discover(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { call.reject("TV remote plugin was closed."); return }
            self.pendingCall?.reject("A new TV scan was started.", "DISCOVERY_CANCELLED")
            self.browser?.stop()
            self.services.forEach { $0.stop(); $0.delegate = nil }
            self.pendingCall = call; self.devices.removeAll(); self.services.removeAll()
            self.scanID = UUID()
            let id = self.scanID
            let browser = NetServiceBrowser()
            self.browser = browser; browser.delegate = self
            browser.searchForServices(ofType: "_androidtvremote2._tcp.", inDomain: "local.")
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
                guard let self, self.scanID == id else { return }
                self.browser?.stop(); self.browser = nil
                self.services.forEach { $0.stop(); $0.delegate = nil }
                self.pendingCall?.resolve(["devices": self.devices]); self.pendingCall = nil
            }
        }
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard browser === self.browser, pendingCall != nil else { return }
        service.delegate = self; services.append(service); service.resolve(withTimeout: 3)
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        guard browser === self.browser else { return }
        browser.stop(); self.browser = nil
        pendingCall?.reject("TV discovery could not start. Allow Local Network access in iOS Settings and use the TV's Wi-Fi network.", "DISCOVERY_UNAVAILABLE")
        pendingCall = nil
    }

    public func netServiceDidResolveAddress(_ sender: NetService) {
        guard pendingCall != nil, services.contains(where: { $0 === sender }) else { return }
        // Prefer IPv4 so the shared UI and certificate pins use the same numeric TV address.
        let addresses: [(Int32, String)] = (sender.addresses ?? []).compactMap { data in
            data.withUnsafeBytes { raw -> (Int32, String)? in
                guard raw.count >= MemoryLayout<sockaddr>.size, let base = raw.baseAddress else { return nil }
                let address = base.assumingMemoryBound(to: sockaddr.self)
                let family = Int32(address.pointee.sa_family)
                guard family == AF_INET || family == AF_INET6 else { return nil }
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(address, socklen_t(raw.count), &host, socklen_t(NI_MAXHOST), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return (family, String(cString: host))
            }
        }
        guard let address = addresses.first(where: { $0.0 == AF_INET })?.1 ?? addresses.first?.1 else { return }
        guard !devices.contains(where: { $0["ip"] as? String == address }) else { return }
        devices.append(["name": sender.name, "host": address, "ip": address, "port": sender.port, "pairingPort": 6467])
    }

    private func perform(_ call: CAPPluginCall, _ action: @escaping (AndroidTvRemoteClient) throws -> [String: Any]) {
        worker.async { [weak self] in
            guard let self else { call.reject("TV remote plugin was closed."); return }
            do { call.resolve(try action(self.client)) }
            catch let error as TvRemoteError { call.reject(error.message, error.code) }
            catch { call.reject(error.localizedDescription, "REMOTE_UNAVAILABLE") }
        }
    }

    @objc func pair(_ call: CAPPluginCall) {
        let ip = call.getString("ip") ?? call.getString("host") ?? ""
        perform(call) { try $0.pair(ip) }
    }

    @objc func finishPairing(_ call: CAPPluginCall) {
        let pin = call.getString("pin") ?? ""
        perform(call) { try $0.finishPairing(pin) }
    }

    @objc func cancelPairing(_ call: CAPPluginCall) {
        perform(call) { $0.cancelPairing(); return [:] }
    }

    @objc func send(_ call: CAPPluginCall) {
        let command = call.getString("command") ?? ""
        perform(call) { try $0.send(command); return ["sent": true] }
    }
    @objc func getState(_ call: CAPPluginCall) { call.resolve(client.getState()) }
    @objc func setKeyboardEnabled(_ call: CAPPluginCall) {
        let enabled = call.getBool("enabled") ?? false
        perform(call) { try $0.setKeyboardEnabled(enabled) }
    }
    @objc func sendText(_ call: CAPPluginCall) {
        let text = call.getString("text") ?? ""
        perform(call) { try $0.sendText(text); return [:] }
    }
    @objc func replaceText(_ call: CAPPluginCall) {
        guard let text = call.getString("text") else { call.reject("Text is required.", "INVALID_INPUT"); return }
        perform(call) { try $0.replaceText(text); return [:] }
    }
    deinit { browser?.stop(); services.forEach { $0.stop() }; networkMonitor?.cancel(); client.close() }
}
