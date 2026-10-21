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
    }

    // Release signing comes from the environment (GitHub Actions secrets). Without it the
    // release build is left unsigned, so no key material ever lives in the repository.
    val keystorePath = System.getenv("OBSCURA_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OBSCURA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OBSCURA_KEY_ALIAS")
                keyPassword = System.getenv("OBSCURA_KEY_PASSWORD")
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

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
