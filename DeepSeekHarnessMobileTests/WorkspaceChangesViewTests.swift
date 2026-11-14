import XCTest
import SwiftUI
import DeepSeekHarnessShared
@testable import DeepSeekHarnessMobile

@MainActor
final class WorkspaceChangesViewTests: XCTestCase {
    func testWireFramePreservesDiffAndSummaryAndRendersSheet() async throws {
        XCTAssertNotNil(UIFont(name: "NotoSansSC-Thin", size: 12))
        XCTAssertNotNil(UIFont(name: "JetBrainsMono-Regular", size: 12))
        XCTAssertNotNil(UIImage(named: "WorkspaceReviewClose"))
        XCTAssertNotNil(UIImage(named: "WorkspaceReviewChevron"))
        XCTAssertNotNil(UIImage(named: "WorkspaceReviewWrap"))
        XCTAssertNotNil(UIImage(named: "WorkspaceReviewNoWrap"))
        let shared = SharedWorkspaceChangesStore()
        _ = shared.open(sessionId: "fixture", supported: true, connected: true)
        let announcement = shared.acceptJson(json: """
            {"kind":"event","sessionId":"fixture","seq":160,"event":{"type":"workspace/changes","turn":3}}
            """)
        let summaryID = try XCTUnwrap(announcement.requests.first?.correlationId)
        let summaryJSON = """
            {"kind":"workspace-changes-summary","requestId":"\(summaryID)","sessionId":"fixture","seq":160,
            "summary":{"turn":3,"total":2,"added":10,"deleted":1,"files":[
              {"path":".gitignore","display":".gitignore","added":1,"deleted":1},
              {"path":"src/ConversationView.swift","display":"src/ConversationView.swift","added":9,"deleted":0}]}}
            """
        let swiftFrame = try GatewayWireDecoder.decode(Data(summaryJSON.utf8))
        XCTAssertNotNil(swiftFrame.summary)
        let encoded = try JSONEncoder().encode(swiftFrame)
        _ = shared.acceptJson(json: String(decoding: encoded, as: UTF8.self))
        let request = shared.selectFile(index: 0)
        let requestID = try XCTUnwrap(request.requests.first?.correlationId)
        let diffJSON = """
            {"kind":"workspace-changes-diff","requestId":"\(requestID)","sessionId":"fixture","seq":160,"index":0,
             "diff":{"kind":"text","path":".gitignore","display":".gitignore","before":true,"after":true,
             "hunks":[{"oldStart":51,"oldLines":2,"newStart":51,"newLines":2,
             "lines":[" # 本地工程不应提交绝对路径","-/promo/onetake-old","+/promo/onetake-*"]}]}}
            """
        let swiftDiff = try GatewayWireDecoder.decode(Data(diffJSON.utf8))
        XCTAssertNotNil(swiftDiff.diff)
        XCTAssertEqual(swiftDiff.index, 0)
        _ = shared.acceptJson(json: String(decoding: try JSONEncoder().encode(swiftDiff), as: UTF8.self))
        XCTAssertEqual(shared.snapshot().previews.count, 1)
        let snapshot = shared.snapshot()
        let host = UIHostingController(rootView: Color(.systemGroupedBackground).sheet(isPresented: .constant(true)) {
            WorkspaceChangesSheet(state: snapshot, selectFile: { _ in }, retrySummary: {})
                .presentationDetents([.large]).presentationDragIndicator(.hidden)
        })
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        try await Task.sleep(for: .milliseconds(800))
        host.view.layoutIfNeeded()
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        let attachment = XCTAttachment(image: image)
        attachment.name = "文件改动面板"
        attachment.lifetime = .keepAlways
        add(attachment)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("workspace-changes-ios.png")
        try image.pngData()?.write(to: url)
        print("WORKSPACE_CHANGES_SCREENSHOT=\(url.path)")
        XCTAssertNotNil(host.presentedViewController)
    }
}
