import Foundation
import SwiftUI
import Charts
import DeepSeekHarnessShared

func gatewayPathLabel(_ kind: String) -> String {
    switch kind {
    case "local": return String(localized: "内网直连")
    case "nat": return String(localized: "NAT 穿透直连")
    case "relay": return String(localized: "TURN 中继")
    default: return String(localized: "直连")
    }
}

struct GatewayConnectionPathView: View {
    let paths: [String: RtcSelectedPath]
    private let channels = ["control", "conversation"]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("传输路径").appFont(.subheadline)
            ForEach(channels, id: \.self) { channel in
                VStack(alignment: .leading, spacing: 3) {
                    let title = channel == "control" ? String(localized: "控制通道") : String(localized: "会话通道")
                    if let path = paths[channel] {
                        Text("\(title)：\(gatewayPathLabel(path.kind)) · \(path.protocol)")
                            .appFont(.caption)
                        Text(verbatim: "\(path.localEndpoint) → \(path.remoteEndpoint)")
                            .appFont(.caption2.monospaced())
                            .textSelection(.enabled)
                    } else {
                        Text("\(title)：\(String(localized: "正在确认路径"))").appFont(.caption)
                    }
                }
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityIdentifier("gateway-connection-path")
    }
}

@MainActor
final class GatewayNetworkDiagnosticsMonitor: ObservableObject {
    @Published private(set) var snapshot: NetworkDiagnosticsSnapshot
    private let tracker: NetworkDiagnosticsStore
    private weak var gateway: GatewayClient?
    private var task: Task<Void, Never>?
    private var lease: UUID?

    init() {
        tracker = NetworkDiagnosticsStore()
        snapshot = tracker.snapshot()
    }

    func start(_ client: GatewayClient) {
        guard task == nil else { return }
        let id = UUID()
        lease = id
        gateway = client
        tracker.start(runId: id.uuidString, nowMs: Self.now)
        client.addNetworkDiagnosticsObserver(id: id, pong: { [weak self] requestID in
            guard let self, self.lease == id else { return }
            _ = self.tracker.acceptPong(requestId: requestID, nowMs: Self.now)
        }, traffic: { [weak self] sent, received in
            guard let self, self.lease == id else { return }
            self.tracker.recordTraffic(sent: Int64(sent), received: Int64(received))
        })
        task = Task { [weak self, weak client] in
            while !Task.isCancelled {
                guard let self, let client, self.lease == id else { return }
                self.tracker.connection(key: client.networkDiagnosticsConnectionKey, connectionMethod: client.connectionMethod,
                    isConnected: client.state.isConnected, isSupported: client.networkDiagnosticsSupported, nowMs: Self.now)
                _ = self.tracker.tick(nowMs: Self.now)
                if let requestID = self.tracker.nextProbe(nowMs: Self.now) {
                    do { try await client.sendNetworkDiagnosticsProbe(requestID) }
                    catch is CancellationError {
                        if Task.isCancelled || self.lease != id { return }
                    }
                    catch {
                        guard self.lease == id else { return }
                        self.tracker.sendFailed(requestId: requestID, nowMs: Self.now)
                    }
                }
                guard self.lease == id else { return }
                self.snapshot = self.tracker.snapshot()
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        if let lease { gateway?.removeNetworkDiagnosticsObserver(id: lease) }
        lease = nil
        gateway = nil
        tracker.stop()
        snapshot = tracker.snapshot()
    }

    deinit { task?.cancel() }
    private static var now: Double { ProcessInfo.processInfo.systemUptime * 1000 }
}

@MainActor
struct GatewayNetworkDiagnosticsView: View {
    @ObservedObject var gateway: GatewayClient
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var monitor = GatewayNetworkDiagnosticsMonitor()
    @State private var selectedMetric = 0
    @State private var pageVisible = false
    private let titles = ["延迟", "丢包", "抖动", "流量"]
    private var snapshot: NetworkDiagnosticsSnapshot { monitor.snapshot }

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text(gateway.state.isConnected ? String(localized: String.LocalizationValue(gateway.connectionMethod)) : String(localized: "未连接"))
                            .appFont(.caption).foregroundStyle(.tint)
                        Spacer()
                        Text(!gateway.state.isConnected ? String(localized: "等待连接") : !gateway.networkDiagnosticsSupported
                             ? String(localized: "网关需更新") : String(localized: "每秒检测 · 最近 60 秒"))
                            .appFont(.caption2).foregroundStyle(.secondary)
                    }
                    if gateway.state.isConnected && gateway.connectionMethod == "WebRTC" {
                        GatewayConnectionPathView(paths: gateway.connectionPaths)
                    }
                    HStack {
                        metric("往返延迟", number(snapshot.roundTripMs, unit: " ms"))
                        metric("探测丢包率", number(snapshot.lossPercent, unit: "%"))
                    }
                    HStack {
                        metric("延迟抖动", number(snapshot.jitterMs, unit: " ms"))
                        metric("接收速率", rate(snapshot.receivedBytesPerSecond))
                    }
                    Picker("网络指标", selection: $selectedMetric) {
                        ForEach(titles.indices, id: \.self) { index in Text(LocalizedStringKey(titles[index])).tag(index) }
                    }
                    .pickerStyle(.segmented)
                    chart
                    HStack {
                        Text("60 秒前")
                        Spacer()
                        if selectedMetric == 3 {
                            Text("接收").foregroundStyle(Color.accentColor)
                            Text("发送").foregroundStyle(Color.orange)
                            Text(verbatim: "KiB/s")
                        } else {
                            Text(selectedMetric == 1 ? String(localized: "探测超时 · %") : String(localized: "毫秒 · ms"))
                        }
                        Spacer()
                        Text("现在")
                    }
                    .appFont(.caption2).foregroundStyle(.secondary)
                    Text(String(format: String(localized: "平均 %@ · P95 %@"), number(snapshot.averageRoundTripMs, unit: " ms"), number(snapshot.p95RoundTripMs, unit: " ms")))
                        .appFont(.caption2).foregroundStyle(.secondary)
                    Text(String(format: String(localized: "发送 %@ · 成功 %d / 超时 %d"), rate(snapshot.sentBytesPerSecond), Int(snapshot.successfulProbes), Int(snapshot.timedOutProbes)))
                        .appFont(.caption2).foregroundStyle(.secondary)
                    if gateway.state.isConnected && !gateway.networkDiagnosticsSupported {
                        Text("请更新网关以启用可关联的网络探测；旧网关不会被误判为丢包。")
                            .appFont(.caption2).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)
                .accessibilityIdentifier("network-diagnostics")
            } header: {
                Text("网络检测")
            }
        }
        .navigationTitle("网络检测")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { pageVisible = true; if scenePhase == .active { monitor.start(gateway) } }
        .onChange(of: scenePhase) { phase in
            if phase == .active && pageVisible { monitor.start(gateway) } else { monitor.stop() }
        }
        .onDisappear { pageVisible = false; monitor.stop() }
    }

    private func metric(_ title: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).appFont(.caption2).foregroundStyle(.secondary)
            Text(verbatim: value).appFont(.title3).monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var chart: some View {
        let samples = snapshot.samples
        let last = samples.last?.elapsedSeconds ?? 0
        return Chart {
            ForEach(Array(samples.enumerated()), id: \.offset) { _, sample in
                let value = metricValue(sample)
                if value >= 0 {
                    LineMark(x: .value("秒", sample.elapsedSeconds), y: .value("值", value),
                             series: .value("分段", selectedMetric == 1 || selectedMetric == 3 ? "rx" : "rx-\(sample.segment)"))
                        .foregroundStyle(Color.accentColor)
                    PointMark(x: .value("秒", sample.elapsedSeconds), y: .value("值", value))
                        .symbolSize(6).foregroundStyle(Color.accentColor)
                }
                if selectedMetric == 3 {
                    LineMark(x: .value("秒", sample.elapsedSeconds), y: .value("值", sample.sentBytesPerSecond / 1024),
                             series: .value("分段", "tx"))
                        .foregroundStyle(Color.orange)
                }
            }
        }
        .chartXScale(domain: (last - 60)...last)
        .chartYScale(domain: selectedMetric == 1 ? 0...100 : 0...maxChartValue)
        .chartXAxis(.hidden)
        .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) }
        .chartLegend(.hidden)
        .frame(height: 180)
        .overlay {
            if !samples.contains(where: { metricValue($0) >= 0 }) {
                Text("等待探测结果…").appFont(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityLabel("最近一分钟网络状态折线图")
        .accessibilityIdentifier("network-live-chart")
    }

    private var maxChartValue: Double {
        max(1, snapshot.samples.map { max(metricValue($0), selectedMetric == 3 ? $0.sentBytesPerSecond / 1024 : 0) }.max() ?? 1) * 1.15
    }
    private func metricValue(_ sample: NetworkDiagnosticsSample) -> Double {
        switch selectedMetric {
        case 1: return sample.lossPercent
        case 2: return sample.jitterMs
        case 3: return sample.receivedBytesPerSecond / 1024
        default: return sample.roundTripMs
        }
    }
    private func number(_ value: Double, unit: String) -> String {
        value < 0 || !value.isFinite ? "—" : value.formatted(.number.precision(.fractionLength(1))) + unit
    }
    private func rate(_ value: Double) -> String { number(value / 1024, unit: " KiB/s") }
}
