plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    ohosArm64 {
        binaries.sharedLib { baseName = "whaleness" }
    }
    sourceSets {
        all { languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi") }
        commonMain {
            // 直接编译主项目的业务源码，避免维护另一套 Reducer 和协议实现。
            kotlin.srcDir("../../shared/src/commonMain/kotlin")
            // Room 仍未适配；平台使用真实 ArkData，窗口格式和网络仲裁复用共享源码。
            kotlin.exclude("**/ChatCacheDatabase.kt", "**/SharedChatHistoryCache.kt",
                "**/ChatMemoryCache.kt", "**/ChatPrefetchPolicy.kt")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:2.0.21-coroutines-KBA-001")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
            }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// 日用与性能候选默认使用优化的 Native；可显式设置 HARMONY_NATIVE_MODE=debug 调试。
val nativeMode = providers.gradleProperty("harmony.nativeMode")
    .orElse(providers.environmentVariable("HARMONY_NATIVE_MODE")).orElse("release").get()
require(nativeMode in setOf("debug", "release")) { "harmony.nativeMode 仅支持 debug 或 release" }
val publishNative by tasks.registering(Sync::class) {
    dependsOn("link${nativeMode.replaceFirstChar { it.uppercaseChar() }}SharedOhosArm64")
    from(layout.buildDirectory.dir("bin/ohosArm64/${nativeMode}Shared"))
    include("libwhaleness.so", "libwhaleness_api.h")
    into(rootProject.layout.projectDirectory.dir("../harmonyApp/entry/src/main/cpp/generated/arm64-v8a"))
}

// 复用主项目的MVI/历史/对话/高亮回归，验证Native内部通路不改变公开线协议。
val collectFacadeTests by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../shared/src/commonTest/kotlin")) {
        include("com/clarklevis/dsh/shared/SharedMviEventsTest.kt")
        include("com/clarklevis/dsh/shared/SharedHistoryStoreTest.kt")
        include("com/clarklevis/dsh/shared/SharedConversationStoreTest.kt")
        include("com/clarklevis/dsh/shared/WorkspaceCodePreviewSupportTest.kt")
    }
    into(layout.buildDirectory.dir("generated/facadeTests"))
}
kotlin.sourceSets.named("jvmTest") {
    kotlin.srcDir(collectFacadeTests.map { it.destinationDir })
}
tasks.named("compileTestKotlinJvm") { dependsOn(collectFacadeTests) }
