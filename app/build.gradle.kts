plugins {
    id("com.android.application")
}

android {
    namespace = "com.phonewebmcp.gu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonewebmcp.gu"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // 保持与 release 相同的签名配置（默认使用 debug keystore）
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 零第三方依赖：纯系统 API（android.app.Activity + org.json + WebView）
}