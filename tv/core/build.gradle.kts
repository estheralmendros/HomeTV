import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    // Test fakes shared with :app tests (FakeHomeTvApi, TestServer, Fixtures, F, UsLocale).
    testFixturesApi(libs.okhttp.mockwebserver)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)
    testFixturesImplementation(libs.retrofit.converter.kotlinx.serialization)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

tasks.test {
    useJUnitPlatform()
    // BrandAssetsTest checks the app's banner and launcher icon on a plain JVM (javax.imageio); the path
    // comes from Gradle, not from the working directory, and the files are inputs for up-to-date checks.
    val appRes = rootProject.file("app/src/main/res")
    inputs.files(appRes.resolve("drawable-xhdpi/banner.png"), appRes.resolve("drawable/ic_launcher_foreground.xml"), appRes.resolve("values/colors.xml"))
        .withPropertyName("brandAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("hometv.appRes", appRes.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
