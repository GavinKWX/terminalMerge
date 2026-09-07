// MF919 Pro -- the Android 13 fleet. Single Activity + Navigation component.
// Release history lives in CHANGELOG.md (extracted from this file in Phase 0).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.navigation.safe.args)
    id("kotlin-parcelize")
}

android {
    namespace = "com.sc.mf919pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sc.mf919pro"
        minSdk = 29
        targetSdk = 35
        versionCode = 1

        // DO NOT NORMALISE. Migrations are gated on versionName, not versionCode:
        // MainActivity compares `currVersion < getAppVersion().replace(".","").toInt()`.
        // Editing this string silently re-runs or skips DB migrations on live
        // terminals. See Migration1004's header comment for a prior instance.
        versionName = "1.0.04"

        multiDexEnabled = true
        resValue("string", "app_name", "SHARECOMMERCE")
        resValue("string", "app_name_about", " ")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        // The only signing config, used by all three build types on purpose -- see the note on
        // the release block below before changing anything here.
        getByName("debug") {
            storeFile = file("${rootDir}/keystore/debug.keystore")
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "DEFAULT_ENV", "\"development\"")
            manifestPlaceholders["appNameSuffix"] = "(SIT)"
        }
        create("stag") {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".uat"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "DEFAULT_ENV", "\"staging\"")
            manifestPlaceholders["appNameSuffix"] = "(UAT)"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // INTENTIONAL -- do not "fix" this, and do not point it at a CI keystore.
            //
            // Release is signed with the debug keystore by design, and that design is
            // load-bearing:
            //   * distribution is via TMS, not Play Store, so Play's signing rules never apply;
            //   * a manufacturer signing layer is applied before upload to TMS -- that is the
            //     signature the fleet actually trusts, not this one;
            //   * Android only permits an in-place update from an APK signed with the SAME key,
            //     so moving release onto a separate keystore would break app updates on every
            //     already-deployed terminal.
            //
            // Confirmed by Gavin, 2026-09-04. Audit item B3: closed as by-design, not a gap.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "DEFAULT_ENV", "\"production\"")
            manifestPlaceholders["appNameSuffix"] = ""
        }
    }

    flavorDimensions += "client"
    productFlavors {
        create("sharecomm") {
            dimension = "client"
            resValue("string", "app_name_about", "Share Commerce")
            manifestPlaceholders["appName"] = "ShareCommerce"
        }
        create("special") {
            dimension = "client"
            resValue("string", "app_name_about", "Peyment App")
            manifestPlaceholders["appName"] = "PaymentAPP"
        }
        create("rm") {
            dimension = "client"
            resValue("string", "app_name_about", "RM")
            manifestPlaceholders["appName"] = "RM"
        }
        create("glypay") {
            dimension = "client"
            resValue("string", "app_name_about", "GLYPay")
            manifestPlaceholders["appName"] = "GLYPay"
        }
        create("baguspos") {
            dimension = "client"
            resValue("string", "app_name_about", "BagusPos")
            manifestPlaceholders["appName"] = "BagusPos"
        }
        create("paydibs") {
            dimension = "client"
            resValue("string", "app_name_about", "Paydibs")
            manifestPlaceholders["appName"] = "Paydibs"
        }
        create("payex") {
            dimension = "client"
            resValue("string", "app_name_about", "Xendit")
            manifestPlaceholders["appName"] = "Xendit"
        }
        create("oxpay") {
            dimension = "client"
            resValue("string", "app_name_about", "OxPay")
            manifestPlaceholders["appName"] = "OxPay"
        }
        create("rnd") {
            dimension = "client"
            resValue("string", "app_name_about", "R&D")
            manifestPlaceholders["appName"] = "R&D"
        }
        create("bsn") {
            dimension = "client"
            resValue("string", "app_name_about", "BSN")
            manifestPlaceholders["appName"] = "BSN"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

// Audit B4 -- variant filter. See the note in app-mf919/build.gradle.kts.
androidComponents {
    val active = (providers.gradleProperty("activeFlavors").orNull)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    if (!active.isNullOrEmpty()) {
        beforeVariants { variant ->
            variant.enable = variant.productFlavors.any { it.second in active }
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    // Was resolving transitively through circleindicator (audit A4); declared now.
    implementation(libs.androidx.viewpager2)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.gson)
    implementation(libs.commons.lang3)
    implementation(libs.threetenabp)
    implementation(libs.timber)
    implementation(libs.java.websocket)
    implementation(libs.circleindicator)
    implementation(libs.glide)
    implementation(libs.libphonenumber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
