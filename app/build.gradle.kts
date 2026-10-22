import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 动态探测本机 Android SDK 中已安装的最高 API 平台。
 * 优先使用 Android 17 (API 37)，若未安装则回退到 36 / 35 / 34 / 33。
 */
fun detectHighestInstalledApi(): Int {
    val candidates = mutableListOf<String?>()
    candidates += System.getenv("ANDROID_HOME")
    candidates += System.getenv("ANDROID_SDK_ROOT")
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        runCatching {
            val props = Properties()
            localProps.inputStream().use { props.load(it) }
            candidates += props.getProperty("sdk.dir")
        }
    }
    val platformRoots = candidates.filterNotNull()
        .map { File(it, "platforms") }
        .filter { it.isDirectory }
    val installed = platformRoots.flatMap { root ->
        root.listFiles { file -> file.isDirectory && file.name.startsWith("android-") }
            ?.mapNotNull { file ->
                file.name.removePrefix("android-").toIntOrNull()
            } ?: emptyList()
    }
    val highest = installed.maxOrNull() ?: 36
    // 低于 34 的本地 SDK 无法编译当前依赖，强制使用 36 作为回退。
    return if (highest < 34) 36 else highest
}

val detectedCompileSdk = detectHighestInstalledApi()

android {
    namespace = "com.singflow.android"
    compileSdk = detectedCompileSdk

    defaultConfig {
        applicationId = "com.singflow.android"
        minSdk = 33
        targetSdk = detectedCompileSdk
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // 单语言（简体中文）极限瘦身：剔除 AndroidX / Material3 附带的 80+ 种语言资源。
        resourceConfigurations += listOf("zh", "zh-rCN")
    }


    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 个人自用：release 复用调试签名以便直接安装；对外发布时替换为正式 keystore。
            signingConfig = signingConfigs.getByName("debug")
            // 仅保留真机架构，去掉模拟器用的 x86_64 内核（单份约 80 MB）。
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 16 KB 页大小红线：禁止 legacy 解压打包，保证 .so 以 16 KB 对齐装入 APK。
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/DebugProbesKt.bin"
            excludes += "/kotlin-tooling-metadata.json"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "libs")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
