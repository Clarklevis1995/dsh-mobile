import Foundation

struct HistorySyncConfiguration: Equatable {
    var pageMessageLimit = 60
    var pageByteBudget = 4 * 1024 * 1024
    var pagesPerBatch = 2

    static let `default` = HistorySyncConfiguration()
}

@MainActor
final class HistorySyncEngine {
    let configuration: HistorySyncConfiguration
    private let timeoutTracker = RequestTracker()
    private var generations: [String: UUID] = [:]

    init(configuration: HistorySyncConfiguration = .default) {
        self.configuration = configuration
    }

    func beginRequest(
        sessionID: String,
        timeout: Duration,
        onTimeout: @escaping @MainActor () -> Void
    ) {
        let generation = UUID()
        generations[sessionID] = generation
        timeoutTracker.begin(sessionID, timeout: timeout) { [weak self] in
            guard let self, self.generations[sessionID] == generation else { return }
            self.generations[sessionID] = nil
            onTimeout()
        }
    }

    func beginProcessing(sessionID: String) -> UUID {
        timeoutTracker.finish(sessionID)
        let generation = UUID()
        generations[sessionID] = generation
        return generation
    }

    func isCurrent(_ generation: UUID, sessionID: String) -> Bool {
        generations[sessionID] == generation
    }

    func isActive(sessionID: String) -> Bool {
        generations[sessionID] != nil
    }

    func finish(sessionID: String) {
        timeoutTracker.finish(sessionID)
        generations[sessionID] = nil
    }

    func cancelAll() {
        timeoutTracker.finishAll()
        generations.removeAll()
    }
}
