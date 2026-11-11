pluginManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        mavenCentral()
    }
}
rootProject.name = "whaleness-harmony"
include(":shared")
