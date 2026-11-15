import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// UI-free app logic shared by `:tv` (and later `:mobile`): data layer, ViewModels, UiText, navigation contracts,
// the string/drawable catalog both apps read. Screens and composables stay in the apps.
android {
    namespace = "com.hometv.tv.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // kotlinx.serialization and Retrofit R8 keeps, applied through the AAR to every app.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        error += setOf("HardcodedText", "SetTextI18n")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnitPlatform()
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // StringsCatalog: the library's own catalog and R class.
            it.systemProperty("hometv.catalogs", file("src/main/res/values/strings.xml").absolutePath)
            it.systemProperty("hometv.rClasses", "com.hometv.tv.shared.R")
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest") || name.contains("AndroidTest")) {
        compilerOptions.optIn.addAll("kotlinx.coroutines.ExperimentalCoroutinesApi", "androidx.compose.ui.test.ExperimentalTestApi")
    }
}

dependencies {
    api(project(":playback"))
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.datastore.preferences)
    api(libs.coil.compose)
    api(libs.kotlinx.coroutines.android)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":testing"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
