plugins {
    java
}

val apiSrc = file("../mikuvanish-api/src/main/java")

sourceSets {
    main {
        java.srcDir(apiSrc)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("com.github.NEZNAMY:TAB-API:6.1.2")
}

val pluginVersion = project.version.toString()

// 产物名与插件名保持大小写一致（与 MikuMsg/MikuAuth 的命名风格统一），
// 并直接输出到项目根目录，方便取用。
tasks.named<Jar>("jar") {
    archiveBaseName.set("MikuVanish-Paper")
    destinationDirectory.set(rootProject.layout.projectDirectory)
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}
