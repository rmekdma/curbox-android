plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}
val learningReal = providers.gradleProperty("learningReal").map { it.toBooleanStrict() }.getOrElse(false)
if (learningReal && gradle.startParameter.taskNames.any { it.contains("test", ignoreCase = true) }) {
    error("Tests require the fake learning composition")
}

android {
    namespace = "neth.iecal.todo"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        buildConfigField("Boolean", "LEARNING_FAKE_ONLY", (!learningReal).toString())
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    sourceSets {
        if (learningReal) {
            getByName("main") { java.srcDir("src/learningReal/java") }
        } else {
            getByName("release") { java.srcDir("src/learningFake/java") }
            getByName("debug") {
                java.srcDir("src/learningFakeDebug/java")
                manifest.srcFile("src/learningFakeDebug/AndroidManifest.xml")
            }
        }
    }
}
dependencies {
    implementation(project(":plugin-contract"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation(libs.gson)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

