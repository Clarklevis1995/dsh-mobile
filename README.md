# DeepSeek Harness Mobile

Native SwiftUI iOS client for `dsh-plugin-mobile-gateway`.

## Run

1. Start DeepSeek Harness with the sibling `dsh-plugin-mobile-gateway` enabled.
2. Open `DeepSeekHarnessMobile.xcodeproj` and run the `DeepSeekHarnessMobile` scheme.
3. In Settings, use `ws://<your-mac-lan-ip>:3080/ws/mobile` on a physical iPhone. The simulator can use `ws://127.0.0.1:3080/ws/mobile`.

The v0.1.6 gateway provides live events, prompt admission, workspace/session lists, history, search, host metadata, directory browsing, and workspace creation. The app synchronizes those surfaces automatically after connecting.

See [`Docs/exploration.md`](Docs/exploration.md) for the Harness/Web UI feature review, protocol mapping, and current gateway limitations.
