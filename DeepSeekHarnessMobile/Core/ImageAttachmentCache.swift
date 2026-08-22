import CryptoKit
import Foundation

/// A bounded memory cache backed by the app's purgeable Caches directory.
/// Attachment ids are content-addressed, so the same key is safe to share
/// across sessions after the gateway has validated session ownership.
final class ImageAttachmentCache {
    static let defaultTTL: TimeInterval = 7 * 24 * 60 * 60
    static let defaultMemoryCostLimit = 32 * 1_024 * 1_024

    private final class MemoryEntry: NSObject {
        let data: NSData
        let expiresAt: Date

        init(data: Data, expiresAt: Date) {
            self.data = data as NSData
            self.expiresAt = expiresAt
        }
    }

    private let memory = NSCache<NSString, MemoryEntry>()
    private let fileManager: FileManager
    private let directoryURL: URL
    private let ttl: TimeInterval
    private let now: () -> Date

    init(
        directoryURL: URL? = nil,
        ttl: TimeInterval = ImageAttachmentCache.defaultTTL,
        memoryCostLimit: Int = ImageAttachmentCache.defaultMemoryCostLimit,
        fileManager: FileManager = .default,
        now: @escaping () -> Date = Date.init
    ) {
        self.fileManager = fileManager
        self.ttl = ttl
        self.now = now
        self.directoryURL = directoryURL ?? Self.defaultDirectory(fileManager: fileManager)
        memory.totalCostLimit = memoryCostLimit
        memory.countLimit = 64

        try? fileManager.createDirectory(
            at: self.directoryURL,
            withIntermediateDirectories: true,
            attributes: nil
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var cacheDirectory = self.directoryURL
        try? cacheDirectory.setResourceValues(values)
        removeExpiredFiles()
    }

    func data(for attachmentId: String) -> Data? {
        let key = attachmentId as NSString
        let currentDate = now()
        if let entry = memory.object(forKey: key) {
            guard entry.expiresAt > currentDate else {
                memory.removeObject(forKey: key)
                removeFile(for: attachmentId)
                return nil
            }
            return entry.data as Data
        }

        let fileURL = fileURL(for: attachmentId)
        guard let attributes = try? fileManager.attributesOfItem(atPath: fileURL.path),
              let createdAt = attributes[.modificationDate] as? Date,
              createdAt.addingTimeInterval(ttl) > currentDate,
              let data = try? Data(contentsOf: fileURL, options: .mappedIfSafe) else {
            try? fileManager.removeItem(at: fileURL)
            return nil
        }
        insertIntoMemory(data, attachmentId: attachmentId, createdAt: createdAt)
        return data
    }

    func store(_ data: Data, for attachmentId: String) {
        let createdAt = now()
        insertIntoMemory(data, attachmentId: attachmentId, createdAt: createdAt)

        let fileURL = fileURL(for: attachmentId)
        do {
            try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            try fileManager.setAttributes([.modificationDate: createdAt], ofItemAtPath: fileURL.path)
        } catch {
            // A disk-cache failure must not prevent the image from rendering
            // during the current process lifetime.
        }
    }

    func removeExpiredFiles() {
        guard let files = try? fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        let cutoff = now().addingTimeInterval(-ttl)
        for fileURL in files {
            let values = try? fileURL.resourceValues(forKeys: [.contentModificationDateKey])
            guard let modifiedAt = values?.contentModificationDate, modifiedAt > cutoff else {
                try? fileManager.removeItem(at: fileURL)
                continue
            }
        }
    }

    func removeAllFromMemory() {
        memory.removeAllObjects()
    }

    private func insertIntoMemory(_ data: Data, attachmentId: String, createdAt: Date) {
        let entry = MemoryEntry(data: data, expiresAt: createdAt.addingTimeInterval(ttl))
        memory.setObject(entry, forKey: attachmentId as NSString, cost: data.count)
    }

    private func removeFile(for attachmentId: String) {
        try? fileManager.removeItem(at: fileURL(for: attachmentId))
    }

    private func fileURL(for attachmentId: String) -> URL {
        let digest = SHA256.hash(data: Data(attachmentId.utf8))
        let filename = digest.map { String(format: "%02x", $0) }.joined()
        return directoryURL.appendingPathComponent(filename, isDirectory: false)
    }

    private static func defaultDirectory(fileManager: FileManager) -> URL {
        let base = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        return base
            .appendingPathComponent("DshMobile", isDirectory: true)
            .appendingPathComponent("ImageAttachments", isDirectory: true)
    }
}
