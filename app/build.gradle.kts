plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    //id("com.android.application")
    id("com.google.gms.google-services")

}

android {
    namespace = "com.example.facedetectionapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.facedetectionapp"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // --- 1. 카메라 (CameraX) ---
    val cameraxVersion = "1.3.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

// --- 2. 아이콘 (Extended Icons) ---
    implementation("androidx.compose.material:material-icons-extended:1.5.4")

// --- 3. 얼굴 탐지 (ML Kit) ---
    implementation("com.google.mlkit:face-detection:16.1.5")

// --- 4. 얼굴 식별 (TensorFlow Lite) ---
    implementation("org.tensorflow:tensorflow-lite:2.16.1") // gpu-delegate-plugin에 포함되어 있음
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1") // <-- 이 줄을 삭제하세요.
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

// --- 5. 로컬 DB (Room) ---
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion") // Kotlin Extensions
    ksp("androidx.room:room-compiler:$roomVersion") // Annotation Processor

    // --- 6. 데이터 변환 (Gson) ---
    // 설명: 안면 인식 데이터(FloatArray 리스트)를 DB에 문자열로 저장하기 위해 필수
    implementation("com.google.code.gson:gson:2.10.1")

    // --- 7. 화면 이동 (Navigation Compose) ---
    // 설명: [홈(리스트)] <-> [카메라(등록)] 화면을 왔다 갔다 하기 위해 필수
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))

    implementation("com.google.firebase:firebase-analytics")

    // 8. API 통신용 (Retrofit & Gson)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // ★ Meta Wearables DAT SDK (2026년 기준 최신 0.5.0 버전)
    implementation("com.meta.wearable:mwdat-core:0.5.0")
    implementation("com.meta.wearable:mwdat-camera:0.5.0")


}