plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.edgarguitarist.audiopc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgarguitarist.audiopc"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // `-PidSufijo=.dev` instala una copia aparte (para probar sin pisar la versión instalada).
        (project.findProperty("idSufijo") as String?)?.let { applicationIdSuffix = it }
    }

    // Firma de release opcional: si hay llave configurada (propiedades Gradle o variables de entorno
    // ANDROID_KEYSTORE_FILE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD) se usa;
    // si no, el release se firma con la llave de debug.
    fun firma(nombre: String): String? =
        (project.findProperty(nombre) as String?) ?: System.getenv(nombre)
    val keystoreFile = firma("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    val tieneLlaveRelease = keystoreFile != null && keystoreFile.exists() &&
        !firma("ANDROID_KEYSTORE_PASSWORD").isNullOrBlank() && !firma("ANDROID_KEY_ALIAS").isNullOrBlank()
    if (tieneLlaveRelease) {
        signingConfigs.create("release") {
            storeFile = keystoreFile
            storePassword = firma("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = firma("ANDROID_KEY_ALIAS")
            keyPassword = firma("ANDROID_KEY_PASSWORD") ?: firma("ANDROID_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sin llave propia configurada, se firma con la de debug (CI la reemplaza con secretos).
            signingConfig = signingConfigs.getByName(if (tieneLlaveRelease) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
