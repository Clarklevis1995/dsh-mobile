import Foundation

@MainActor
final class RequestTracker {
    private var generations: [String: UUID] = [:]
    private var timeoutTasks: [String: Task<Void, Never>] = [:]

    var activeKeys: Set<String> { Set(generations.keys) }

    func begin(
        _ key: String,
        timeout: Duration,
        onTimeout: @escaping @MainActor () -> Void
    ) {
        finish(key)
        let generation = UUID()
        generations[key] = generation
        timeoutTasks[key] = Task { [weak self] in
            do {
                try await Task.sleep(for: timeout)
            } catch {
                return
            }
            guard let self,
                  self.generations[key] == generation else { return }
            self.generations[key] = nil
            self.timeoutTasks[key] = nil
            onTimeout()
        }
    }

    func finish(_ key: String) {
        generations[key] = nil
        timeoutTasks.removeValue(forKey: key)?.cancel()
    }

    func finishAll() {
        for task in timeoutTasks.values { task.cancel() }
        timeoutTasks.removeAll()
        generations.removeAll()
    }
}
