plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.opentrackpad.client"
    compileSdk = 35

    defaultConfig {
        // Deliberately not the light version's id.
        //
        // v0.1 is finished, installed, and works; it stays on the phone and
        // keeps working while this one is built, so the two have to be separate
        // installs. Sharing an id would replace it on the first build and leave
        // no way back. They are told apart on the launcher by the label, which
        // says v0.2 for the same reason.
        //
        // Settings and profiles are per-install, so this one starts with the
        // defaults rather than inheriting anything from the light version.
        applicationId = "org.opentrackpad.client.v2"
        // Android 9. The handoff targets 9 or newer; nothing here needs more.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-dev"
    }

    buildTypes {
        release {
            // The app draws a touch surface and a few buttons; almost all of
            // what the libraries bring is unused. Stripping it takes the
            // package from megabytes to a fraction of one.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
}
