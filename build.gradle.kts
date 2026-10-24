plugins {
    java
}

group = "com.taketori"

// 版本号的唯一来源是 gradle.properties 的 version=…
// plugin.yml 的 ${version} 占位符与产物文件名都由它自动同步，避免多处各写一遍漏改。
version = providers.gradleProperty("version").getOrElse("0.0.0-dev")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 1.21.4 的 API 坐标沿用「主.次.修订-R0.1-SNAPSHOT」的老格式。
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Paper 1.21.4 的运行时与 API 都是 Java 21（class 文件 major 65）。
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val pluginVersion = version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// 兼容护栏（计划 §5.6）：core 层必须是纯 Java，禁止触碰 Bukkit / Paper / NMS。
// 违反即构建失败，这样"升级只改适配层"才是可验证的承诺而不是口号。
// ---------------------------------------------------------------------------
val checkCorePurity by tasks.registering {
    group = "verification"
    description = "core/ 层禁止引用 org.bukkit / io.papermc / net.minecraft / craftbukkit"

    val coreDir = layout.projectDirectory.dir("src/main/java/com/taketori/kassen/core").asFile
    val banned = listOf("org.bukkit", "io.papermc", "net.minecraft", "craftbukkit")

    doLast {
        if (!coreDir.exists()) return@doLast
        val offenders = coreDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> banned.any { line.contains(it) } }
                    .map { (i, line) -> "${file.relativeTo(coreDir)}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        if (offenders.isNotEmpty()) {
            throw GradleException("core 层出现版本相关依赖，请下沉到 paper/ 或 version/ 包：\n" + offenders.joinToString("\n"))
        }
        logger.lifecycle("checkCorePurity: core/ 层无 Bukkit/NMS 引用 ✓")
    }
}

tasks.named("check") { dependsOn(checkCorePurity) }
tasks.named("build") { dependsOn(checkCorePurity) }

// ---------------------------------------------------------------------------
// 参数键护栏：技能实现读取的参数键必须被 ConfigValidator 收录。
// 否则玩家在 weapons.yml 里改了某个键，代码读的却是另一个键 —— 配置静默失效且无任何报错。
// ---------------------------------------------------------------------------
val checkParamKeys by tasks.registering {
    group = "verification"
    description = "技能读取的参数键必须被 ConfigValidator 的 ALLOWED_PARAMS 收录"

    val implDir = layout.projectDirectory.dir("src/main/java/com/taketori/kassen/paper/skill/impl").asFile
    val validatorFile = layout.projectDirectory.file("src/main/java/com/taketori/kassen/config/ConfigValidator.java").asFile
    val combatFile = layout.projectDirectory.file("src/main/java/com/taketori/kassen/paper/listener/CombatListener.java").asFile

    doLast {
        val allowed = Regex("""Map\.entry\("([a-z_]+)",\s*Set\.of\(([^)]*)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(validatorFile.readText())
            .associate { match ->
                match.groupValues[1] to Regex("""\"([a-zA-Z0-9\-]+)\"""")
                    .findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
            }
        val problems = mutableListOf<String>()

        implDir.listFiles { file -> file.extension == "java" }?.forEach { file ->
            val text = file.readText()
            val type = Regex("""String\s+type\(\)\s*\{\s*return\s+"([a-z_]+)"""")
                .find(text)?.groupValues?.get(1) ?: return@forEach
            val keys = Regex("""(?:context|def)\.(?:dbl|integer|bool|str)\("([a-zA-Z0-9\-]+)"""")
                .findAll(text).map { it.groupValues[1] }.toSet()
            val allowedKeys = allowed[type]
            if (allowedKeys == null) {
                problems += "${file.name}: 类型 $type 未在 ConfigValidator 中登记"
            } else {
                keys.filterNot { it in allowedKeys }
                    .forEach { problems += "${file.name} (type=$type): 读取参数 $it，但校验表未收录" }
            }
        }

        Regex("""\bdef\.(?:dbl|integer|bool|str)\("([a-zA-Z0-9\-]+)"""")
            .findAll(combatFile.readText())
            .map { it.groupValues[1] }
            .filterNot { it in allowed["special_shot_toggle"].orEmpty() }
            .forEach { problems += "CombatListener: 读取参数 $it，但 special_shot_toggle 未收录" }

        if (problems.isNotEmpty()) {
            throw GradleException("技能参数键与校验表不一致（配置会静默失效）：\n" + problems.joinToString("\n"))
        }
        logger.lifecycle("checkParamKeys: 技能读取的参数键与校验表一致 ✓")
    }
}

tasks.named("check") { dependsOn(checkParamKeys) }

// ---------------------------------------------------------------------------
// 离线验证程序（tools/）：不依赖运行中的服务器——参数解析、MiniMessage 按钮、
// 多世界作用域、weapons.yml 健全性、YAML 编辑器回归。
// 接入构建后可用 ./gradlew offlineCheck 一键运行，避免它们沦为无人编译的死代码。
//
// 注意：java { toolchain } 只作用于 java 插件自建的 compileJava 等任务，这里手写的
// JavaCompile / JavaExec 不受其管辖，会各自退回 Gradle 守护进程所在的 JDK。守护进程
// 未必是 21，届时要么编译报「不支持发行版本 21」，要么运行报 UnsupportedClassVersionError。
// 因此下面三处都必须显式绑定同一套 Java 21 工具链。
// ---------------------------------------------------------------------------
val java21Compiler = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val java21Launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

val compileOfflineTools by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "编译 tools/ 下的离线验证程序"
    dependsOn(tasks.compileJava)
    source = fileTree("tools") { include("**/*.java") }
    classpath = files(sourceSets.main.get().output, configurations.compileClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("classes/offline-tools"))
    javaCompiler.set(java21Compiler)
    options.encoding = "UTF-8"
    options.release.set(21)
}

data class OfflineTool(val mainClass: String, val taskName: String, val arguments: List<String>)

val offlineTools = listOf(
    OfflineTool("BaseArgParserTest", "offlineBaseArgParser", emptyList()),
    OfflineTool("MiniMessageClickTest", "offlineMiniMessageClick", emptyList()),
    OfflineTool("WeaponYamlCheck", "offlineWeaponYamlCheck", listOf("src/main/resources")),
    OfflineTool("WeaponYamlEditorTest", "offlineWeaponYamlEditor", emptyList()),
    OfflineTool("LegacyConfigFallbackTest", "offlineLegacyConfigFallback", emptyList()),
    OfflineTool("WorldScopeTest", "offlineWorldScope", emptyList())
)

val offlineCheck by tasks.registering {
    group = "verification"
    description = "运行 tools/ 下全部离线验证程序"
}

offlineTools.forEach { tool ->
    val execTask = tasks.register<JavaExec>(tool.taskName) {
        group = "verification"
        description = "运行离线验证：${tool.mainClass}"
        dependsOn(compileOfflineTools, tasks.classes)
        mainClass.set(tool.mainClass)
        javaLauncher.set(java21Launcher)
        classpath = files(
            layout.buildDirectory.dir("classes/offline-tools"),
            sourceSets.main.get().output,
            configurations.compileClasspath
        )
        workingDir = layout.projectDirectory.asFile
        if (tool.arguments.isNotEmpty()) {
            args(tool.arguments)
        }
    }
    offlineCheck { dependsOn(execTask) }
}

tasks.named("check") { dependsOn(checkParamKeys) }
tasks.named("build") { dependsOn(checkParamKeys) }
// 离线验证随 build 自动运行，防止 tools/ 沦为无人编译的死代码
tasks.named("check") { dependsOn(offlineCheck) }
