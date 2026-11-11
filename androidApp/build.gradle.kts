import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.clarklevis.dsh.android"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.clarklevis.dsh.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 40
        versionName = "1.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 与已安装的正式版共存，用于同一台真机上的 A/B 对照（不必每次替换安装、重新配对）。
            // namespace 不变；manifest 里的 ${applicationId}（FileProvider 权限）会自动跟随。
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    sourceSets.getByName("main").assets.srcDir(rootProject.file("AppRelease"))

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// ---- 内嵌 Tailscale：把 ./tsnetgo 用 gomobile 编成本地 AAR ----
// 需要 Go 与 Android NDK。产物落在 tsnetgo/build/tsnetgo.aar（被 .gitignore 的 build/ 规则排除）。
// 工具链在配置期解析：这个依赖是必需的，缺了就直接给出可操作的报错，而不是一个难懂的编译失败。
// 全部取 String，是为了不与配置缓存的序列化限制纠缠（闭包捕获脚本对象会被拒绝）。
val tsnetSrcDir = rootProject.file("tsnetgo")
val tsnetAarFile = File(tsnetSrcDir, "build/tsnetgo/tsnetgo.aar")
// gomobile 不会自建输出目录。
File(tsnetSrcDir, "build/tsnetgo").mkdirs()

// 接口枚举的三种取法（见 tsnetgo/getter_*.go）。默认空 = 不加任何 getter，
// 在 Android 上会失败，用来确认失败点没有变化。
val buildTsnetAar = tasks.register<Exec>("buildTsnetAar") {
    group = "build"
    description = "gomobile bind ./tsnetgo -> AAR（需要 Go 与 Android NDK）"
    val apiLevel = 24 // 必须 <= android.defaultConfig.minSdk，否则低版本设备 UnsatisfiedLinkError
    // AGP 未能自动裁剪 Go 的共享库；由 Go 链接器去除 DWARF 和普通符号表，保留 JNI 动态导出。
    val goLinkerFlags = "-s -w"
    val goEnvironment = rootProject.file("scripts/with_go_toolchain.sh")
    // 只在这个任务被配置时求值，并且**不在配置期抛错**：之前抛错让任何 Gradle 调用
    // （包括 Xcode 的 "Build KMP Framework" 阶段）都要求本机有 Android SDK，iOS 构建被无端挡住。
    // 这里用局部变量而不是脚本级属性：doFirst 捕获局部值才符合配置缓存的要求。
    val sdkProps = Properties()
    val sdkLocalFile = rootProject.file("local.properties")
    if (sdkLocalFile.exists()) sdkLocalFile.inputStream().use { sdkProps.load(it) }
    val sdkDir: File? = listOfNotNull(
        sdkProps.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { File(it) },
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) },
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { File(it) }
    ).firstOrNull { it.isDirectory }
    val ndkDir: File? = sdkDir?.let { File(it, "ndk").listFiles()?.filter { f -> f.isDirectory }?.maxByOrNull { f -> f.name } }
    val goBin: File = System.getenv("GOPATH")?.let { File(it, "bin") }
        ?: File(System.getProperty("user.home"), "go/bin")
    val gomobile: String? = listOf(
        File(goBin, "gomobile"),
        File("/opt/homebrew/bin/gomobile"),
        File("/usr/local/bin/gomobile")
    ).firstOrNull { it.canExecute() }?.absolutePath
    inputs.files(
        fileTree(tsnetSrcDir) {
            include("**/*.go", "go.mod", "go.sum")
            exclude("build/**")
        }
    )
    inputs.file(goEnvironment)
    inputs.property("goLinkerFlags", goLinkerFlags)
    outputs.files(tsnetAarFile, File(tsnetSrcDir, "build/tsnetgo/tsnetgo-sources.jar"))
    workingDir = tsnetSrcDir
    doFirst {
        val sdk = sdkDir ?: throw GradleException(
            "找不到 Android SDK：设置 ANDROID_HOME，或在 local.properties 写 sdk.dir"
        )
        val ndk = ndkDir ?: throw GradleException(
            "未找到 NDK：${File(sdk, "ndk")} —— 先执行 sdkmanager \"ndk;27.0.12077973\""
        )
        val tool = gomobile ?: throw GradleException(
            "未找到 gomobile：go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init"
        )
        environment("ANDROID_HOME", sdk.absolutePath)
        environment("ANDROID_SDK_ROOT", sdk.absolutePath)
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        environment("NDK_HOME", ndk.absolutePath)
        // 16 KB 页对齐：Android 15+ 的新内核与 Google Play 对 targetSdk≥35 都要求 ELF 段按 16 KB 对齐。
        // 实测证据：不加这行时 libgojni.so 的 LOAD 对齐是 0x1000（真机上 HyperOS 会弹「Android 应用兼容性」警告），
        // 加上后是 0x4000，且 16 KB 对齐的库在 4 KB 页设备上照常加载。
        environment("CGO_LDFLAGS", "-Wl,-z,max-page-size=16384")
        environment(
            "PATH",
            listOf(goBin.absolutePath, System.getenv("PATH").orEmpty())
                .filter(String::isNotBlank).joinToString(":")
        )
        val args = mutableListOf(tool, "bind", "-target=android", "-androidapi", apiLevel.toString())
        args += listOf("-ldflags", goLinkerFlags)
        args += listOf("-o", "build/tsnetgo/tsnetgo.aar", ".")
        commandLine(listOf("/bin/sh", goEnvironment.absolutePath) + args)
    }
}

// 本地化守卫：中英文目录、格式参数，以及 iOS / widget / Android / shared 中未本地化的中文字面量。
// 挂在单元测试前，任何新增的硬编码中文都会让 testDebugUnitTest 失败。
val verifyLocalizations = tasks.register<Exec>("verifyLocalizations") {
    group = "verification"
    description = "python3 scripts/verify-localizations.py"
    // 无声明输出：每次都运行（约 2 秒），避免漏掉 iOS / shared 源文件的变化。
    workingDir = rootProject.projectDir
    commandLine("python3", rootProject.file("scripts/verify-localizations.py").absolutePath)
}
tasks.withType<Test>().configureEach { dependsOn(verifyLocalizations) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":shared"))
    // 内嵌 tailscale 节点（Go）。生成的 Java 在包 `tsnetgo` 下，接口只有 string/int。
    implementation(files(tsnetAarFile).builtBy(buildTsnetAar))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation(libs.backdrop)
    implementation(libs.markwon.core)
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.android.svg)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // 避免旧 Espresso 反射已移除的 InputManager.getInstance，兼容新模拟器。
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
