import Foundation

/// Centralized user-facing strings that are compared programmatically, shared
/// across screens, or need stable localization keys with interpolated
/// defaults. Keeping them here guarantees producers and consumers resolve
/// identical values at runtime, independent of device locale. Never compare
/// localized UI text against raw literals — always route through these
/// constants.
enum L10n {
    // MARK: Session title placeholders

    /// Placeholder title assigned when a brand-new session is created locally.
    static let newSessionPlaceholderTitle = String(localized: "新建 Harness 会话")

    /// Prefix shared by all remote-session placeholder titles.
    static let remoteSessionTitlePrefix = String(localized: "远端会话")

    /// Placeholder title for a session first discovered on the server.
    static func remoteSessionTitle(_ idPrefix: String) -> String {
        String(localized: "session.remote.title", defaultValue: "远端会话 \(idPrefix)")
    }

    /// Placeholder title for a locally created blank session.
    static func blankSessionTitle(_ idPrefix: String) -> String {
        String(localized: "session.blank.title", defaultValue: "空白会话 \(idPrefix)")
    }

    // MARK: Conversation projection markers
    //
    // These titles double as state markers exchanged between
    // ConversationProjection and its consumers (streaming detection, tool
    // result rendering). Comparisons must use these constants so matching
    // keeps working under any localization.

    static let userMessageTitle = String(localized: "你")
    static let streamingAssistantTitle = String(localized: "DeepSeek · 正在生成")
    static let streamingReasoningTitle = String(localized: "Think · 正在推理")
    static let assemblingToolTitle = String(localized: "Tool Call · 正在组装")
    static let toolResultDoneTitle = String(localized: "工具完成")
    static let toolResultFailedTitle = String(localized: "工具失败")

    /// Title for context-injection events, e.g. “Context injection · plugin”.
    static func contextInjectionTitle(_ source: String) -> String {
        String(localized: "projection.context-injection", defaultValue: "上下文注入 · \(source)")
    }

    // MARK: Agent preset labels
    //
    // Single source of truth; GatewayModels and the settings/conversation
    // screens all render through these helpers.

    static func presetModeName(for id: String) -> String {
        switch id {
        case "standard": String(localized: "标准模式")
        case "code": String(localized: "PTC 模式")
        case "minimal": String(localized: "极简模式")
        case "cordis": String(localized: "创造模式")
        default: id
        }
    }

    static func presetModeBlurb(for id: String) -> String {
        switch id {
        case "standard":
            String(localized: "preset.blurb.standard", defaultValue: "功能完整的编码 Agent，支持文件编辑、Shell、检索、Skills、计划与工作流。")
        case "code":
            String(localized: "preset.blurb.code", defaultValue: "通过 Code Mode SDK 组合多步工具操作。")
        case "minimal":
            String(localized: "preset.blurb.minimal", defaultValue: "精简工具集合，适合轻量、直接的编码任务。")
        case "cordis":
            String(localized: "preset.blurb.cordis", defaultValue: "用于创建和维护自定义 Agent 预设。")
        default:
            String(localized: "preset.blurb.default", defaultValue: "由 DeepSeek Harness 提供的 Agent 预设。")
        }
    }

    // MARK: Permission labels

    static func permissionName(for id: String) -> String {
        switch id {
        case "ask": String(localized: "每次询问")
        case "read-only": String(localized: "只读")
        case "workspace-write": String(localized: "工作区写入")
        case "danger-full-access": String(localized: "完全访问")
        default: id
        }
    }

    // MARK: Reasoning effort labels

    static func reasoningEffortName(for level: String) -> String {
        switch level {
        case "low": String(localized: "低")
        case "medium": String(localized: "中")
        case "high": String(localized: "高")
        default: level
        }
    }
}
