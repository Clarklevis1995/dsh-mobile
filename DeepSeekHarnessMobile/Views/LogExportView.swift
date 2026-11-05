import SwiftUI
import UIKit

private enum LogExportHour {
    static func start(of date: Date) -> Date {
        Calendar.current.dateInterval(of: .hour, for: date)!.start
    }

    static func end(of date: Date) -> Date {
        Calendar.current.dateInterval(of: .hour, for: date)!.end
    }
}

struct LogExportView: View {
    private enum Preset: String, CaseIterable {
        case hour = "1小时", today = "今天", day = "24小时", custom = "自定义"
    }
    private struct RangeKey: Hashable { let start: Date; let end: Date; let retry: Int }
    private struct Editor: Identifiable {
        let isStart: Bool
        let isDate: Bool
        let value: Date
        var id: String { "\(isStart)-\(isDate)" }
        var title: String {
            isStart
                ? (isDate ? String(localized: "开始日期") : String(localized: "开始时间"))
                : (isDate ? String(localized: "结束日期") : String(localized: "结束时间"))
        }
    }
    @State private var start = LogExportHour.start(of: Date())
    @State private var end = LogExportHour.end(of: Date())
    @State private var preset = Preset.hour
    @State private var summary: LocalAppLog.Summary?
    @State private var earliest: Date?
    @State private var loading = true
    @State private var busy = false
    @State private var exportingShare = false
    @State private var failed = false
    @State private var exportFailed = false
    @State private var retry = 0
    @State private var editor: Editor?
    @State private var presentation: LogExportPresentation?
    private let background = Color(.systemGroupedBackground)
    private let card = Color(.secondarySystemGroupedBackground)
    private var interval: DateInterval { DateInterval(start: start, end: max(start, end)) }
    private var valid: Bool { start < end && start <= Date() }
    private var canExport: Bool { valid && !loading && !busy && (summary?.fileCount ?? 0) > 0 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                section("时间范围") {
                    VStack(spacing: 16) {
                        Picker("时间范围", selection: Binding(get: { preset }, set: select)) {
                            ForEach(Preset.allCases, id: \.self) { value in
                                Text(LocalizedStringKey(value.rawValue)).tag(value)
                            }
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("log-range-picker")

                        Divider()
                        VStack(spacing: 8) {
                            timeRow("开始", value: start, isStart: true)
                            timeRow("结束", value: end, isStart: false)
                        }
                        Divider()
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 8) {
                                durationLabel
                                Spacer(minLength: 8)
                                timeZoneLabel
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                durationLabel
                                timeZoneLabel
                            }
                        }
                    }
                    .padding(16)
                    .background(card, in: RoundedRectangle(cornerRadius: 24))
                    // 导出期间只拦截编辑，避免原生控件短暂禁用、恢复时产生置灰闪烁。
                    .allowsHitTesting(!busy)
                }
                section("导出内容") {
                    HStack(spacing: 12) {
                        Image(systemName: "doc.text")
                            .appFont(.system(size: 20))
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("应用运行日志").appFont(SettingsTypography.item)
                            Text(summaryText).appFont(SettingsTypography.description).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        if !loading, !failed, let summary, summary.fileCount > 0 {
                            VStack(alignment: .trailing, spacing: 4) {
                                Text(ByteCountFormatter.string(fromByteCount: summary.bytes, countStyle: .file))
                                    .appFont(SettingsTypography.item.monospacedDigit())
                                Text("预计大小").appFont(SettingsTypography.description).foregroundStyle(.secondary)
                            }
                            .fixedSize(horizontal: true, vertical: false)
                        }
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                    .background(card, in: RoundedRectangle(cornerRadius: 24))
                    if failed {
                        Button("重试") { retry += 1 }
                    } else if !loading && summary?.fileCount == 0 {
                        Button("选择最近24小时") { select(.day) }
                    }
                }
                Text("日志按小时留存，仅导出所选时段\n旧日志会自动清理，可选日期以本机留存为准")
                    .appFont(SettingsTypography.description).foregroundStyle(.secondary)
            }
            .padding(24).frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
        }
        .background(background)
        .appFont(SettingsTypography.item)
        .navigationTitle("日志导出与分享")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { exportToolbar }
        .task(id: RangeKey(start: start, end: end, retry: retry)) { await refresh() }
        .sheet(item: $editor) { item in
            LogDateTimePicker(title: item.title, isDate: item.isDate, initial: item.value,
                              earliest: earliest ?? .distantPast, isStart: item.isStart) { value in
                if item.isStart { start = value } else { end = value }
                preset = .custom
            }
        }
        .sheet(item: $presentation) { item in
            LogExportSheet(urls: item.urls, share: item.share) { presentation = nil }
        }
        .alert("日志导出失败，请重试", isPresented: $exportFailed) { Button("好", role: .cancel) {} }
    }

    private var summaryText: String {
        if loading { return String(localized: "正在统计日志…") }
        if failed { return String(localized: "无法读取日志，请重试") }
        guard let summary, summary.fileCount > 0 else { return String(localized: "所选时间范围内没有日志") }
        return String(format: String(localized: "%lld 个日志文件"), Int64(summary.fileCount))
    }

    private var durationLabel: some View {
        Text(valid ? durationText : String(localized: "结束时间须晚于开始时间"))
            .appFont(SettingsTypography.description).foregroundStyle(valid ? Color.secondary : Color.red)
    }

    private var durationText: String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.hour]
        formatter.unitsStyle = .full
        let duration = formatter.string(from: max(0, end.timeIntervalSince(start))) ?? "0"
        return String(format: String(localized: "已选择 %@"), duration)
    }

    private var timeZoneLabel: some View {
        let seconds = TimeZone.current.secondsFromGMT(for: start)
        let offset = String(format: "UTC%@%02d:%02d", seconds < 0 ? "−" : "+",
                            abs(seconds) / 3600, abs(seconds) % 3600 / 60)
        return Text(verbatim: offset).appFont(SettingsTypography.description.monospaced())
            .foregroundStyle(.secondary)
    }

    private func section<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).appFont(SettingsTypography.section).foregroundStyle(.secondary)
            content()
        }
    }

    private func timeRow(_ title: LocalizedStringKey, value: Date, isStart: Bool) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                Text(title).appFont(SettingsTypography.item).fixedSize()
                Spacer(minLength: 8)
                dateButton(value, isStart: isStart)
                timeButton(value, isStart: isStart)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text(title).appFont(SettingsTypography.item).fixedSize()
                HStack(spacing: 8) {
                    dateButton(value, isStart: isStart)
                    Spacer(minLength: 0)
                    timeButton(value, isStart: isStart)
                }
            }
        }
        .buttonStyle(.plain).tint(DSHColor.ocean)
        .frame(minHeight: 56)
    }

    private func dateButton(_ value: Date, isStart: Bool) -> some View {
        Button {
            guard !busy else { return }
            editor = Editor(isStart: isStart, isDate: true, value: value)
        } label: {
            Text(value, format: .dateTime.year().month().day())
                .appFont(SettingsTypography.item).foregroundStyle(DSHColor.ocean)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 8)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityIdentifier(isStart ? "log-start-date" : "log-end-date")
        .accessibilityLabel(Text(isStart ? "开始日期" : "结束日期"))
        .accessibilityValue(Text(value, format: .dateTime.year().month().day()))
    }

    private func timeString(_ value: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .autoupdatingCurrent
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: value)
    }

    private func timeButton(_ value: Date, isStart: Bool) -> some View {
        Button {
            guard !busy else { return }
            editor = Editor(isStart: isStart, isDate: false, value: value)
        } label: {
            Text(timeString(value))
                .appFont(SettingsTypography.item).monospacedDigit().foregroundStyle(DSHColor.ocean)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 8)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityIdentifier(isStart ? "log-start-time" : "log-end-time")
        .accessibilityLabel(Text(isStart ? "开始时间" : "结束时间"))
        .accessibilityValue(Text(timeString(value)))
    }

    @ToolbarContentBuilder private var exportToolbar: some ToolbarContent {
        if #available(iOS 26.0, *) {
            ToolbarItem(placement: .topBarTrailing) {
                exportToolbarButton(share: false)
            }
            .sharedBackgroundVisibility(.hidden)
            ToolbarItem(placement: .topBarTrailing) {
                exportToolbarButton(share: true)
            }
            .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                exportToolbarButton(share: false)
                exportToolbarButton(share: true)
            }
        }
    }

    @ViewBuilder private func exportToolbarButton(share: Bool) -> some View {
        let button = Button { prepare(share: share) } label: {
            Group {
                if busy && exportingShare == share {
                    ProgressView()
                } else {
                    Image(share ? "LogShare" : "LogDownload")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 20, height: 20)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!canExport)
        .accessibilityLabel(Text(share ? "分享日志" : "导出到文件"))
        .accessibilityIdentifier(share ? "log-share" : "log-save")
        if #available(iOS 26.0, *) {
            button.glassEffect(.regular.interactive(), in: .circle)
        } else {
            button.glassSurface(radius: 22, shadowOpacity: 0)
        }
    }

    private func select(_ value: Preset) {
        guard !busy else { return }
        preset = value
        guard value != .custom else { return }
        let now = Date()
        end = LogExportHour.end(of: now)
        switch value {
        case .hour: start = end.addingTimeInterval(-3600)
        case .day: start = end.addingTimeInterval(-86400)
        case .today: start = Calendar.current.startOfDay(for: now)
        case .custom: break
        }
    }

    @MainActor private func refresh() async {
        loading = true; failed = false; summary = nil
        do {
            try await Task.sleep(for: .milliseconds(180))
            let result = try await LocalAppLog.shared.summary(in: interval)
            try Task.checkCancellation()
            summary = result; earliest = result.earliest; loading = false
        } catch is CancellationError {} catch { failed = true; loading = false }
    }

    private func prepare(share: Bool) {
        guard canExport else { return }
        exportingShare = share
        busy = true
        let selectedInterval = interval
        Task { @MainActor in
            defer { busy = false }
            do {
                let url = try await LocalAppLog.shared.exportArchive(in: selectedInterval)
                presentation = LogExportPresentation(urls: [url], share: share)
            } catch { exportFailed = true; await refresh() }
        }
    }
}

private struct LogDateTimePicker: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let isDate: Bool
    let earliest: Date
    let isStart: Bool
    let confirm: (Date) -> Void
    @State private var draft: Date
    init(title: String, isDate: Bool, initial: Date, earliest: Date, isStart: Bool, confirm: @escaping (Date) -> Void) {
        self.title = title; self.isDate = isDate; self.earliest = earliest; self.isStart = isStart; self.confirm = confirm
        _draft = State(initialValue: initial)
    }
    private var latest: Date {
        isStart ? LogExportHour.start(of: Date()) : LogExportHour.end(of: Date())
    }
    private var hours: [Date] {
        let day = Calendar.current.dateInterval(of: .day, for: draft)!
        return stride(from: day.start.timeIntervalSince1970, to: day.end.timeIntervalSince1970, by: 3600)
            .map { Date(timeIntervalSince1970: $0) }
            .filter { $0 <= latest }
    }
    private var dateSelection: Binding<Date> {
        Binding(get: { draft }, set: { draft = min(LogExportHour.start(of: $0), latest) })
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(draft, format: .dateTime.year().month().day().weekday())
                        .appFont(SettingsTypography.description).foregroundStyle(.secondary)
                    if isDate {
                        DatePicker("日期", selection: dateSelection,
                                   in: Calendar.current.startOfDay(for: earliest)...latest, displayedComponents: .date)
                            .datePickerStyle(.graphical).labelsHidden()
                            .accessibilityIdentifier("log-native-calendar")
                    } else {
                        Picker("时间", selection: $draft) {
                            ForEach(hours, id: \.self) { hour in
                                Text(String(format: "%02d:00", Calendar.current.component(.hour, from: hour)))
                                    .tag(hour)
                            }
                        }
                        .pickerStyle(.wheel).labelsHidden()
                        .accessibilityIdentifier("log-native-hour-picker")
                    }
                    Text(isDate ? "灰色日期不在可导出的日志范围内" : "上下滑动选择整点")
                        .appFont(SettingsTypography.description).foregroundStyle(.secondary)
                }
                .padding(16)
            }
            .appFont(SettingsTypography.item)
            .navigationTitle(LocalizedStringKey(title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { confirm(LogExportHour.start(of: draft)); dismiss() }.disabled(draft > latest)
                }
            }
        }
        .presentationDetents([.height(isDate ? 560 : 408), .large])
        .presentationDragIndicator(.visible)
        .logPickerAppearance()
    }
}

private extension View {
    @ViewBuilder func logPickerAppearance() -> some View {
        if #available(iOS 16.4, *) {
            self.presentationBackground(Color(.secondarySystemGroupedBackground)).presentationCornerRadius(30)
        } else {
            self
        }
    }
}
