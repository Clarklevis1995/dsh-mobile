import Foundation
import SwiftUI
import UIKit
import WebKit

enum GenuiFontScale {
    static func effective(appScale: Double, followsSystem: Bool, dynamicTypeSize: DynamicTypeSize) -> CGFloat {
        let categories: [UIContentSizeCategory] = [
            .extraSmall, .small, .medium, .large, .extraLarge,
            .extraExtraLarge, .extraExtraExtraLarge, .accessibilityMedium,
            .accessibilityLarge, .accessibilityExtraLarge,
            .accessibilityExtraExtraLarge, .accessibilityExtraExtraExtraLarge
        ]
        let index = DynamicTypeSize.allCases.firstIndex(of: dynamicTypeSize) ?? 3
        let category = categories[min(max(index, 0), categories.count - 1)]
        let bodySize = UIFont.preferredFont(
            forTextStyle: .body,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: category)
        ).pointSize
        return min(2, max(0.8, bodySize / 17 * CGFloat(AppFontScale.effective(appScale, followsSystem: followsSystem))))
    }
}

enum GenuiActionKind: String, Decodable, Sendable {
    case sendMessage = "SEND_MESSAGE"
    case copyText = "COPY_TEXT"
    case expandDetails = "EXPAND_DETAILS"
}

struct GenuiActionEffectPayload: Decodable, Sendable {
    let type: GenuiActionKind
    let sessionId: String
    let blockId: String
    let contentVersion: String
    let text: String?
    let details: String?

    private enum CodingKeys: String, CodingKey { case type, sessionId, blockId, contentVersion, text, details }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(GenuiActionKind.self, forKey: .type)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        blockId = try container.decode(String.self, forKey: .blockId)
        contentVersion = try container.decode(String.self, forKey: .contentVersion)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        details = try container.decodeIfPresent(String.self, forKey: .details)
        guard !sessionId.isEmpty,
              !blockId.isEmpty,
              blockId.utf16.count <= 512,
              contentVersion.utf16.count == 64,
              (text?.utf16.count ?? 0) <= 8_192,
              (details?.utf16.count ?? 0) <= 8_192 else {
            throw DecodingError.dataCorruptedError(forKey: .blockId, in: container, debugDescription: "Invalid GenUI effect bounds")
        }
    }
}

/// Serves vendored offline GenUI assets synchronously from in-memory bytes on WebKit's
/// callback isolation domain, tracking active task identities so a stopped task can never
/// receive `didReceive`, `didFinish`, or `didFailWithError`.
private final class GenuiResourceSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "dsh-genui"
    static let htmlURL = URL(string: "dsh-genui://local/genui/genui.html")!

    private static let contentTypes = [
        "genui.html": "text/html; charset=utf-8",
        "genui-bridge.js": "application/javascript",
        "echarts-core.js": "application/javascript",
        "icons.js": "application/javascript",
        "standalone-runtime.js": "application/javascript"
    ]

    private static let resources: [String: (data: Data, contentType: String)] = {
        let bundle = Bundle.main
        var loaded: [String: (data: Data, contentType: String)] = [:]
        for (name, contentType) in contentTypes {
            let resource = (name as NSString).deletingPathExtension
            let ext = (name as NSString).pathExtension
            guard let url = bundle.url(forResource: resource, withExtension: ext, subdirectory: "genui")
                ?? bundle.url(forResource: resource, withExtension: ext),
                let data = try? Data(contentsOf: url) else { continue }
            loaded[name] = (data, contentType)
        }
        return loaded
    }()

    private var activeTasks = Set<ObjectIdentifier>()

    func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        let taskID = ObjectIdentifier(urlSchemeTask)
        activeTasks.insert(taskID)
        defer { activeTasks.remove(taskID) }

        let request = urlSchemeTask.request
        guard request.httpMethod == "GET",
              let url = request.url,
              url.scheme == Self.scheme,
              url.host == "local" else {
            if activeTasks.contains(taskID) {
                urlSchemeTask.didFailWithError(URLError(.unsupportedURL))
            }
            return
        }
        let name = url.lastPathComponent
        guard url.path == "/genui/\(name)",
              let resource = Self.resources[name],
              let response = HTTPURLResponse(
                url: url,
                statusCode: 200,
                httpVersion: "HTTP/1.1",
                headerFields: [
                    "Content-Type": resource.contentType,
                    "Content-Length": "\(resource.data.count)",
                    "Cache-Control": "no-store"
                ]
              ) else {
            if activeTasks.contains(taskID) {
                urlSchemeTask.didFailWithError(URLError(.unsupportedURL))
            }
            return
        }
        guard activeTasks.contains(taskID) else { return }
        urlSchemeTask.didReceive(response)
        guard activeTasks.contains(taskID) else { return }
        urlSchemeTask.didReceive(resource.data)
        guard activeTasks.contains(taskID) else { return }
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        activeTasks.remove(ObjectIdentifier(urlSchemeTask))
    }
}

struct GenuiDetail: Identifiable {
    let id = UUID()
    let text: String
}

struct GenuiDetailSheet: View {
    let detail: GenuiDetail
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(verbatim: detail.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding()
            }
            .navigationTitle(String(localized: "详情"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

struct GenuiBlockView: UIViewRepresentable {
    let segment: GenuiSegment
    let isDark: Bool
    let fontScale: CGFloat
    @Binding var height: CGFloat
    @Binding var failed: Bool
    var onAction: (_ blockID: String, _ contentVersion: String, _ actionJSON: String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator
        coordinator.blockId = segment.id
        coordinator.heightChanged = { height = $0 }
        coordinator.failureChanged = { failed = true }
        coordinator.actionHandler = onAction

        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(GenuiResourceSchemeHandler(), forURLScheme: GenuiResourceSchemeHandler.scheme)
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.userContentController.add(coordinator, name: "genui")

        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.isScrollEnabled = false
        view.scrollView.bounces = false
        view.scrollView.showsVerticalScrollIndicator = false
        view.scrollView.showsHorizontalScrollIndicator = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.scrollView.pinchGestureRecognizer?.isEnabled = false
        view.allowsBackForwardNavigationGestures = false
        view.navigationDelegate = coordinator
        coordinator.webView = view
        coordinator.load(segment: segment, isDark: isDark, fontScale: fontScale)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.heightChanged = { height = $0 }
        coordinator.failureChanged = { failed = true }
        coordinator.actionHandler = onAction
        coordinator.update(segment: segment, isDark: isDark, fontScale: fontScale)
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.activeRenderId += 1
        coordinator.webView = nil
        coordinator.heightChanged = nil
        coordinator.failureChanged = nil
        coordinator.actionHandler = nil
        view.navigationDelegate = nil
        view.stopLoading()
        view.configuration.userContentController.removeScriptMessageHandler(forName: "genui")
        view.loadHTMLString("", baseURL: nil)
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        weak var webView: WKWebView?
        var blockId = ""
        var heightChanged: ((CGFloat) -> Void)?
        var failureChanged: (() -> Void)?
        var actionHandler: ((_ blockID: String, _ contentVersion: String, _ actionJSON: String) -> Void)?
        private var loaded = false
        fileprivate var activeRenderId = 0
        private var lastHeight: CGFloat = 72
        private var segment: GenuiSegment?
        private var isDark = false
        private var fontScale: CGFloat = 1
        private var renderedBlockId: String?
        private var renderedRawJson: String?
        private var renderedIsDark: Bool?
        private var renderedFontScale: CGFloat?
        private var renderedInteractive: Bool?

        func load(segment: GenuiSegment, isDark: Bool, fontScale: CGFloat) {
            self.segment = segment
            self.isDark = isDark
            self.fontScale = fontScale
            self.blockId = segment.id
            loaded = false
            renderedBlockId = nil
            renderedRawJson = nil
            renderedIsDark = nil
            renderedFontScale = nil
            renderedInteractive = nil
            activeRenderId += 1
            lastHeight = 72
            heightChanged?(lastHeight)
            webView?.load(URLRequest(url: GenuiResourceSchemeHandler.htmlURL))
        }

        func update(segment: GenuiSegment, isDark: Bool, fontScale: CGFloat) {
            if self.blockId != segment.id {
                load(segment: segment, isDark: isDark, fontScale: fontScale)
                return
            }
            self.segment = segment
            self.isDark = isDark
            self.fontScale = fontScale
            syncIfLoaded()
        }

        private func syncIfLoaded() {
            guard loaded, let webView, let segment else { return }
            if renderedBlockId == segment.id && renderedRawJson == segment.rawJson {
                if renderedIsDark != isDark {
                    renderedIsDark = isDark
                    applyThemeInPlace(webView: webView, isDark: isDark)
                }
                if renderedFontScale == nil || abs((renderedFontScale ?? 0) - fontScale) >= 0.001 {
                    renderedFontScale = fontScale
                    applyFontScaleInPlace(webView: webView, fontScale: fontScale)
                }
                if renderedInteractive != segment.interactive {
                    renderedInteractive = segment.interactive
                    applyInteractiveInPlace(webView: webView, interactive: segment.interactive)
                }
                return
            }

            activeRenderId += 1
            let renderId = activeRenderId
            do {
                let payload = String(decoding: try JSONEncoder().encode(segment.rawJson), as: UTF8.self)
                let theme = String(decoding: try JSONEncoder().encode(isDark ? "dark" : "light"), as: UTF8.self)
                let scale = String(format: "%.4f", Double(fontScale))
                let interactiveFlag = segment.interactive ? "true" : "false"
                let script = """
                (function() {
                  if (typeof window.renderGenui !== 'function') return false;
                  window.renderGenui(\(payload), \(theme), \(renderId), \(interactiveFlag));
                  window.setGenuiFontScale(\(scale));
                  return true;
                })()
                """
                renderedBlockId = segment.id
                renderedRawJson = segment.rawJson
                renderedIsDark = isDark
                renderedFontScale = fontScale
                renderedInteractive = segment.interactive
                webView.evaluateJavaScript(script) { result, error in
                    guard renderId == self.activeRenderId else { return }
                    if error != nil || (result as? Bool) != true {
                        self.failureChanged?()
                    }
                }
            } catch {
                guard renderId == activeRenderId else { return }
                failureChanged?()
            }
        }

        private func applyThemeInPlace(webView: WKWebView, isDark: Bool) {
            guard let encodedTheme = try? JSONEncoder().encode(isDark ? "dark" : "light") else { return }
            let theme = String(decoding: encodedTheme, as: UTF8.self)
            webView.evaluateJavaScript("if (typeof window.setGenuiTheme === 'function') window.setGenuiTheme(\(theme));", completionHandler: nil)
        }

        private func applyFontScaleInPlace(webView: WKWebView, fontScale: CGFloat) {
            let scale = String(format: "%.4f", Double(fontScale))
            webView.evaluateJavaScript("if (typeof window.setGenuiFontScale === 'function') window.setGenuiFontScale(\(scale));", completionHandler: nil)
        }

        private func applyInteractiveInPlace(webView: WKWebView, interactive: Bool) {
            let flag = interactive ? "true" : "false"
            webView.evaluateJavaScript("if (typeof window.setGenuiInteractive === 'function') window.setGenuiInteractive(\(flag));", completionHandler: nil)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            syncIfLoaded()
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard navigationAction.targetFrame?.isMainFrame == true,
                  navigationAction.request.url == GenuiResourceSchemeHandler.htmlURL else {
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) {
            guard (error as NSError).code != NSURLErrorCancelled else { return }
            failureChanged?()
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: any Error) {
            guard (error as NSError).code != NSURLErrorCancelled else { return }
            failureChanged?()
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame,
                  message.frameInfo.request.url?.scheme == GenuiResourceSchemeHandler.scheme,
                  message.frameInfo.request.url?.host == "local",
                  let body = message.body as? [String: Any],
                  let name = body["name"] as? String,
                  let value = body["value"] as? String else { return }
            let messageRenderId = (body["renderId"] as? NSNumber)?.intValue ?? 0
            guard messageRenderId == activeRenderId, activeRenderId > 0 else { return }
            switch name {
            case "resize":
                guard let parsed = Double(value), parsed.isFinite else { return }
                let next = CGFloat(min(20_000, max(1, parsed)))
                guard abs(next - lastHeight) >= 0.5 else { return }
                lastHeight = next
                heightChanged?(next)
            case "action":
                guard value.utf16.count <= 16_384, !blockId.isEmpty,
                      let segment, segment.state == .ready, segment.interactive,
                      segment.contentVersion.utf16.count == 64 else { return }
                actionHandler?(blockId, segment.contentVersion, value)
            case "error":
                failureChanged?()
            default:
                break
            }
        }
    }
}

struct GenuiSegmentView: View {
    let segment: GenuiSegment
    let sourceText: String
    let markdown: (String) -> AnyView
    let isDark: Bool
    let fontScale: CGFloat
    let onAction: (_ blockID: String, _ contentVersion: String, _ actionJSON: String) -> Void
    @State private var height: CGFloat = 72
    @State private var failed = false

    private var fallbackText: String {
        segment.resolvedFallbackMarkdown(in: sourceText)
    }

    var body: some View {
        Group {
            switch segment.state {
            case .invalid:
                markdown(fallbackText)
            case .streaming:
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .accessibilityLabel(Text(String(localized: "正在加载中")))
            case .ready:
                if failed {
                    markdown(fallbackText)
                } else {
                    ZStack(alignment: .topLeading) {
                        GenuiBlockView(
                            segment: segment,
                            isDark: isDark,
                            fontScale: fontScale,
                            height: $height,
                            failed: $failed,
                            onAction: onAction
                        )
                        .frame(height: max(1, height))
                        if height <= 1 {
                            ProgressView().padding(12)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
        .onChange(of: segment.id) { _ in
            height = 72
            failed = false
        }
        .onChange(of: segment.rawJson) { _ in
            height = 72
            failed = false
        }
    }
}
