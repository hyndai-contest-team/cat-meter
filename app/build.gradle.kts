plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.catmeter.app"
    compileSdk = 37
    // AAOS 표준 Car API (차속 PERF_VEHICLE_SPEED, 주행 제한 CarUxRestrictions). 기기에 있으므로 컴파일에만 쓴다.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.catmeter.app"
        minSdk = 28           // Pleos Connect 배포 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"

        // 탑승 등록 서버 (server/). 에뮬레이터에서 10.0.2.2 는 이 컴퓨터를 가리킨다.
        // 다른 주소를 쓰려면: ./gradlew installDebug -PboardingServerUrl=https://...
        val boardingServerUrl = (project.findProperty("boardingServerUrl") as String?) ?: "http://10.0.2.2:8787"
        buildConfigField("String", "BOARDING_SERVER_URL", "\"$boardingServerUrl\"")
    }

    buildTypes {
        debug {
            // Pleos Connect 에뮬레이터 v2.1.0 은 Virtual Console 의 좌석 점유와 SDK 값이 반대로 들어온다 (README 좌석 실험)
            buildConfigField("boolean", "SEAT_OCCUPANCY_INVERTED", "true")
        }
        release {
            isMinifyEnabled = false
            // 실차 값 방향은 확인 전. 확인되면 맞춘다.
            buildConfigField("boolean", "SEAT_OCCUPANCY_INVERTED", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    val filamentVersion = "1.77.3"

    implementation(
        "com.google.android.filament:filament-android:$filamentVersion"
    )
    implementation(
        "com.google.android.filament:gltfio-android:$filamentVersion"
    )
    implementation(
        "com.google.android.filament:filament-utils-android:$filamentVersion"
    )
}
