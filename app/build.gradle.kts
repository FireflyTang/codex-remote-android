import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.firefly.codexremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.firefly.codexremote"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val mobilecoreAar = layout.projectDirectory.file("../mobilecore/build/mobilecore.aar")
val buildMobilecoreAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Regenerates MobileCore before packaging it into the app."
    workingDir(rootProject.projectDir)
    commandLine("bash", "mobilecore/build-aar.sh")
    inputs.files(
        rootProject.fileTree("mobilecore") {
            include("*.go", "go.mod", "go.sum", "build-aar.sh", "patches/**")
            exclude("build/**", ".tools/**")
        },
    )
    outputs.file(mobilecoreAar)
}
val verifyMobilecoreAar by tasks.registering {
    group = "verification"
    description = "Checks the generated MobileCore Android archive."
    dependsOn(buildMobilecoreAar)
    inputs.file(mobilecoreAar)
    doLast {
        check(mobilecoreAar.asFile.isFile) {
            "Missing ${mobilecoreAar.asFile}. Build the agreed generated AAR before :app."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyMobilecoreAar)
}

dependencies {
    implementation(files(mobilecoreAar))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
