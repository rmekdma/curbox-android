plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "neth.iecal.todo"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
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

