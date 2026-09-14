// MF919 -- the Android 7/10 fleet. Activities + manual Intent navigation.
// Release history lives in CHANGELOG.md (extracted from this file in Phase 0).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "com.sc.mf919"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sc.mf919"
        minSdk = 24
        targetSdk = 33
        versionCode = 1

        // DO NOT NORMALISE. Migrations are gated on versionName, not versionCode:
        // MainActivity compares `currVersion < getAppVersion().replace(".","").toInt()`.
        // Editing this string silently re-runs or skips DB migrations on live
        // terminals. See Migration1004's header comment for a prior instance.
        versionName = "2.2.26"

        multiDexEnabled = true
        resValue("string", "app_name", "SHARECOMMERCE")
        resValue("string", "app_name_about", " ")
        buildConfigField("boolean", "hide_bottom", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
            buildConfigField("String", "DEFAULT_ENV", "\"development\"")
            manifestPlaceholders["appNameSuffix"] = "(SIT)"
        }
        create("stag") {
            isDebuggable = true
            isMinifyEnabled = false
            // :core only has debug/release. Without this, every stag build fails to resolve
            // :core at all ("No matching variant ... BuildTypeAttr 'stag'"). stag is
            // debuggable and unminified, so core's debug is the right match.
            matchingFallbacks += "debug"
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
            resValue("string", "app_name_about", "Payment APP")
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
            buildConfigField("boolean", "hide_bottom", "true")
            manifestPlaceholders["appName"] = "Xendit"
        }
        create("oxpay") {
            dimension = "client"
            resValue("string", "app_name_about", "OxPay")
            buildConfigField("boolean", "hide_bottom", "true")
            manifestPlaceholders["appName"] = "Oxpay"
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

// Audit B4 -- variant filter. 10 flavors x 3 build types = 30 variants per app.
// Opt-in rather than hardcoded, because which flavors ship is a business fact
// this repo does not encode: ./gradlew -PactiveFlavors=sharecomm,bsn ...
// With the property absent, every variant is configured exactly as before.
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
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.vectordrawable.animated)

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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
