import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.family.bankapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.family.bankapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.2.5"
    }

    buildTypes {
        release {
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
        compose = true
    }
}

afterEvaluate {
    tasks.register<Copy>("copyApkToOneDrive") {
        dependsOn("assembleDebug")
        from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        into("${System.getProperty("user.home")}/OneDrive")
        rename { "FamilyBank.apk" }
        onlyIf { file("${System.getProperty("user.home")}/OneDrive").exists() }
    }

    tasks.register("publishFamilyApk") {
        dependsOn("assembleDebug", "copyApkToOneDrive")
        doLast {
            val versionCode = android.defaultConfig.versionCode
            val versionName = android.defaultConfig.versionName
            val apkSource = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
            val docsDir = rootProject.file("docs")
            docsDir.mkdirs()
            apkSource.copyTo(docsDir.resolve("FamilyBank.apk"), overwrite = true)
            val manifest = """
                {
                  "versionCode": $versionCode,
                  "versionName": "$versionName",
                  "apkUrl": "https://johnathonlarsen.github.io/billApp/FamilyBank.apk",
                  "releasedAt": "${Instant.now()}",
                  "notes": "Family Bank update"
                }
            """.trimIndent()
            docsDir.resolve("app-update.json").writeText(manifest)
            logger.lifecycle("Published docs/FamilyBank.apk and docs/app-update.json (v$versionName)")
        }
    }

    tasks.named("assembleDebug").configure {
        finalizedBy("publishFamilyApk")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.plaid.link:sdk-core:5.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
