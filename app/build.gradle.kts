import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val libcoreDir = rootProject.layout.projectDirectory.dir("libcore")
val libcoreSources = fileTree(libcoreDir) {
    include("**/*.go")
}
val generatedLibcoreAar = libcoreDir.file("libcore.aar")
val generatedLibcoreSourcesJar = libcoreDir.file("libcore-sources.jar")
val packagedLibcoreAar = layout.projectDirectory.file("libs/libcore.aar")

fun requireEnvironmentForLibcoreBuild() {
    val hasAndroidSdk =
        providers.environmentVariable("ANDROID_HOME").orNull?.isNotBlank() == true ||
            providers.environmentVariable("ANDROID_SDK_ROOT").orNull?.isNotBlank() == true
    val hasAndroidNdk =
        providers.environmentVariable("ANDROID_NDK_HOME").orNull?.isNotBlank() == true ||
            providers.environmentVariable("ANDROID_NDK_ROOT").orNull?.isNotBlank() == true

    if (!hasAndroidSdk) {
        throw GradleException("Missing Android SDK environment. Set ANDROID_HOME or ANDROID_SDK_ROOT before running Gradle.")
    }
    if (!hasAndroidNdk) {
        throw GradleException("Missing Android NDK environment. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT before running Gradle.")
    }
}

val tidyLibcore by tasks.registering(Exec::class) {
    group = "build"
    description = "Runs go mod tidy for libcore."
    workingDir = libcoreDir.asFile
    commandLine("go", "mod", "tidy")
    inputs.files(libcoreDir.file("go.mod"), libcoreDir.file("go.sum"))
    inputs.files(libcoreSources)
    outputs.file(libcoreDir.file("go.sum"))
}

val bindLibcore by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libcore.aar with gomobile using externally provided environment variables."
    dependsOn(tidyLibcore)
    workingDir = libcoreDir.asFile
    commandLine(
        "gomobile",
        "bind",
        "-androidapi",
        "21",
        "-trimpath",
        "-ldflags=-s -w",
        "-tags=with_quic,with_wireguard,with_utls",
        "-v",
        "."
    )
    inputs.files(libcoreDir.file("go.mod"), libcoreDir.file("go.sum"))
    inputs.files(libcoreSources)
    outputs.file(generatedLibcoreAar)
    doFirst {
        requireEnvironmentForLibcoreBuild()
    }
    doLast {
        delete(generatedLibcoreSourcesJar)
    }
}

val syncLibcoreAar by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the generated libcore.aar into app/libs."
    dependsOn(bindLibcore)
    from(generatedLibcoreAar)
    into(layout.projectDirectory.dir("libs"))
    rename { "libcore.aar" }
    outputs.file(packagedLibcoreAar)
}

tasks.register("buildLibcore") {
    group = "build"
    description = "Builds and syncs libcore.aar for the app."
    dependsOn(syncLibcoreAar)
}

android {
    namespace = "net.iovxw.pwap"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "net.iovxw.pwap"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.zxing.lite)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.named("preBuild") {
    dependsOn(syncLibcoreAar)
}
