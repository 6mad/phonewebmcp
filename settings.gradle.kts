pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 中国区镜像（海外 CI 上官方源优先，本地/国内网络用镜像加速）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "PhoneWebMCP"
include(":app")
