# E25：共享语音桥与原生交互

限定 K／F 通过，不替代真实麦克风、真实语音插件 G 或设备 D。

- 生产实现复用 SharedVoiceInputStore，接鸿蒙按需授权、16kHz／mono／S16LE、上限、音量／计时、取消、后台释放和 TaskPool 编码。
- 5 项 JVM 核心检查通过：识别只更新草稿；转写期间编辑保留结果待确认插入；跨会话取消且拒绝迟到结果；拒绝权限按 stop＋discard 释放；高频语音返回不带整段历史。
- F 使用临时包中的明确硬件适配器：仅 permission／start／stop 替换为固定 PCM，不采集环境音；其余原生界面、长按／松手、TaskPool、Native WAV 处理、真实本机 WebSocket 协议与草稿持久化路径均为生产代码。
- 夹具实际解码并检查 WAV 格式（16kHz／mono／16bit）；识别返回前手动修改草稿，显示待插入文本，明确点击插入后合并正确，不自动发送聊天。
- 最新共享回归共 99 JVM／43 Node，优化 Native 与 HAP 构建通过。真实麦克风被占用／撤销权限／锁屏、上滑轨迹和设备专项保留待验。

复跑：`python3 harmony/testing/product_ui.py --scenario voice`。

2026-10-31 新输入栏复测同项通过，见 [最新原生交互](final-ui/README.md)，仍为固定 PCM 适配器，不计真实录音 G／D。
