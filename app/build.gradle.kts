plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val freeVersionCode = providers.gradleProperty("freeVersionCode").get().toInt()
val freeVersionName = providers.gradleProperty("freeVersionName").get()

android {
    namespace = "io.github.hoerire.free"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.hoerire.free"
        minSdk = 35
        targetSdk = 37
        versionCode = freeVersionCode
        versionName = freeVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "debug") "-Debug" else ""
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { version -> "Free-v$version$suffix.apk" },
            )
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    compileOnly(libs.libxposed.api)
    // framework @hide 类的编译期占位（android.view.InputChannel / InputEventReceiver）。
    // 只参与编译，绝不进 dex，否则运行时会和 bootclasspath 的真实类撞名。
    compileOnly(files(rootProject.file("libs/hidden-stubs.jar")))
    // 设置页运行在系统设置进程里，androidx 的偏好/碎片类由设置 APK 提供，
    // 因此同样只取编译期签名（`libs/*-classes.jar` 从官方 AAR 中解出）。
    compileOnly(files(rootProject.file("libs/preference-classes.jar")))
    compileOnly(files(rootProject.file("libs/fragment-classes.jar")))
    implementation(libs.dexkit)
    implementation(libs.libxposed.service)
    implementation(libs.glasmio.core)
    implementation(libs.glasmio.glass)
    implementation(libs.glasmio.ui)
    implementation(libs.glasmio.scaffold)
    implementation(libs.glasmio.preference)
    implementation(libs.glasmio.icons)
    testImplementation(libs.junit)
}
