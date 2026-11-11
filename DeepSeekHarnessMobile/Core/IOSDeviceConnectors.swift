import SwiftUI
import AlarmKit
import EventKit
import CoreLocation
import MediaPlayer
import MusicKit
import PhotosUI
import HealthKit
import ImageIO
import UIKit
import DeepSeekHarnessShared

struct PhoneConnectorModule: Identifiable {
    let id: String
    let title: String
}

struct PhoneConnectorRegistrationModule: Identifiable, Equatable {
    let id: String
    let enabled: Bool
    let available: Bool
    let tools: [String]
}

enum PhoneConnectorRegistrationState: Equatable {
    case disconnected, unsupported, registering, registered, unverified, failed, timedOut, notRegistered
}

/// 每个主机独立保存开关。连接器开启即持续授权当前主机，网关租约、主机权限、本机开关及系统权限共同约束执行。
@MainActor
final class IOSDeviceConnectors: NSObject, ObservableObject, CLLocationManagerDelegate, PHPickerViewControllerDelegate {
    static let modules = [
        PhoneConnectorModule(id: "calendar", title: "日历"),
        PhoneConnectorModule(id: "reminders", title: "提醒事项"),
        PhoneConnectorModule(id: "location", title: "位置"),
        PhoneConnectorModule(id: "photos", title: "照片"),
        PhoneConnectorModule(id: "music", title: "音乐"),
        PhoneConnectorModule(id: "health", title: "健康"),
        PhoneConnectorModule(id: "alarms", title: "闹钟")
    ]
    @Published private(set) var enabled: [String: Bool]
    @Published private(set) var permissions: [String: Bool] = [:]
    @Published private(set) var authorizing = false
    @Published private(set) var syncing = false
    @Published private(set) var registrationState: PhoneConnectorRegistrationState = .disconnected
    @Published private(set) var registeredModules: [PhoneConnectorRegistrationModule] = []
    @Published private(set) var registrationConfirmedAt: Date?
    @Published private(set) var checkingRegistration = false
    @Published var notice: String?
    private let key: String
    private let send: (String) -> Void
    private let shared = SharedDeviceConnectors()
    private let events = EKEventStore()
    private let writes: PhoneConnectorWriteLedger
    private let markers: PhoneMapMarkerStore
    private let health = HKHealthStore()
    private let location = CLLocationManager()
    private var activeLocation: CLLocationManager?
    private var preciseAuthorizationDeclined = false
    private var locationResult: CheckedContinuation<CLLocation, Error>?
    private var locationAuthorization: CheckedContinuation<Bool, Never>?
    private var lease: String?
    private var registration: String?
    private var registrationChanges: [String: Bool] = [:]
    private var queuedPermissionChanges: [String: Bool] = [:]
    private var registrationTimeout: Task<Void, Never>?
    private let registrationWait: Duration
    private let statusWait: Duration
    private var statusRequest: String?
    private var statusTimeout: Task<Void, Never>?
    private var statusQuerySupported = false
    private var permissionSyncSupported: Bool?
    private var revision = 0
    private var supported = false
    private var seen: Set<String> = []
    private var seenOrder: [String] = []
    private var pending: Invocation?
    private weak var picker: PHPickerViewController?
    private var photoResult: CheckedContinuation<[String: Any], Error>?
    private var nativeCancel: (() -> Void)?
    private var backgroundObserver: NSObjectProtocol?
    private struct Failure: Error { let code: String }
    private final class Invocation {
        let id: String
        let lease: String
        let module: String
        var task: Task<Void, Never>?
        var timeout: Task<Void, Never>?
        init(id: String, lease: String, module: String) { self.id = id; self.lease = lease; self.module = module }
    }

    init(hostID: String, registrationWait: Duration = .seconds(10), statusWait: Duration = .seconds(5), send: @escaping (String) -> Void) {
        key = "phone-connectors.v1.\(hostID)"
        writes = PhoneConnectorWriteLedger(hostID: hostID)
        markers = PhoneMapMarkerStore(hostID: hostID)
        enabled = UserDefaults.standard.dictionary(forKey: key) as? [String: Bool] ?? [:]
        self.send = send
        self.registrationWait = registrationWait
        self.statusWait = statusWait
        super.init()
        location.delegate = self
        backgroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.cancelPending(code: "user_action_required") }
        }
    }
    deinit { if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) } }

    func isAvailable(_ id: String) -> Bool {
        if id == "alarms" { if #available(iOS 26.0, *) { return true }; return false }
        return id == "health" ? HKHealthStore.isHealthDataAvailable() : Self.modules.contains { $0.id == id }
    }
    private func tools(for module: String) -> [String] {
        switch module {
        case "calendar": return ["calendar.busy", "calendar.create"]
        case "reminders": return ["reminders.list", "reminders.create"]
        case "alarms": return isAvailable(module) ? ["alarms.create", "alarms.list", "alarms.cancel"] : []
        case "music": return ["music.search", "music.state", "music.play", "music.pause", "music.seek", "music.library", "music.play-library", "music.catalog-search"]
        case "health": return ["health.daily-summary"]
        case "location": return ["location.current"] + IOSMapConnector.tools
        case "photos": return ["photos.select"]
        default: return []
        }
    }
    func isOn(_ id: String) -> Bool {
        guard enabled[id] == true else { return false }
        return queuedPermissionChanges[id] == true || registrationChanges[id] == true || permissionSyncSupported == nil || permissions[id] == true
    }
    func setEnabled(_ id: String, _ value: Bool) async {
        guard Self.modules.contains(where: { $0.id == id }), isAvailable(id), !authorizing else { return }
        if value {
            if id == "location" { preciseAuthorizationDeclined = false }
            authorizing = true
            defer { authorizing = false }
            do {
                guard try await authorize(id) else { notice = "系统权限未允许，请在设置中检查。"; return }
            } catch { notice = "无法取得系统权限，请在设置中检查。"; return }
        }
        enabled[id] = value
        queuedPermissionChanges[id] = value
        notice = nil
        UserDefaults.standard.set(enabled, forKey: key)
        cancelPending(code: "tool_disabled")
        register()
    }
    func connected(_ capabilities: [String]) {
        disconnected()
        supported = capabilities.contains("device-tools-v1")
        if !supported { registrationState = .unsupported }
        register()
    }
    func disconnected(discardPendingChanges: Bool = false) {
        cancelPending(code: "device_disconnected")
        if syncing { notice = "连接中断，权限同步尚未确认，请重连后检查开关。" }
        registrationTimeout?.cancel(); registrationTimeout = nil
        registrationChanges = [:]; syncing = false; permissionSyncSupported = nil
        if discardPendingChanges { queuedPermissionChanges = [:] }
        supported = false; lease = nil; registration = nil; permissions = [:]; seen.removeAll(); seenOrder.removeAll()
        clearRegistrationSnapshot()
        cancelStatusCheck()
        statusQuerySupported = false
        registrationState = .disconnected
    }
    var canRetryRegistration: Bool { supported && registration == nil && !authorizing }
    var canCheckRegistration: Bool { supported && statusQuerySupported && registration == nil }
    var registeredToolCount: Int { registeredModules.reduce(0) { $0 + $1.tools.count } }
    func isRegisteredModuleAllowed(_ module: PhoneConnectorRegistrationModule) -> Bool {
        registrationState == .registered && module.enabled && module.available && enabled[module.id] == true && permissions[module.id] == true
    }
    func retryRegistration() {
        guard canRetryRegistration else { return }
        guard pending == nil else { notice = "连接器正在执行任务，请完成后重试。"; return }
        register()
    }
    /// 只读查询当前控制连接的登记，不更换租约、不取消调用、不重新授权。
    func refreshRegistrationStatus() {
        guard canCheckRegistration, statusRequest == nil, UIApplication.shared.applicationState == .active else { return }
        let request = UUID().uuidString
        statusRequest = request; checkingRegistration = true
        emit(["type": "device-tools-status", "version": 1, "requestId": request])
        statusTimeout = Task { [weak self] in
            guard let wait = self?.statusWait else { return }
            do { try await Task.sleep(for: wait) } catch { return }
            guard let self, self.statusRequest == request else { return }
            self.cancelStatusCheck()
            self.clearRegistrationSnapshot()
            self.registrationState = .unverified
        }
    }
    private func cancelStatusCheck() {
        statusTimeout?.cancel(); statusTimeout = nil
        statusRequest = nil; checkingRegistration = false
    }
    private func clearRegistrationSnapshot() {
        registeredModules = []; registrationConfirmedAt = nil
    }
    private func acceptRegistrationSnapshot(_ object: [String: Any]) -> Bool {
        guard let rows = object["modules"] as? [[String: Any]], rows.count <= Self.modules.count else { return false }
        var accepted: [PhoneConnectorRegistrationModule] = []
        for row in rows {
            guard let id = row["id"] as? String, Self.modules.contains(where: { $0.id == id }),
                  !accepted.contains(where: { $0.id == id }), let enabled = row["enabled"] as? Bool,
                  let available = row["available"] as? Bool, let tools = row["tools"] as? [String],
                  Set(tools).count == tools.count, Set(tools).isSubset(of: Set(self.tools(for: id))) else { return false }
            accepted.append(.init(id: id, enabled: enabled, available: available, tools: tools))
        }
        registeredModules = accepted; registrationConfirmedAt = .now
        return true
    }
    func cancelPending(code: String = "cancelled") {
        guard let call = pending else { return }
        pending = nil
        call.timeout?.cancel(); call.task?.cancel()
        let photo = photoResult; photoResult = nil
        photo?.resume(throwing: CancellationError())
        picker?.dismiss(animated: false); picker = nil
        let gps = locationResult; locationResult = nil
        gps?.resume(throwing: CancellationError()); activeLocation?.stopUpdatingLocation(); activeLocation?.delegate = nil; activeLocation = nil
        nativeCancel?(); nativeCancel = nil
        result(call.id, call.lease, error: code)
    }
    private func emit(_ value: [String: Any]) {
        guard let bytes = try? JSONSerialization.data(withJSONObject: value) else { return }
        send(String(decoding: bytes, as: UTF8.self))
    }
    private func register() {
        guard supported else { return }
        cancelPending(code: "stale_lease")
        registrationTimeout?.cancel()
        cancelStatusCheck()
        clearRegistrationSnapshot()
        registrationState = .registering
        statusQuerySupported = false
        lease = nil; revision += 1
        let request = UUID().uuidString; registration = request
        let changes = queuedPermissionChanges
        // 写入网络前消费本次意图；断线后不重放已发送的开启操作。
        queuedPermissionChanges = [:]; registrationChanges = changes; syncing = !changes.isEmpty
        var frame: [String: Any] = ["type": "device-tools-register", "version": 1, "requestId": request,
              "capabilityRevision": revision, "modules": Self.modules.map {
            ["id": $0.id, "enabled": enabled[$0.id] == true, "available": isAvailable($0.id), "tools": tools(for: $0.id)] as [String: Any]
        }]
        if !changes.isEmpty { frame["permissionChanges"] = changes }
        emit(frame)
        registrationTimeout = Task { [weak self] in
            guard let wait = self?.registrationWait else { return }
            do { try await Task.sleep(for: wait) } catch { return }
            guard let self, self.registration == request else { return }
            self.registration = nil; self.registrationChanges = [:]; self.syncing = false
            self.notice = "连接器权限同步超时，请检查连接后重试。"
            self.registrationState = .timedOut
            if !changes.isEmpty { self.register() } // 只重新读取状态，不重发开启补丁。
        }
    }
    func receive(_ json: String) {
        guard supported, let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let kind = object["kind"] as? String else { return }
        if kind == "error", object["requestType"] as? String == "device-tools-status",
           let request = object["requestId"] as? String, request == statusRequest {
            cancelStatusCheck(); clearRegistrationSnapshot(); registrationState = .unverified
            return
        }
        if kind == "error", object["requestType"] as? String == "device-tools-register",
           let request = object["requestId"] as? String, request == registration {
            let attemptedSync = !registrationChanges.isEmpty
            registrationTimeout?.cancel(); registrationTimeout = nil
            registration = nil; registrationChanges = [:]; syncing = false
            clearRegistrationSnapshot(); registrationState = .failed
            if ["invalid_request", "unsupported-version", "unsupported_version"].contains(object["code"] as? String ?? "") {
                notice = "连接器注册失败，可能与网关版本不兼容。请更新并重启主机端 DSH，然后重新连接。"
            } else {
                notice = "主机权限同步失败，请检查连接后重试。"
            }
            if attemptedSync { register() }
            return
        }
        guard object["version"] as? Int == 1 else { return }
        if kind == "device-tools-registered" {
            guard object["requestId"] as? String == registration, object["capabilityRevision"] as? Int == revision,
                  let owner = object["connectionLeaseId"] as? String, !owner.isEmpty else { return }
            let attemptedSync = !registrationChanges.isEmpty
            registrationTimeout?.cancel(); registrationTimeout = nil
            lease = owner; registration = nil; registrationChanges = [:]; syncing = false
            permissions = object["permissions"] as? [String: Bool] ?? [:]
            permissionSyncSupported = object["permissionSync"] as? Bool == true
            statusQuerySupported = object["statusQuery"] as? Bool == true
            registrationState = acceptRegistrationSnapshot(object) ? .registered : .unverified
            if registrationState != .registered { clearRegistrationSnapshot() }
            notice = attemptedSync && permissionSyncSupported != true ? "当前网关不支持手机同步权限，请更新网关。" : nil
            return
        }
        if kind == "device-tools-status" {
            guard let request = object["requestId"] as? String, request == statusRequest else { return }
            if object["status"] as? String == "not-registered" {
                cancelStatusCheck(); cancelPending(code: "stale_lease"); lease = nil
                clearRegistrationSnapshot(); registrationState = .notRegistered
                return
            }
            guard object["status"] as? String == "registered", object["connectionLeaseId"] as? String == lease,
                  lease != nil, object["capabilityRevision"] as? Int == revision else { return }
            cancelStatusCheck()
            permissions = object["permissions"] as? [String: Bool] ?? [:]
            registrationState = acceptRegistrationSnapshot(object) ? .registered : .unverified
            if registrationState != .registered { clearRegistrationSnapshot() }
            if let call = pending, permissions[call.module] != true { cancelPending(code: "tool_disabled") }
            return
        }
        guard let current = lease, object["connectionLeaseId"] as? String == current else { return }
        if kind == "device-tools-permissions" {
            permissions = object["permissions"] as? [String: Bool] ?? [:]
            if let call = pending, permissions[call.module] != true { cancelPending(code: "tool_disabled") }
            return
        }
        guard let id = object["invocationId"] as? String else { return }
        if kind == "device-tool-cancel" { if pending?.id == id { cancelPending() }; return }
        guard kind == "device-tool-invoke", !seen.contains(id) else { return }
        seen.insert(id); seenOrder.append(id)
        if seenOrder.count > 1024 { seen.remove(seenOrder.removeFirst()) }
        let validation = shared.validateInvocationJson(json: json)
        guard validation.isEmpty else { result(id, current, error: validation); return }
        let tool = object["tool"] as? String ?? ""
        guard let module = shared.toolModule(tool: tool), enabled[module] == true, permissions[module] == true else {
            result(id, current, error: "tool_disabled"); return
        }
        guard isAvailable(module) else { result(id, current, error: "tool_unsupported"); return }
        guard UIApplication.shared.applicationState == .active else { result(id, current, error: "user_action_required"); return }
        guard pending == nil else { result(id, current, error: "busy"); return }
        let args = object["arguments"] as? [String: Any] ?? [:]
        let call = Invocation(id: id, lease: current, module: module)
        pending = call
        call.task = Task { [weak self, weak call] in
            guard let self, let call else { return }
            defer {
                if self.pending === call { self.pending = nil; call.timeout?.cancel(); self.nativeCancel = nil }
            }
            do {
                try Task.checkCancellation()
                guard self.pending === call else { return }
                if ["music.play", "music.pause", "music.seek", "music.play-library", "maps.open"].contains(tool) {
                    guard let session = object["sessionId"] as? String, let toolCall = object["toolCallId"] as? String else { throw Failure(code: "invalid_request") }
                    let identity = "\(session.count):\(session):\(toolCall)"
                    let ledgerKey = self.key + (tool == "maps.open" ? ".map-calls" : ".music-calls")
                    var ledger = UserDefaults.standard.stringArray(forKey: ledgerKey) ?? []
                    guard !ledger.contains(identity) else { throw Failure(code: "already_attempted") }
                    ledger.append(identity)
                    UserDefaults.standard.set(Array(ledger.suffix(512)), forKey: ledgerKey)
                }
                let data: [String: Any]
                if PhoneConnectorWriteInput.tools.contains(tool) {
                    try PhoneConnectorWriteInput.validate(tool, args)
                    if module == "calendar", !self.eventAccess(.event) { throw Failure(code: "permission_denied") }
                    if module == "reminders", !self.eventAccess(.reminder) { throw Failure(code: "permission_denied") }
                    if module == "alarms" {
                        guard #available(iOS 26.0, *), AlarmManager.shared.authorizationState == .authorized else { throw Failure(code: "permission_denied") }
                    }
                    guard let session = object["sessionId"] as? String, !session.isEmpty else { throw Failure(code: "invalid_request") }
                    data = try await self.writes.run(tool: tool, sessionID: session, arguments: args) {
                        try await self.execute(tool, args, sessionID: session)
                    }
                } else { data = try await self.execute(tool, args, sessionID: object["sessionId"] as? String ?? "") }
                try Task.checkCancellation()
                guard self.pending === call else { return }
                let bytes = try JSONSerialization.data(withJSONObject: data)
                guard bytes.count <= 128 * 1024 else { throw Failure(code: "result_too_large") }
                try Task.checkCancellation()
                guard self.pending === call, self.lease == current, self.enabled[module] == true,
                      self.permissions[module] == true, UIApplication.shared.applicationState == .active else { return }
                self.emit(["type": "device-tool-result", "version": 1, "invocationId": id,
                           "connectionLeaseId": current, "status": "success", "result": data])
                if tool == "maps.open" {
                    Task { @MainActor [weak self] in
                        do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
                        guard let self, self.lease == current, self.enabled["location"] == true,
                              self.permissions["location"] == true, UIApplication.shared.applicationState == .active else { return }
                        if !IOSMapConnector.open(args) { self.notice = "无法打开苹果地图，请稍后重试。" }
                    }
                }
            } catch is CancellationError { }
            catch { if self.pending === call { self.result(id, current, error: (error as? Failure)?.code ?? (error as? PhoneConnectorFailure)?.code ?? "data_unavailable") } }
        }
        let timeout = min(max(object["timeoutMs"] as? Int ?? 120000, 1000), 120000)
        call.timeout = Task { [weak self, weak call] in
            do { try await Task.sleep(for: .milliseconds(timeout)) } catch { return }
            guard let self, let call, self.pending === call else { return }
            self.cancelPending(code: "invocation_timeout")
        }
    }
    private func result(_ id: String, _ owner: String, error: String) {
        guard lease == owner else { return }
        emit(["type": "device-tool-result", "version": 1, "invocationId": id,
              "connectionLeaseId": owner, "status": "error", "error": ["code": error]])
    }
    private func authorize(_ module: String) async throws -> Bool {
        switch module {
        case "calendar", "reminders":
            if #available(iOS 17.0, *) {
                return try await (module == "calendar" ? events.requestFullAccessToEvents() : events.requestFullAccessToReminders())
            }
            return try await events.requestAccess(to: module == "calendar" ? .event : .reminder)
        case "location":
            if location.authorizationStatus == .notDetermined {
                return await withCheckedContinuation { continuation in
                    locationAuthorization = continuation; location.requestWhenInUseAuthorization()
                }
            }
            return [.authorizedAlways, .authorizedWhenInUse].contains(location.authorizationStatus)
        case "music":
            return await MusicAuthorization.request() == .authorized
        case "health":
            try await health.requestAuthorization(toShare: [], read: [HKObjectType.quantityType(forIdentifier: .stepCount)!, HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!])
            // HealthKit 不公开读取授权状态；完成弹窗不代表数据可读。
            return true
        case "alarms":
            if #available(iOS 26.0, *) { return try await AlarmManager.shared.requestAuthorization() == .authorized }
            return false
        case "photos": return true // 系统选择器无需相册全量读取权限。
        default: return false
        }
    }
    private func presenter() -> UIViewController? {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var presenter = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return nil }
        while let next = presenter.presentedViewController { presenter = next }
        return presenter is UIAlertController ? nil : presenter
    }
    private func range(_ args: [String: Any]) throws -> (Date, Date, TimeZone) {
        let formatter = ISO8601DateFormatter()
        func parse(_ value: Any?) -> Date? {
            guard let value = value as? String else { return nil }
            formatter.formatOptions = [.withInternetDateTime]
            if let date = formatter.date(from: value) { return date }
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return formatter.date(from: value)
        }
        guard let start = parse(args["start"]), let end = parse(args["end"]), end > start,
              end.timeIntervalSince(start) <= 8 * 86400, let name = args["timeZone"] as? String,
              let zone = TimeZone(identifier: name) else { throw Failure(code: "scope_rejected") }
        return (start, end, zone)
    }
    private func execute(_ tool: String, _ args: [String: Any], sessionID: String = "") async throws -> [String: Any] {
        let formatter = ISO8601DateFormatter()
        switch tool {
        case "calendar.create":
            guard eventAccess(.event) else { throw Failure(code: "permission_denied") }
            guard let calendar = events.defaultCalendarForNewEvents, calendar.allowsContentModifications else { throw Failure(code: "calendar_not_writable") }
            let event = EKEvent(eventStore: events)
            event.calendar = calendar; event.title = args["title"] as? String
            event.startDate = PhoneConnectorWriteInput.date(args["start"]); event.endDate = PhoneConnectorWriteInput.date(args["end"])
            event.timeZone = TimeZone(identifier: args["timeZone"] as! String)
            event.isAllDay = args["allDay"] as? Bool ?? false
            event.notes = args["notes"] as? String; event.location = args["location"] as? String
            if let minutes = args["alertMinutesBefore"] as? Int { event.addAlarm(EKAlarm(relativeOffset: -Double(minutes) * 60)) }
            try Task.checkCancellation()
            try events.save(event, span: .thisEvent, commit: true)
            return ["id": event.calendarItemIdentifier, "title": event.title ?? "", "start": formatter.string(from: event.startDate), "end": formatter.string(from: event.endDate),
                    "timeZone": event.timeZone?.identifier ?? args["timeZone"]!, "allDay": event.isAllDay, "operationId": args["operationId"]!, "status": "created"]
        case "reminders.create":
            guard eventAccess(.reminder) else { throw Failure(code: "permission_denied") }
            guard let calendar = events.defaultCalendarForNewReminders(), calendar.allowsContentModifications else { throw Failure(code: "calendar_not_writable") }
            let reminder = EKReminder(eventStore: events)
            reminder.calendar = calendar; reminder.title = args["title"] as? String; reminder.notes = args["notes"] as? String
            reminder.priority = args["priority"] as? Int ?? 0
            let due = PhoneConnectorWriteInput.date(args["dueDate"])
            let notify = args["notify"] as? Bool ?? (due != nil)
            if let due {
                var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: args["timeZone"] as! String)!
                reminder.dueDateComponents = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second, .timeZone, .calendar], from: due)
                if notify { reminder.addAlarm(EKAlarm(absoluteDate: due)) }
            }
            try Task.checkCancellation()
            try events.save(reminder, commit: true)
            return ["id": reminder.calendarItemIdentifier, "title": reminder.title ?? "", "dueDate": due.map { formatter.string(from: $0) } as Any? ?? NSNull(),
                    "timeZone": args["timeZone"]!, "notify": notify, "priority": reminder.priority, "operationId": args["operationId"]!, "status": "created"]
        case "alarms.create", "alarms.list", "alarms.cancel":
            guard #available(iOS 26.0, *) else { throw Failure(code: "tool_unsupported") }
            return try await executeAlarm(tool, args, sessionID: sessionID)
        case "calendar.busy":
            let (start, end, zone) = try range(args)
            guard eventAccess(.event) else { throw Failure(code: "permission_denied") }
            let found = events.events(matching: events.predicateForEvents(withStart: start, end: end, calendars: nil))
            guard found.count <= 2000 else { throw Failure(code: "result_too_large") }
            let sorted = found.filter { $0.availability != .free && $0.status != .canceled }.map {
                (max(start, $0.startDate), min(end, $0.endDate))
            }.filter { $0.0 < $0.1 }.sorted { $0.0 < $1.0 }
            var intervals: [(Date, Date)] = []
            for row in sorted {
                if let last = intervals.last, row.0 <= last.1 { intervals[intervals.count - 1].1 = max(last.1, row.1) }
                else { intervals.append(row) }
            }
            return ["busyIntervals": intervals.map { ["start": formatter.string(from: $0.0), "end": formatter.string(from: $0.1)] }, "timeZone": zone.identifier, "truncated": false, "coverage": "authorized-calendars", "collectedAt": formatter.string(from: Date())]
        case "reminders.list":
            let (start, end, zone) = try range(args)
            guard eventAccess(.reminder) else { throw Failure(code: "permission_denied") }
            let predicate = events.predicateForIncompleteReminders(withDueDateStarting: start, ending: end, calendars: nil)
            let found: [EKReminder] = await withCheckedContinuation { continuation in
                var completed = false
                let token = events.fetchReminders(matching: predicate) { found in
                    Task { @MainActor in
                        guard !completed else { return }; completed = true
                        continuation.resume(returning: found ?? [])
                    }
                }
                nativeCancel = { [events] in
                    events.cancelFetchRequest(token)
                    if !completed { completed = true; continuation.resume(returning: []) }
                }
            }
            try Task.checkCancellation()
            return ["items": found.prefix(100).map { ["title": String(($0.title ?? "").prefix(500)), "completed": $0.isCompleted, "dueDate": $0.dueDateComponents.flatMap { Calendar.current.date(from: $0) }.map { formatter.string(from: $0) } as Any? ?? NSNull()] }, "truncated": found.count > 100, "timeZone": zone.identifier, "collectedAt": formatter.string(from: Date())]
        case "location.current", "location.precise":
            let precise = tool == "location.precise"
            if precise { try IOSMapConnector.validate(tool, args) }
            else { guard (args["requestedPrecision"] as? String ?? "coarse") == "coarse" else { throw Failure(code: "scope_rejected") } }
            guard [.authorizedAlways, .authorizedWhenInUse].contains(location.authorizationStatus), locationResult == nil else { throw Failure(code: "permission_denied") }
            if precise && location.accuracyAuthorization != .fullAccuracy {
                guard !preciseAuthorizationDeclined else { throw Failure(code: "precise_location_not_authorized") }
                do { try await location.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "MapsPrecision") }
                catch { try Task.checkCancellation(); preciseAuthorizationDeclined = true; throw Failure(code: "precise_location_not_authorized") }
                try Task.checkCancellation()
                guard location.accuracyAuthorization == .fullAccuracy else { preciseAuthorizationDeclined = true; throw Failure(code: "precise_location_not_authorized") }
            }
            try Task.checkCancellation()
            let sample: CLLocation = try await withCheckedThrowingContinuation { continuation in
                locationResult = continuation
                let manager = CLLocationManager(); manager.delegate = self
                manager.desiredAccuracy = precise ? kCLLocationAccuracyNearestTenMeters : kCLLocationAccuracyKilometer
                activeLocation = manager; manager.requestLocation()
            }
            try Task.checkCancellation()
            if precise && location.accuracyAuthorization != .fullAccuracy { throw Failure(code: "precise_location_not_authorized") }
            return try IOSMapConnector.locationResult(sample, precise: precise, maxAge: min(max(args["maxAgeSeconds"] as? Double ?? 60, 1), 300))
        case "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete":
            return try markers.execute(tool, args, sessionID: sessionID)
        case "maps.search", "maps.geocode", "maps.reverse-geocode", "maps.directions", "maps.open":
            return try await IOSMapConnector.execute(tool, args)
        case "photos.select":
            guard let presenter = presenter() else { throw Failure(code: "user_action_required") }
            return try await withCheckedThrowingContinuation { continuation in
                photoResult = continuation
                var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 1
                let picker = PHPickerViewController(configuration: config); picker.delegate = self
                self.picker = picker; presenter.present(picker, animated: true)
            }
        case "health.daily-summary": return try await healthSummary(args)
        case "music.library":
            return try await IOSMusicLibraryConnector().browse(args)
        case "music.catalog-search":
            return try await IOSMusicLibraryConnector().searchCatalog(args)
        case "music.play-library":
            do { try await IOSMusicLibraryConnector.play(args) }
            catch let error as PhoneConnectorFailure { throw error }
            catch is CancellationError { throw CancellationError() }
            catch { throw Failure(code: "music_playback_unavailable") }
            let player = MPMusicPlayerController.systemMusicPlayer
            return ["playbackState": player.playbackState.rawValue, "playbackSeconds": player.currentPlaybackTime.isFinite ? player.currentPlaybackTime : 0, "requested": tool]
        case "music.state", "music.search", "music.play", "music.pause", "music.seek":
            guard MPMediaLibrary.authorizationStatus() == .authorized else { throw Failure(code: "permission_denied") }
            let player = MPMusicPlayerController.systemMusicPlayer
            if tool == "music.search" {
                guard let query = args["query"] as? String, !query.isEmpty, query.count <= 100 else { throw Failure(code: "invalid_request") }
                let collection = MPMediaQuery.songs()
                collection.addFilterPredicate(MPMediaPropertyPredicate(value: query, forProperty: MPMediaItemPropertyTitle, comparisonType: .contains))
                guard let items = collection.items else { throw Failure(code: "music_library_unavailable") }
                return ["items": items.prefix(20).map { ["id": String($0.persistentID), "title": String(($0.title ?? "").prefix(500)), "artist": String(($0.artist ?? "").prefix(500))] }, "source": "authorized-media-library"]
            }
            if tool == "music.play" {
                if let id = args["itemId"] as? String, let number = UInt64(id) {
                    let query = MPMediaQuery.songs(); query.addFilterPredicate(MPMediaPropertyPredicate(value: NSNumber(value: number), forProperty: MPMediaItemPropertyPersistentID))
                    guard let item = query.items?.first else { throw Failure(code: "data_unavailable") }
                    player.setQueue(with: MPMediaItemCollection(items: [item]))
                }
                guard player.nowPlayingItem != nil || args["itemId"] != nil else { throw Failure(code: "data_unavailable") }
                player.play()
            } else if tool == "music.pause" { player.pause() }
            else if tool == "music.seek" {
                guard let seconds = args["seconds"] as? Double, seconds.isFinite, seconds >= 0, let item = player.nowPlayingItem,
                      seconds <= item.playbackDuration else { throw Failure(code: "invalid_request") }
                player.currentPlaybackTime = seconds
            }
            return ["playbackState": player.playbackState.rawValue, "playbackSeconds": player.currentPlaybackTime.isFinite ? player.currentPlaybackTime : 0, "requested": tool]
        default: throw Failure(code: "tool_unsupported")
        }
    }
    @available(iOS 26.0, *)
    private func executeAlarm(_ tool: String, _ args: [String: Any], sessionID: String) async throws -> [String: Any] {
        let manager = AlarmManager.shared
        guard manager.authorizationState == .authorized else { throw Failure(code: "permission_denied") }
        if tool == "alarms.create" {
            guard let date = PhoneConnectorWriteInput.date(args["fireDate"]), date > Date() else { throw Failure(code: "invalid_request") }
            let title = args["title"] as! String
            let presentation: AlarmPresentation.Alert
            if #available(iOS 26.1, *) { presentation = .init(title: LocalizedStringResource(stringLiteral: title)) }
            else { presentation = .init(title: LocalizedStringResource(stringLiteral: title), stopButton: AlarmButton(text: "停止", textColor: .white, systemImageName: "stop.fill")) }
            let attributes = AlarmAttributes<PhoneAlarmMetadata>(presentation: AlarmPresentation(alert: presentation), metadata: PhoneAlarmMetadata(), tintColor: .blue)
            let id = writes.alarmIdentifier(sessionID: sessionID, operationID: args["operationId"] as! String)
            try Task.checkCancellation()
            _ = try await manager.schedule(id: id, configuration: .alarm(schedule: .fixed(date), attributes: attributes))
            return ["id": id.uuidString, "title": title, "fireDate": ISO8601DateFormatter().string(from: date), "timeZone": args["timeZone"]!,
                    "state": "scheduled", "operationId": args["operationId"]!, "status": "created"]
        }
        let owned = try writes.alarmReceipts()
        let alarms = try manager.alarms
        if tool == "alarms.cancel" {
            guard let value = args["alarmId"] as? String, let id = UUID(uuidString: value), owned[value.lowercased()] != nil else { throw Failure(code: "alarm_not_owned") }
            try Task.checkCancellation()
            if alarms.contains(where: { $0.id == id }) { try manager.cancel(id: id) }
            return ["id": id.uuidString, "operationId": args["operationId"]!, "status": "cancelled"]
        }
        let items = alarms.compactMap { alarm -> [String: Any]? in
            guard let receipt = owned[alarm.id.uuidString.lowercased()] else { return nil }
            let state: String
            switch alarm.state {
            case .scheduled: state = "scheduled"
            case .alerting: state = "alerting"
            case .countdown: state = "countdown"
            case .paused: state = "paused"
            @unknown default: state = "unknown"
            }
            return ["id": alarm.id.uuidString, "title": receipt["title"]!, "fireDate": receipt["fireDate"]!, "timeZone": receipt["timeZone"]!, "state": state]
        }.sorted { ($0["fireDate"] as? String ?? "") < ($1["fireDate"] as? String ?? "") }
        return ["items": Array(items.prefix(100)), "truncated": items.count > 100, "source": "whaleness-host-alarms"]
    }
    private func eventAccess(_ type: EKEntityType) -> Bool {
        let status = EKEventStore.authorizationStatus(for: type)
        if #available(iOS 17.0, *) { return status == .fullAccess }
        return status == .authorized
    }
    private func healthSummary(_ args: [String: Any]) async throws -> [String: Any] {
        let (start, end, zone) = try range(args)
        let steps = HKQuantityType.quantityType(forIdentifier: .stepCount)!
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let count: Double? = try await withCheckedThrowingContinuation { continuation in
            var completed = false
            let query = HKStatisticsQuery(quantityType: steps, quantitySamplePredicate: predicate, options: .cumulativeSum) { _, result, error in
                Task { @MainActor in
                    guard !completed else { return }; completed = true
                    if let error { continuation.resume(throwing: error) }
                    else { continuation.resume(returning: result?.sumQuantity()?.doubleValue(for: .count())) }
                }
            }
            nativeCancel = { [health] in
                health.stop(query)
                if !completed { completed = true; continuation.resume(throwing: CancellationError()) }
            }
            health.execute(query)
        }
        try Task.checkCancellation()
        let sleepType = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis)!
        let samples: [HKSample] = try await withCheckedThrowingContinuation { continuation in
            var completed = false
            let query = HKSampleQuery(sampleType: sleepType, predicate: predicate, limit: 2001, sortDescriptors: nil) { _, found, error in
                Task { @MainActor in
                    guard !completed else { return }; completed = true
                    if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: found ?? []) }
                }
            }
            nativeCancel = { [health] in
                health.stop(query)
                if !completed { completed = true; continuation.resume(throwing: CancellationError()) }
            }
            health.execute(query)
        }
        try Task.checkCancellation()
        guard samples.count <= 2000 else { throw Failure(code: "result_too_large") }
        let sorted = samples.compactMap { $0 as? HKCategorySample }.filter {
            [HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue, HKCategoryValueSleepAnalysis.asleepCore.rawValue, HKCategoryValueSleepAnalysis.asleepDeep.rawValue, HKCategoryValueSleepAnalysis.asleepREM.rawValue].contains($0.value)
        }.map { (max(start, $0.startDate), min(end, $0.endDate)) }.filter { $0.0 < $0.1 }.sorted { $0.0 < $1.0 }
        var merged: [(Date, Date)] = []
        for row in sorted {
            if let last = merged.last, row.0 <= last.1 { merged[merged.count - 1].1 = max(last.1, row.1) }
            else { merged.append(row) }
        }
        return ["steps": count as Any? ?? NSNull(), "sleepMinutes": merged.isEmpty ? NSNull() : NSNumber(value: merged.reduce(0) { $0 + $1.1.timeIntervalSince($1.0) } / 60),
                "timeZone": zone.identifier, "coverage": "authorized-health-data", "availability": "no-data-may-mean-not-shared", "collectedAt": ISO8601DateFormatter().string(from: Date())]
    }
    nonisolated func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        Task { @MainActor in
            picker.dismiss(animated: true)
            guard self.picker === picker, let continuation = photoResult else { return }
            self.picker = nil
            guard let item = results.first else { photoResult = nil; continuation.resume(throwing: Failure(code: "cancelled")); return }
            let ownerID = pending?.id
            item.itemProvider.loadFileRepresentation(forTypeIdentifier: "public.image") { [weak self] url, _ in
                // URL 仅在 provider 回调内有效；解码缩略图并重新编码，移除原图元数据。
                let image: [String: Any]? = url.flatMap { Self.thumbnail($0) }
                Task { @MainActor in
                    guard let self, self.pending?.id == ownerID, self.photoResult != nil else { return }
                    self.photoResult = nil
                    if let image { continuation.resume(returning: ["images": [image]]) }
                    else { continuation.resume(throwing: Failure(code: "data_unavailable")) }
                }
            }
        }
    }
    nonisolated private static func thumbnail(_ url: URL) -> [String: Any]? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 768] as CFDictionary) else { return nil }
        let image = UIImage(cgImage: cg)
        for quality in [0.65, 0.45, 0.25, 0.1] {
            if let data = image.jpegData(compressionQuality: quality), data.count <= 65536 {
                return ["mediaType": "image/jpeg", "data": data.base64EncodedString(), "width": cg.width, "height": cg.height]
            }
        }
        return nil
    }
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            guard location.authorizationStatus != .notDetermined, let waiting = locationAuthorization else { return }
            locationAuthorization = nil; waiting.resume(returning: [.authorizedAlways, .authorizedWhenInUse].contains(location.authorizationStatus))
        }
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            guard activeLocation === manager, let sample = locations.last, let pending = locationResult else { return }
            locationResult = nil; activeLocation?.delegate = nil; activeLocation = nil; pending.resume(returning: sample)
        }
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            guard activeLocation === manager, let pending = locationResult else { return }
            locationResult = nil; activeLocation?.delegate = nil; activeLocation = nil; pending.resume(throwing: error)
        }
    }
}

@available(iOS 26.0, *)
private struct PhoneAlarmMetadata: AlarmMetadata {}
