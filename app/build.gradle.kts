import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    // Code lives in the conventional lowercase package; the installed app id keeps the chosen casing.
    namespace = "com.shahaabapps.obscura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shahaabapps.Obscura"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("obscura.versionCode").get().toInt()
        versionName = providers.gradleProperty("obscura.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 64-bit only: every phone sold since 2019 is arm64; x86_64 is for emulators.
        // Dropping the 32-bit SQLCipher libraries roughly halves the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // Release signing is read from this machine's git config, falling back to the environment
    // for CI. Both live outside the working tree — git config in .git/config, which is never
    // committed — so no key, password or path to one is ever part of the repository or the code.
    // Set them once per clone:
    //
    //   git config obscura.signing.keystore      /path/to/obscura-release.jks
    //   git config obscura.signing.storePassword ...
    //   git config obscura.signing.keyAlias      ...
    //   git config obscura.signing.keyPassword   ...
    //
    // Without them the release build is simply left unsigned.
    fun gitConfig(key: String): String? = runCatching {
        val process = ProcessBuilder("git", "config", "--get", key)
            .directory(rootDir)
            .redirectErrorStream(false)
            .start()
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.errorStream.close()
        if (process.waitFor() == 0 && value.isNotEmpty()) value else null
    }.getOrNull()

    fun signingValue(name: String, environmentName: String): String? =
        gitConfig("obscura.signing.$name") ?: System.getenv(environmentName)

    val keystorePath = signingValue("keystore", "OBSCURA_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = signingValue("storePassword", "OBSCURA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "OBSCURA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "OBSCURA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

// The vault must never be able to reach the network. Fail the build if any library
// slips a network permission into the merged manifest.
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register("verify${variantName}NoNetwork") {
            group = "verification"
            description = "Fails if the merged $variantName manifest requests network access."
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val banned = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
                val leaked = banned.filter { text.contains("\"$it\"") }
                check(leaked.isEmpty()) { "Network permission(s) found in merged manifest: $leaked" }
                println("verify${variantName}NoNetwork: OK, no network permissions")
            }
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(verify) }
    }
}

dependencies {
    implementation(project(":crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real PDF reader to check the encrypted recovery-key PDF. Tests only; never shipped.
    testImplementation(libs.pdfbox)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose's test library brings Espresso 3.5, which breaks on Android 17 (API 37); 3.7 fixes it.
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
