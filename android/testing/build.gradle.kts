import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Test-only helpers shared by `:playback` and `:tv` (fakes, scripted API, dispatcher extension, Compose test utils).
// No Robolectric: each module that runs on it declares it itself.
android {
    namespace = "com.hometv.tv.testing"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":shared"))
    api(testFixtures(project(":core")))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.junit.jupiter.params)
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.test.core)
    api(platform(libs.compose.bom))
    api(libs.compose.ui.test.junit4)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.optIn.addAll("kotlinx.coroutines.ExperimentalCoroutinesApi", "androidx.compose.ui.test.ExperimentalTestApi")
}
