plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.dashcams.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.dashcams.app"
        minSdk = 26 // Android 8: la idea es usar móviles viejos
        targetSdk = 36 // Android 16: lo exige Google Play
        versionCode = 33
        versionName = "0.13.6"

        // Librerías nativas del control por voz: solo móviles ARM y el emulador del PC (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Clave fija de pruebas: así cada APK nuevo se instala encima del anterior
        // sin tener que desinstalar (y sin perder los clips guardados).
        // Para publicar en Google Play se creará otra clave privada aparte.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Clave de subida a Google Play. No está en el repositorio: GitHub la recibe de los
        // secretos FIRMA_KEYSTORE_BASE64 y FIRMA_PASSWORD (ver .github/workflows/build.yml).
        val releaseStore = System.getenv("FIRMA_KEYSTORE_PATH")
        if (releaseStore != null && file(releaseStore).exists()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("FIRMA_PASSWORD")
                keyAlias = "dashcams"
                keyPassword = System.getenv("FIRMA_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val camerax = "1.5.1"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Fecha, velocidad, coordenadas y marca dibujadas dentro del vídeo
    implementation("androidx.camera:camera-effects:$camerax")

    // Control por voz sin internet (el modelo de español se descarga al activarlo)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
