import Foundation

enum PairingPayloadParser {
    static func parse(_ rawValue: String, now: Date = .now) throws -> GatewayPairingPayload {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = decodeStrictBase64URL(trimmed) else {
            throw PairingPayloadError.invalidBase64URL
        }

        let payload: GatewayPairingPayload
        do {
            payload = try JSONDecoder().decode(GatewayPairingPayload.self, from: data)
        } catch {
            throw PairingPayloadError.invalidJSON
        }

        guard payload.version == 2 else {
            throw PairingPayloadError.unsupportedVersion(payload.version)
        }
        guard let url = payload.endpoint,
              ["ws", "wss"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else {
            throw PairingPayloadError.invalidEndpoint
        }
        guard !payload.pairingCode.isEmpty,
              !payload.pairingCode.contains(","),
              payload.pairingCode.unicodeScalars.allSatisfy({
                  !CharacterSet.whitespacesAndNewlines.contains($0) &&
                  !CharacterSet.controlCharacters.contains($0)
              }) else {
            throw PairingPayloadError.invalidCode
        }
        guard payload.expirationDate > now else {
            throw PairingPayloadError.expired
        }
        return payload
    }

    private static func decodeStrictBase64URL(_ value: String) -> Data? {
        guard !value.isEmpty,
              !value.contains("="),
              value.unicodeScalars.allSatisfy({ scalar in
                  CharacterSet.alphanumerics.contains(scalar) || scalar == "-" || scalar == "_"
              }),
              value.count % 4 != 1 else { return nil }
        var base64 = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        return Data(base64Encoded: base64, options: [])
    }
}

enum PairingPayloadError: LocalizedError, Equatable {
    case invalidBase64URL
    case invalidJSON
    case unsupportedVersion(Int)
    case invalidEndpoint
    case invalidCode
    case expired

    var errorDescription: String? {
        switch self {
        case .invalidBase64URL:
            String(localized: "配对内容不是有效的 Base64URL 字符串。")
        case .invalidJSON:
            String(localized: "Base64URL 解码后的内容不是有效的 DeepSeek Harness 配对 JSON。")
        case .unsupportedVersion(let version):
            String(localized: "pairing.unsupported.version", defaultValue: "不支持的配对协议版本 \(version)，当前客户端需要 version 2。")
        case .invalidEndpoint:
            String(localized: "二维码中的 publicUrl 不是有效的 WebSocket 地址。")
        case .invalidCode:
            String(localized: "二维码中的一次性 pairingCode 无效。")
        case .expired:
            String(localized: "二维码配对码已经过期，请在 WebUI 中重新生成。")
        }
    }
}
