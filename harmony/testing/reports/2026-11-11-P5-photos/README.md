# 系统相册与图片消息专项

2026-11-11，Mate 70 Pro API21 ARM64，独立 .harmony.smoke；优化 Native，隔离 F 夹具 18783/18784。

测试包仅增加导入三张四象限 EXIF 测试图的入口，实际经过系统允许保存对话框后写入媒体库。生产 PhotoViewPicker、FileController、TaskPool、ImageKit、Native 发送与 ImageCache 均不替换。断言相册恰好三张测试媒体后选择，结束按记录 URI 仅删除本轮图片。

取消后草稿保持、无附件；多选三张，预览、左右切图，移除一张，正文与两图发送，确认后清理输入及附件。服务端按实际 JPEG 字节校验尺寸和体积；随后 attachment 读取、私有缩略图生成和收到后预览通过。已人工查看系统选择器和收到后预览原图。

首两轮为系统节点探测，不计通过；最终结果见 results.json。不覆盖相机、权限撤销、真实大图端到端、缩放测量或 D。
