plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "it.allard.etincelle.core.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.exoplayer.dash)
    api(libs.androidx.media3.ui)

    testImplementation(libs.junit)
}
