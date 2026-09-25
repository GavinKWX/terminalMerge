plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "com.sc.terminal.core"
    compileSdk = 35

    defaultConfig {
        // Must stay at the LOWER of the two apps' minSdk. app-mf919 ships to
        // Android 7 hardware, so anything added here has to hold at API 24.
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // Android framework classes are stubs in a JVM unit test and throw "Stub!" by default.
        // The iso.CurrentStore seam takes a Context it only passes along, so its test needs an
        // instance it never calls into -- this lets one be constructed.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The four vendor jars both apps use. Byte-identical across the two source
    // projects (verified by md5 during the Phase 0 import), so they live here
    // once and reach both apps transitively -- hence `api`, not `implementation`.
    api(files("libs/ysdk_6.14.d8af3dea_26081916.jar"))
    api(files("libs/iso8583.jar"))
    api(files("libs/terminalLib.jar"))
    api(files("libs/nanohttpd-2-3-1.jar"))

    // Needed by the shared logging cluster moved in Phase 1b:
    // HelperLog serialises its buffer with Gson, FileLoggingTree plants a Timber tree.
    // `api` so both apps keep seeing them exactly as they did before the move.
    api(libs.gson)
    api(libs.timber)
    // ThreeTenABP, not java.time: :core is minSdk 24 and java.time needs 26. The ISO
    // forming code uses it for the GMT+8 void timestamp.
    api(libs.threetenabp)

    // Needed by DbHandler, moved in Phase 2b: kotlin-reflect drives its reified
    // insert/select mapping (memberProperties), core-ktx supplies SQLiteDatabase.transaction.
    api(libs.kotlin.reflect)
    api(libs.androidx.core.ktx)

    // QRCodeUtil (moved in Phase 2c) encodes QR bitmaps with zxing.
    api(libs.zxing.core)

    // MdbController runs its auto-session supervisor and readiness waits on coroutines.
    api(libs.kotlinx.coroutines.android)

    // datastore/ (settlement-block flag), moved from both apps.
    api(libs.androidx.datastore.preferences)

    // ws/ (ECR WebSocket server + TMS push client), moved from both apps.
    api(libs.java.websocket)

    testImplementation(libs.junit)
}
