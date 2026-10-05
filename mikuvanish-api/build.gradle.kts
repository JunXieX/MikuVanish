plugins {
    java
}

// 共享模块：仅使用 JDK，无外部依赖。
// 通过各平台模块的 sourceSets.srcDir 直接并入编译，避免运行时跨 jar 依赖。

// 产物名与插件名保持大小写一致（与 MikuMsg/MikuAuth 的命名风格统一），
// 并直接输出到项目根目录，与 paper/velocity 产物一致，便于 CI 归档与发布。
tasks.named<Jar>("jar") {
    archiveBaseName.set("MikuVanish-API")
    destinationDirectory.set(rootProject.layout.projectDirectory)
}
