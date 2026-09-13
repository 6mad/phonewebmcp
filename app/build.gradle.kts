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
        versionCode = 3
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 零第三方依赖：纯系统 API（android.app.Activity + org.json + WebView）
}