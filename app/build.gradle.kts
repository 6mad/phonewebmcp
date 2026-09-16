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
        /*
         * 版本规范：
         *  1) 每次产出 APK 必须递增 versionCode，绝不重复——
         *     曾出现"正式修复版"与"故意去掉修复的回归验证版"同号，
         *     导致无法从 /api/info 判断设备上到底是哪个构建。
         *  2) 回归验证版（人为破坏以测试有效性）必须在 versionName 带 "-regress"。
         *  3) 版本经 /api/info 的 appVersionName/appVersionCode 上报，
         *     客户端 `gu.py version` 可直接确认，无需靠行为差异推测。
         */
        versionCode = 10
        versionName = "1.3.5"
    }

    buildFeatures {
        // 生成 BuildConfig：/api/info 用它上报版本号，客户端据此确认新版是否真的装上
        buildConfig = true
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