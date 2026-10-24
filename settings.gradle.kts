rootProject.name = "tsukikage-kassen"

// 本机 JAVA_HOME 未必指向 JDK 21，交给 foojay 按需自动下载/定位。
// 1.0.0 是适配 Gradle 9.x 的版本：0.9.0 会因 JvmVendorSpec.IBM_SEMERU 已被移除而在配置阶段直接崩。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
