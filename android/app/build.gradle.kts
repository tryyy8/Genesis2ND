plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.genesis.mobile"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("GENESIS_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("GENESIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GENESIS_KEY_ALIAS")
                keyPassword = System.getenv("GENESIS_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "app.genesis.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "0.4.19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (System.getenv("GENESIS_KEYSTORE_PATH").isNullOrBlank()) {
                null
            } else {
                signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("org.eclipse.jdt:ecj:3.26.0")
    implementation("com.android.tools:r8:8.6.27")
    implementation("com.android.tools.build:apksig:8.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
