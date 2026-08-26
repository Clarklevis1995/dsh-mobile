import Foundation
import DeepSeekHarnessShared

/// SwiftUI/AppStore 与 KMP 之间的薄边界。
///
/// iOS 仍负责 ObservableObject、网络、持久化与生命周期；共享模块只通过
/// 粗粒度 facade 暴露协议解码和纯业务状态，避免 SwiftUI 依赖 Kotlin 内部类型。
struct KMPSharedAdapter {
    private let facade = SharedMobileFacade()

    var moduleSummary: String {
        facade.moduleSummary()
    }

    func decodeFrameKind(_ json: String) -> String {
        facade.decodeFrameKind(json: json)
    }

    func makeStore() -> SharedMobileStore {
        facade.makeStore()
    }
}
