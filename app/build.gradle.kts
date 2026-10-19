import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 签名配置。
 *
 * 覆盖安装的第一前提是签名一致：签名换了就只能卸载重装（权限、设置全丢）。
 * 本地读 keystore.properties，CI 读环境变量；两者都没有时回落到 AGP 自动生成的 debug 签名。
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propKey)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("storeFile", "VA_KEYSTORE_PATH")

android {
    namespace = "com.example.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.voiceassistant"
        minSdk = 26
        targetSdk = 34
        // 分钟级时间戳：每次构建自动递增，覆盖安装/更新检查都靠它判断新旧
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
        // 展示用的版本名：CI 传 -PversionName=0.7.<run_number>，本地构建保持默认值
        versionName = (project.findProperty("versionName") as String?)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "0.7.0"

        // 更新源仓库，形如 "owner/repo"。CI 用 -PupdateRepo=${{ github.repository }} 自动注入；
        // 本地不传则为空，App 里会提示「未配置更新源」，不影响其它功能。
        val updateRepo = (project.findProperty("updateRepo") as String?)?.trim().orEmpty()
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = signingValue("storePassword", "VA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 自用侧载：先不混淆，便于看堆栈
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配签名就用 debug 签名，保证本地随手构建出来的包也能装
            signingConfig = if (releaseStorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8 默认关闭，用 buildConfigField 暴露更新源仓库必须打开
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // 本地存储（模块 8 会再加 Room）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 模块 9 用；骨架阶段仅声明
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
