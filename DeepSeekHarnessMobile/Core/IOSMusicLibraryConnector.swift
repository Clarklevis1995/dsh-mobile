import Foundation
import MusicKit

/// 只保留 Apple Music 内容链接及歌曲选择参数，不从资料库 ID 猜测目录链接。
enum AppleMusicLink {
    static func artworkURL(_ value: String) -> URL? {
        guard value.utf16.count <= 2048, let parts = URLComponents(string: value),
              parts.scheme?.lowercased() == "https", let host = parts.host?.lowercased(),
              host == "mzstatic.com" || host.hasSuffix(".mzstatic.com"),
              parts.user == nil, parts.password == nil, parts.port == nil, parts.fragment == nil else { return nil }
        return parts.url
    }
    static func url(_ value: String) -> URL? {
        guard value.utf16.count <= 4096, var parts = URLComponents(string: value),
              parts.scheme?.lowercased() == "https", parts.host?.lowercased() == "music.apple.com",
              parts.user == nil, parts.password == nil, parts.port == nil, parts.fragment == nil else { return nil }
        var path = parts.path.split(separator: "/").map(String.init)
        if path.first?.range(of: "^[a-z]{2}$", options: .regularExpression) != nil { path.removeFirst() }
        guard (2...3).contains(path.count), let kind = path.first, ["song", "album", "artist", "playlist"].contains(kind),
              let id = path.last, id.range(of: kind == "playlist" ? "^pl\\.[A-Za-z0-9._-]{1,200}$" : "^[0-9]{1,20}$", options: .regularExpression) != nil else { return nil }
        let song = (parts.queryItems ?? []).filter { $0.name == "i" }
        guard song.count <= 1, song.allSatisfy({ $0.value?.range(of: "^[0-9]{1,20}$", options: .regularExpression) != nil }) else { return nil }
        parts.scheme = "https"; parts.host = "music.apple.com"
        parts.queryItems = song.isEmpty ? nil : song
        return parts.url
    }
}

struct PhoneMusicCatalogOptions {
    let query: String
    let limit: Int
    init(_ args: [String: Any]) throws {
        guard Set(args.keys).isSubset(of: ["query", "limit"]), let text = args["query"] as? String,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf16.count <= 100 else { throw PhoneConnectorFailure(code: "invalid_request") }
        query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let value = args["limit"] {
            guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite,
                  value.doubleValue.rounded() == value.doubleValue, (1...20).contains(value.intValue) else { throw PhoneConnectorFailure(code: "invalid_request") }
            limit = value.intValue
        } else { limit = 10 }
    }
}

struct PhoneMusicLibraryOptions {
    let kind: String
    let query: String?
    let offset: Int
    let limit: Int

    init(_ args: [String: Any]) throws {
        guard Set(args.keys).isSubset(of: ["kind", "query", "offset", "limit"]) else { throw PhoneConnectorFailure(code: "invalid_request") }
        if let kind = args["kind"], !(kind is String) { throw PhoneConnectorFailure(code: "invalid_request") }
        kind = args["kind"] as? String ?? "songs"
        guard ["songs", "albums", "artists", "playlists"].contains(kind) else { throw PhoneConnectorFailure(code: "invalid_request") }
        if let value = args["query"] {
            guard let text = value as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf16.count <= 100 else { throw PhoneConnectorFailure(code: "invalid_request") }
            query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        } else { query = nil }
        func integer(_ key: String, default fallback: Int, range: ClosedRange<Int>) throws -> Int {
            guard let value = args[key] else { return fallback }
            guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
                  value.doubleValue.isFinite, value.doubleValue.rounded() == value.doubleValue, range.contains(value.intValue) else { throw PhoneConnectorFailure(code: "invalid_request") }
            return value.intValue
        }
        offset = try integer("offset", default: 0, range: 0...10000)
        limit = try integer("limit", default: 20, range: 1...50)
    }
}

struct PhoneMusicLibraryPage {
    let items: [[String: Any]]
    let hasMore: Bool
}

/// 使用个人资料库请求，不搜索在线目录，不把系统读取错误降级为空曲库。
@MainActor
final class IOSMusicLibraryConnector {
    typealias Loader = @MainActor (PhoneMusicLibraryOptions) async throws -> PhoneMusicLibraryPage
    typealias CatalogLoader = @MainActor (PhoneMusicCatalogOptions) async throws -> [[String: Any]]
    private let authorized: () -> Bool
    private let loader: Loader
    private let catalogLoader: CatalogLoader

    init(authorized: @escaping () -> Bool = { MusicAuthorization.currentStatus == .authorized }, loader: Loader? = nil) {
        self.authorized = authorized
        self.loader = loader ?? Self.load
        self.catalogLoader = Self.loadCatalog
    }
    init(catalogLoader: @escaping CatalogLoader, authorized: @escaping () -> Bool) {
        self.authorized = authorized
        self.loader = Self.load
        self.catalogLoader = catalogLoader
    }
    func searchCatalog(_ args: [String: Any]) async throws -> [String: Any] {
        let options = try PhoneMusicCatalogOptions(args)
        guard authorized() else { throw PhoneConnectorFailure(code: "permission_denied") }
        let items: [[String: Any]]
        do { items = try await catalogLoader(options) }
        catch is CancellationError { throw CancellationError() }
        catch {
            if !authorized() { throw PhoneConnectorFailure(code: "permission_denied") }
            throw PhoneConnectorFailure(code: "music_catalog_unavailable")
        }
        try Task.checkCancellation()
        guard authorized() else { throw PhoneConnectorFailure(code: "permission_denied") }
        return ["items": Array(items.prefix(options.limit)), "source": "apple-music-catalog"]
    }
    func browse(_ args: [String: Any]) async throws -> [String: Any] {
        let options = try PhoneMusicLibraryOptions(args)
        guard authorized() else { throw PhoneConnectorFailure(code: "permission_denied") }
        let page: PhoneMusicLibraryPage
        do { page = try await loader(options) }
        catch is CancellationError { throw CancellationError() }
        catch {
            if !authorized() { throw PhoneConnectorFailure(code: "permission_denied") }
            throw PhoneConnectorFailure(code: "music_library_unavailable")
        }
        try Task.checkCancellation()
        guard authorized() else { throw PhoneConnectorFailure(code: "permission_denied") }
        let pagingLimitReached = page.hasMore && options.offset + page.items.count > 10000
        return ["items": page.items, "kind": options.kind, "offset": options.offset, "limit": options.limit,
                "hasMore": page.hasMore, "nextOffset": page.hasMore && !pagingLimitReached ? options.offset + page.items.count : NSNull(), "pagingLimitReached": pagingLimitReached,
                "source": "apple-music-library", "scope": "device-synced-personal-library", "downloadedOnly": false,
                "status": page.items.isEmpty ? "empty-in-requested-scope" : "ok"]
    }
    private static func itemID(_ id: MusicItemID) -> String { "library:" + id.rawValue }
    private static func bounded(_ value: String) -> String { String(decoding: value.utf16.prefix(200), as: UTF16.self) }
    private static func linked(_ row: [String: Any], url: URL?, artwork: Artwork?, duration: TimeInterval? = nil) -> [String: Any] {
        var result = row
        if let value = url?.absoluteString, let url = AppleMusicLink.url(value) { result["url"] = url.absoluteString }
        if let value = artwork?.url(width: 300, height: 300)?.absoluteString, let url = AppleMusicLink.artworkURL(value) {
            result["artworkURL"] = url.absoluteString
        }
        if let duration, duration.isFinite, (0...86400).contains(duration) { result["durationSeconds"] = duration }
        return result
    }
    private static func loadCatalog(_ options: PhoneMusicCatalogOptions) async throws -> [[String: Any]] {
        var request = MusicCatalogSearchRequest(term: options.query, types: [Song.self])
        request.limit = options.limit
        return try await request.response().songs.map {
            linked(["id": "catalog:" + $0.id.rawValue, "title": bounded($0.title), "artist": bounded($0.artistName), "album": bounded($0.albumTitle ?? "")], url: $0.url, artwork: $0.artwork, duration: $0.duration)
        }
    }
    private static func page<T: MusicLibraryRequestable>(_ type: T.Type, options: PhoneMusicLibraryOptions,
            sort: (inout MusicLibraryRequest<T>) -> Void, row: (T) -> [String: Any]) async throws -> PhoneMusicLibraryPage {
        var request = MusicLibraryRequest<T>()
        request.includeOnlyDownloadedContent = false
        request.limit = options.limit + 1; request.offset = options.offset
        if let query = options.query { request.filter(text: query) }
        sort(&request)
        let items = try await request.response().items
        return PhoneMusicLibraryPage(items: items.prefix(options.limit).map(row), hasMore: items.count > options.limit || items.hasNextBatch)
    }
    private static func load(_ options: PhoneMusicLibraryOptions) async throws -> PhoneMusicLibraryPage {
        switch options.kind {
        case "songs":
            return try await page(Song.self, options: options, sort: { $0.sort(by: \.title, ascending: true) }) {
                linked(["id": itemID($0.id), "title": bounded($0.title), "artist": bounded($0.artistName), "album": bounded($0.albumTitle ?? "")], url: $0.url, artwork: $0.artwork, duration: $0.duration)
            }
        case "albums":
            return try await page(Album.self, options: options, sort: { $0.sort(by: \.title, ascending: true) }) {
                linked(["id": itemID($0.id), "title": bounded($0.title), "artist": bounded($0.artistName)], url: $0.url, artwork: $0.artwork)
            }
        case "artists":
            return try await page(Artist.self, options: options, sort: { $0.sort(by: \.name, ascending: true) }) {
                linked(["id": itemID($0.id), "title": bounded($0.name)], url: $0.url, artwork: $0.artwork)
            }
        default:
            return try await page(Playlist.self, options: options, sort: { $0.sort(by: \.name, ascending: true) }) {
                linked(["id": itemID($0.id), "title": bounded($0.name)], url: $0.url, artwork: $0.artwork)
            }
        }
    }
    static func validatePlayback(_ args: [String: Any]) throws -> (kind: String, id: MusicItemID) {
        guard Set(args.keys) == Set(["kind", "itemId"]), let kind = args["kind"] as? String,
              ["songs", "albums", "playlists"].contains(kind), let value = args["itemId"] as? String,
              value.hasPrefix("library:"), value.utf16.count > 8, value.utf16.count <= 208,
              value.rangeOfCharacter(from: .controlCharacters) == nil else { throw PhoneConnectorFailure(code: "invalid_request") }
        return (kind, MusicItemID(String(value.dropFirst(8))))
    }
    static func play(_ args: [String: Any]) async throws {
        let input = try validatePlayback(args)
        guard MusicAuthorization.currentStatus == .authorized else { throw PhoneConnectorFailure(code: "permission_denied") }
        let player = SystemMusicPlayer.shared
        // 只从个人资料库按返回的 ID 解析，不把不存在的 ID 自动切换为全网搜歌。
        switch input.kind {
        case "songs":
            var request = MusicLibraryRequest<Song>(); request.filter(matching: \.id, equalTo: input.id); request.limit = 1
            guard let item = try await request.response().items.first else { throw PhoneConnectorFailure(code: "music_item_not_found") }
            try Task.checkCancellation(); player.queue = .init(for: [item])
        case "albums":
            var request = MusicLibraryRequest<Album>(); request.filter(matching: \.id, equalTo: input.id); request.limit = 1
            guard let item = try await request.response().items.first else { throw PhoneConnectorFailure(code: "music_item_not_found") }
            try Task.checkCancellation(); player.queue = .init(for: [item])
        default:
            var request = MusicLibraryRequest<Playlist>(); request.filter(matching: \.id, equalTo: input.id); request.limit = 1
            guard let item = try await request.response().items.first else { throw PhoneConnectorFailure(code: "music_item_not_found") }
            try Task.checkCancellation(); player.queue = .init(for: [item])
        }
        try Task.checkCancellation()
        try await player.play()
    }
}
