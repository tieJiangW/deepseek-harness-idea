import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    java
    kotlin("jvm") version "2.0.21"
    // 经典插件线：2.x（org.jetbrains.intellij.platform）未发布到本网络可达的
    // Gradle 插件门户可见范围，且 DSL 与 1.x 不兼容；1.17.4 为本环境可解析的最新稳定版。
    // 升级到 2.x 作为后续改进项（见 docs/DESIGN.md §3.1）。
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.deepseek.harness"
version = "0.2.2"

repositories {
    mavenCentral()
}

val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("2024.1.7")

// 跨平台运行时构建（脚本跑在任意主机，默认取当前主机 os/arch 作为目标平台）。
// 瘦身默认（thin=true）：运行时不打进插件 jar，改为首次运行按平台下载（见 DshHomeManager/RuntimeProvisioner）。
// `-Pthin=false` 时打包一个含当前主机平台运行时的 fat zip（离线/air-gapped 备选）。
val thin: Boolean = providers.gradleProperty("thin").map { it.toBoolean() }.getOrElse(true)
val dshVersion: String = "0.1.1-rc.2"

val hostOs: String = when {
    System.getProperty("os.name").lowercase().contains("win") -> "win"
    System.getProperty("os.name").lowercase().contains("mac") || System.getProperty("os.name").lowercase().contains("darwin") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    else -> "win"
}
val hostArch: String = if (System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }) "arm64" else "x64"

// 构建期把插件版本注入生成资源（dsh-build-info.properties），供运行期读取——
// 避免在运行期读 @Internal 的 com.intellij.ide.plugins.PluginManagerCore（verifier 报 internal API usage）。
val generatedResources = layout.buildDirectory.dir("generated-resources/main")

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // JCEF 在 2026.2（build 262）起从平台核心拆分为内置插件 com.intellij.modules.jcef：
    // 其模块声明为 public 可见性，运行时无需在 plugin.xml 声明依赖即可解析类；
    // 前向编译检查（-PplatformVersion=2026.2）时把该内置插件的 lib jars 加入编译 classpath 验证 API 兼容。
    if (platformVersion.startsWith("2026")) {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME") ?: (System.getProperty("user.home") + "/.gradle")
        val sdkCache = file("$gradleUserHome/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/$platformVersion")
        compileOnly(
            fileTree(sdkCache) {
                include("*/ideaIC-$platformVersion/plugins/jcef-plugin/lib/**/*.jar")
            }
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 前向编译检查（-PplatformVersion=2026.2）时，新版平台自带的 Kotlin 模块（如 fleet.*）
        // metadata 版本高于本工程 Kotlin 2.0.21，需跳过 metadata 版本校验（仅检查我们的源码，
        // 不涉及平台内部 Kotlin 类；见 docs/PROJECT_NOTES.md §1）
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// Step 5：运行时 bundle 作为插件资源参与打包（build/plugin-runtime/，由 bundleRuntime 产出）。
// 仅 fat（-Pthin=false）时把该目录注册为资源源；瘦身插件按平台下载运行时，避免残留 bundle 误打入。
// 另注册构建期生成的版本资源目录（generateBuildInfo）。
sourceSets {
    main {
        if (!thin) resources.srcDir(layout.buildDirectory.dir("plugin-runtime"))
        resources.srcDir(generatedResources)
    }
}

intellij {
    // 目标平台：IntelliJ IDEA Community 2024.1+（与 PRD 一致）
    // 支持 -PplatformVersion=2026.2 做前向兼容编译检查（见 docs/PROJECT_NOTES.md）
    version.set(platformVersion)
    type.set("IC")
    // JCEF：2024.1 内核自带（app-client.jar）；2026.2 起为内置插件，见上方 dependencies 条件编译 classpath
    plugins.set(emptyList())
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        // 2026.2 (build 262) 起兼容范围放宽；2026-08-20 用户实测 IDEA 2026.2 安装报
        // "requires build251.* or older"，故 251.* → 262.*（含前向编译验证，见 DESIGN §3.1）
        untilBuild.set("262.*")
    }

    // 跳过 searchable options 构建（需要无头启动 IDE，CI/沙箱中不稳定）
    buildSearchableOptions {
        enabled = false
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    // Step 2：构建内嵌运行时（scripts/build-runtime.mjs；默认取当前主机平台，见 docs/DESIGN.md §3.2）
    register("buildRuntime", Exec::class) {
        description = "Build the embedded DSH runtime (Node + @deepseek-ai/dsh) into build/runtime"
        group = "build"
        val script = rootProject.file("scripts/build-runtime.mjs")
        val outputDir = rootProject.file("build/runtime")
        inputs.file(script)
        outputs.dir(outputDir)
        commandLine(
            "node", script.absolutePath,
            "--output", outputDir.absolutePath,
            "--dsh-version", dshVersion,
            "--bundle"
        )
    }

    // Step 5：运行时打入插件资源（仅 fat、`-Pthin=false` 时）。瘦身插件依赖按平台下载运行时。
    register("bundleRuntime", Copy::class) {
        description = "Package the built runtime as runtime-bundle.zip into plugin resources"
        group = "build"
        enabled = !thin
        dependsOn("buildRuntime")
        val bundle = rootProject.file("build/runtime-${hostOs}-${hostArch}.zip")
        val dest = rootProject.layout.buildDirectory.dir("plugin-runtime")
        inputs.file(bundle)
        outputs.dir(dest)
        from(bundle) { rename { "runtime-bundle.zip" } }
        into(dest)
    }

    // 构建期把项目版本写入生成资源（DshHomeManager.pluginVersion() 运行期读取，避免用 @Internal 的 PluginManagerCore）
    register("generateBuildInfo") {
        val v: String = version.toString()
        inputs.property("version", v)
        outputs.dir(generatedResources)
        doLast {
            val dir = generatedResources.get().asFile
            dir.mkdirs()
            File(dir, "dsh-build-info.properties").writeText("version=$v\n")
        }
    }

    // 打包资源时仅 fat（-Pthin=false）确保持运行时 bundle 就位；瘦身插件无需运行时打包
    processResources {
        if (!thin) dependsOn("bundleRuntime")
        dependsOn("generateBuildInfo")
    }
}
