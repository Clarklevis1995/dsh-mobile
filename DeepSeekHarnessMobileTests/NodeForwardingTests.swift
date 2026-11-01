import XCTest
@testable import DeepSeekHarnessMobile

/// 钉住「只换连接端点，不动逻辑端点」这条不变量。
/// 新形状（本地转发器 + 系统 WS）里，这是唯一会静默出错的地方：
/// 它不崩，只是连到别的主机、或者把 path/查询改了让插件认不出这条连接。
final class NodeForwardingTests: XCTestCase {
    private func url(_ raw: String) -> URL {
        URL(string: raw)!   // swiftlint:disable:next force_unwrapping
    }

    func testConnectURLChangesOnlyHostAndPort() {
        let logical = url("ws://192.168.1.24:3081/ws/mobile")
        let local = NodeForwarding.connectURL(for: logical, localPort: 49475)
        XCTAssertEqual(local?.absoluteString, "ws://127.0.0.1:49475/ws/mobile")
        // 逻辑端点必须保持不变：凭据与档案标识都是从它取的。
        XCTAssertEqual(logical.absoluteString, "ws://192.168.1.24:3081/ws/mobile")
        XCTAssertEqual(logical.host, "192.168.1.24")
    }

    func testPathAndQueryArePreservedExactly() {
        let cases = [
            ("ws://host:3081/ws/mobile", "ws://127.0.0.1:5/ws/mobile"),
            // 空路径不能被改成 "/"：那是改了用户填的 URL。
            ("ws://host:3081", "ws://127.0.0.1:5"),
            ("ws://host:3081/a/b?x=1&y=2", "ws://127.0.0.1:5/a/b?x=1&y=2"),
            ("http://host:8080/ws/mobile", "http://127.0.0.1:5/ws/mobile"),
        ]
        for (input, expected) in cases {
            let got = NodeForwarding.connectURL(for: url(input), localPort: 5)?.absoluteString
            XCTAssertEqual(got, expected, input)
        }
    }

    func testTargetHostPortAndDefaults() {
        XCTAssertEqual(NodeForwarding.hostPort(url("ws://192.168.1.24:3081/ws/mobile"))?.port, 3081)
        XCTAssertEqual(NodeForwarding.hostPort(url("ws://192.168.1.24:3081/ws/mobile"))?.host, "192.168.1.24")
        XCTAssertEqual(NodeForwarding.hostPort(url("ws://mac-mini.example/ws/mobile"))?.port, 80)
        // IPv6 字面量：host 不带方括号交给转发器。
        XCTAssertEqual(NodeForwarding.hostPort(url("ws://[::1]:3081/x"))?.host, "::1")
        XCTAssertNil(NodeForwarding.hostPort(url("ws:///x")))
    }

    func testOnlyPlainEndpointsAreCarried() {
        XCTAssertTrue(NodeForwarding.canCarry(url("ws://192.168.1.24:3081/ws/mobile")))
        // wss 走不了裸转发器：明确不支持，而不是让它连上后以更难懂的方式失败。
        XCTAssertFalse(NodeForwarding.canCarry(url("wss://gateway.example.ts.net:8443/ws/mobile")))
        XCTAssertFalse(NodeForwarding.canCarry(url("https://host/ws/mobile")))
        XCTAssertFalse(NodeForwarding.canCarry(url("ws://:3081/x")))
    }

    func testZeroLocalPortIsRejected() {
        // 绑定失败时 Go 侧可能回 0；拿 0 去连等于连到不存在的入口。
        XCTAssertNil(NodeForwarding.connectURL(for: url("ws://192.168.1.24:3081/x"), localPort: 0))
    }
}
