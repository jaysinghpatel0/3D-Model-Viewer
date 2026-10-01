plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.a3dmodelviewer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.a3dmodelviewer"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
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
    implementation(libs.androidx.remote.creation.core)
    implementation(libs.filament.android)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation("com.google.android.filament:filament-android:1.69.0")
    implementation("com.google.android.filament:gltfio-android:1.69.0")
}