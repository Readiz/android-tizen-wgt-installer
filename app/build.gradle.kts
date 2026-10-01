plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.readiz.wgtinstaller"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.readiz.wgtinstaller"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.3.9-experimental"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    lint { abortOnError = true }
    bundle { language { enableSplit = false } }
}
dependencies {
    implementation(project(":core"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
