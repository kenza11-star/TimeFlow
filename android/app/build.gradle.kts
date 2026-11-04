import java.security.KeyStore
import java.util.Base64
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
        applicationId = "com.timeflow.app"
        minSdk = 26
        targetSdk = 34
        // versionCode naik otomatis di GitHub Actions (nomor run), supaya APK baru selalu dianggap pembaruan.
        // Selalu naik, dari mana pun APK dibangun (GitHub Actions, Termux, repo baru dengan nomor run mulai dari 1): menit sejak 1970.
        // Dulu 100 + nomor run; build lokal selalu 100 dan repo baru mulai dari 101, lebih kecil dari APK yang sudah terpasang, sehingga Android
        // menolaknya ("bentrok dengan paket yang sudah ada"). Nilai baru ~29 juta, jauh di atas semua versionCode lama.
        versionCode = maxOf(100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0), (System.currentTimeMillis() / 60_000L).toInt())
        versionName = "2.20.0"
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
        // Tanda tangan debug tetap dari repo (keystore/debug.keystore.b64): APK baru selalu bisa menimpa APK lama tanpa uninstall.
        // Hanya dipakai bila file ada dan valid (kata sandi standar "android", alias androiddebugkey); selain itu bawaan Gradle.
        val dbgB64 = rootProject.file("../keystore/debug.keystore.b64")
        if (dbgB64.exists()) {
            val dbgOut = File(project.layout.buildDirectory.get().asFile, "flow-debug.keystore")
            val dbgOk = runCatching {
                dbgOut.parentFile.mkdirs()
                dbgOut.writeBytes(Base64.getMimeDecoder().decode(dbgB64.readText().trim()))
                KeyStore.getInstance(dbgOut, "android".toCharArray()).containsAlias("androiddebugkey")
            }.getOrDefault(false)
            if (dbgOk) {
                getByName("debug") {
                    storeFile = dbgOut
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
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
            // Nama paket sama dengan rilis (com.timeflow.app), tanpa akhiran ".debug".
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
