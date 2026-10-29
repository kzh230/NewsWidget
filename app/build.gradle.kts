plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.newswidget"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.newswidget"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
// 뉴스 파싱용 Jsoup 라이브러리
    implementation("org.jsoup:jsoup:1.17.2")
    // 15분 주기 자동 갱신용 WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}