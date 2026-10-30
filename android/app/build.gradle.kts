import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.timeflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.timeflow"
        minSdk = 26
        targetSdk = 34
        // versionCode naik otomatis di GitHub Actions (nomor run), supaya APK baru selalu dianggap pembaruan.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "2.6.0"
    }

    buildFeatures { buildConfig = true }

    // bundled = UI dibundel di APK (offline penuh, tanpa izin INTERNET).
    // vercel  = UI dimuat dari URL Vercel milikmu: -PtimeflowUrl=https://nama.vercel.app (butuh INTERNET).
    flavorDimensions += "ui"
    productFlavors {
        create("bundled") {
            dimension = "ui"
            buildConfigField("String", "REMOTE_URL", "\"\"")
        }
        create("vercel") {
            dimension = "ui"
            // Mode website dihapus: flavor ini tetap ada supaya perintah build lama (assembleVercelDebug) tidak rusak,
            // tetapi hasilnya sekarang aplikasi penuh yang sama dengan bundled (UI di dalam APK, jalan offline dan online).
            buildConfigField("String", "REMOTE_URL", "\"\"")
        }
    }

    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Tanpa keystore.properties, APK release dihasilkan tanpa tanda tangan (tandatangani manual, lihat README).
            signingConfig = if (ksProps.isNotEmpty()) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Frontend (folder web/ di root repo) dibundel ke assets APK, sehingga UI dan alarm bekerja offline.
    sourceSets["main"].assets.srcDir("../../web")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
