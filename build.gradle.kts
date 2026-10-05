plugins {
    java
}

allprojects {
    // 分组与版本号单一来源：gradle.properties（此处不再重复写死数值，避免多处不同步）。
    // 属性缺失时会直接构建失败，不会静默产出 "unspecified" 版本。
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
