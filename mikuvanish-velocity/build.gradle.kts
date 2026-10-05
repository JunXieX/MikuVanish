plugins {
    java
}

val apiSrc = file("../mikuvanish-api/src/main/java")

// 版本号单一来源：由 Gradle 依 gradle.properties 生成 BuildInfo（其字段为字面量常量，
// 可被 @Plugin(version = ...) 直接引用），避免注解里的版本号与 Gradle 版本号两处漂移。
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildinfo/java")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("dev/junxiex/mikuvanish/velocity")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildInfo.java").writeText(
            """
            package dev.junxiex.mikuvanish.velocity;

            /** 由 Gradle 依据 gradle.properties 生成，请勿手改。 */
            final class BuildInfo {
                static final String VERSION = "$ver";

                private BuildInfo() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets {
    main {
        java.srcDir(apiSrc)
        java.srcDir(generateBuildInfo)
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
}

// 产物名与插件名保持大小写一致（与 MikuMsg/MikuAuth 的命名风格统一），
// 并直接输出到项目根目录，方便取用。
tasks.named<Jar>("jar") {
    archiveBaseName.set("MikuVanish-Velocity")
    destinationDirectory.set(rootProject.layout.projectDirectory)
}