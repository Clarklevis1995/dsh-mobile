plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// ---- 内嵌 Tailscale（iOS）：把 ./tsnetgo 用 gomobile 编成 Tsnetgo.xcframework ----
// 与 androidApp 里的 buildTsnetAar 同源、同一个 Go 包，只是产物换成 Apple 的 xcframework。
// iOS 需要它的原因：16.x 没有给 URLSession 配代理的 API，所以经节点的那条 WebSocket 由
// Swift 自己对着 StartProxy 的本地 CONNECT 代理说——节点与代理都在这个包里。
//
// 解析规则照抄 Android 任务：工具链只在这个任务真要执行时解析，配置期不求值也不抛错，
// 否则任何 Gradle 调用（包括 Xcode 里与本功能无关的构建）都会被本机缺 Go/NDK 挡住。
// doFirst 里只允许引用本 lambda 的局部变量：捕获脚本级对象会被配置缓存拒绝
// （cannot serialize Gradle task object references）。
val tsnetAppleDir = rootProject.file("tsnetgo")
val buildTsnetXcframework = tasks.register<Exec>("buildTsnetXcframework") {
    group = "build"
    description = "gomobile bind ./tsnetgo -> Tsnetgo.xcframework（需要 Go 与 Xcode）"
    val outDir = File(tsnetAppleDir, "build/tsnetgo")
    val xcframework = File(outDir, "Tsnetgo.xcframework")
    val goBin: File = System.getenv("GOPATH")?.let { File(it, "bin") }
        ?: File(System.getProperty("user.home"), "go/bin")
    val gomobile: String? = listOf(
        File(goBin, "gomobile"),
        File("/opt/homebrew/bin/gomobile"),
        File("/usr/local/bin/gomobile")
    ).firstOrNull { it.canExecute() }?.absolutePath
    inputs.dir(tsnetAppleDir)
    outputs.dir(outDir)
    workingDir = tsnetAppleDir
    doFirst {
        val tool = gomobile ?: throw GradleException(
            "未找到 gomobile：go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init"
        )
        // gomobile 不会自建输出目录。
        outDir.mkdirs()
        environment("PATH", listOf(goBin.absolutePath, System.getenv("PATH").orEmpty())
            .filter(String::isNotBlank).joinToString(":"))
        // ios + iossimulator 一次产出同一个 xcframework 的两个切片，Xcode 自己按 sdk 选。
        // 不传 -iosversion：gomobile 的默认最低版本是 13.0，比 app 的 16.1 更低，链接照常通过
        // （实测过在 -mios-version-min=16.1 下链接成功）。
        val args = mutableListOf(tool, "bind", "-target=ios,iossimulator")
        args += listOf("-o", "build/tsnetgo/Tsnetgo.xcframework", ".")
        commandLine(args)
    }
}
