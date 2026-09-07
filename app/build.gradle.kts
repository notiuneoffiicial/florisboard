/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Tiune fork: this module is built as an Android LIBRARY, not an app.
 *
 * Upstream FlorisBoard is a standalone keyboard app. Tiune embeds the whole
 * keyboard — layouts, themes, suggestions, the settings screens — inside its
 * own app, and puts its dictation panel behind the keyboard's mic key. So the
 * things an app module owns (applicationId, version, signing, the launcher
 * icon, product flavours) are owned by Tiune's app module instead, and this
 * file declares a library whose manifest and resources merge into it.
 *
 * The Android Gradle plugin is requested WITHOUT a version on purpose: the
 * embedding build already has it on the classpath (Tauri's buildSrc), and a
 * versioned request for a plugin that is already loaded is a Gradle error.
 */

import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
}

val projectMinSdk: String by project
val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project

// The embedding app's identity, so the code paths that used to read them from
// an application BuildConfig (FileProvider authorities, "is this keyboard
// enabled" checks, the backup format's version stamp) keep working. Set by the
// embedding build through Gradle properties; the defaults are upstream's.
val hostApplicationId: String = (findProperty("florisHostApplicationId") as? String) ?: "dev.patrickgold.florisboard"
val hostVersionCode: String = (findProperty("florisHostVersionCode") as? String) ?: projectVersionCode
val hostVersionName: String = (findProperty("florisHostVersionName") as? String) ?: projectVersionName.substringBefore("-")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-jvm-default=enable",
            "-Xwhen-guards",
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
            "-XXLanguage:+LocalTypeAliases",
        ))
    }
}

configure<LibraryExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = projectCompileSdk.toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        minSdk = projectMinSdk.toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")

        buildConfigField("String", "APPLICATION_ID", "\"$hostApplicationId\"")
        buildConfigField("int", "VERSION_CODE", hostVersionCode)
        buildConfigField("String", "VERSION_NAME", "\"$hostVersionName\"")
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${getGitCommitHash().get()}\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v~draft2\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")

        sourceSets {
            maybeCreate("main").apply {
                assets.directories += "src/main/assets"
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        named("debug") {
            isJniDebuggable = false
        }
        named("release") {
            isMinifyEnabled = false
        }
    }

    lint {
        baseline = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("src/main/config")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.window.core)
    implementation(libs.cache4k)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.patrickgold.compose.tooltip)
    // `api`: the embedding app reads the keyboard's preferences (KeyboardPrefs.kt).
    api(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)

    // `api`, not `implementation`: the embedding app subclasses
    // FlorisImeService and reaches the same Compose/lib types it does.
    api(projects.lib.android)
    api(projects.lib.color)
    api(projects.lib.compose)
    api(projects.lib.kotlin)
    api(projects.lib.snygg)
}

fun getGitCommitHash(short: Boolean = false): Provider<String> {
    if (!File(projectDir, "../.git").exists()) {
        return providers.provider { "null" }
    }

    val execProvider = providers.exec {
        workingDir = projectDir
        if (short) {
            commandLine("git", "rev-parse", "--short", "HEAD")
        } else {
            commandLine("git", "rev-parse", "HEAD")
        }
    }
    return execProvider.standardOutput.asText.map { it.trim() }
}
