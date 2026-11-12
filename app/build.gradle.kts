import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

/** Puts the dictionary images built by :core among the assets. */
abstract class CopyDictionaryImages : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val images: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(images)
            into(outputDir)
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.sentry)
}

// Speech is recognized by the installed Govorun app. Only recognition on the user's server runs a
// VAD here: scripts/fetch_voice_assets.sh downloads sherpa-onnx (`just voice-runtime`) and Silero
// (`just voice-models`).
val sherpaOnnxAar = file("libs/sherpa-onnx.aar")
val vadModel = file("src/main/assets/voice/silero_vad.onnx")

// Short commit hash shown as the build number on the settings page; "unknown" outside a git checkout.
val gitShortHash =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().ifEmpty { "unknown" } }
        .orElse("unknown")

// Error reporting is configured at build time by SENTRY_DSN: from the
// environment first, then from the `.env` file in the repository root. Without a DSN the build has no
// reporting at all and the settings page does not offer the option.
val dotEnv: Map<String, String> =
    rootProject
        .file(".env")
        .takeIf { it.isFile }
        ?.readLines()
        .orEmpty()
        .mapNotNull { line ->
            val entry = line.trim().removePrefix("export ").trim()
            if (entry.startsWith("#") || '=' !in entry) return@mapNotNull null
            entry.substringBefore('=').trim() to
                entry
                    .substringAfter('=')
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
        }.toMap()

fun buildSetting(name: String): Provider<String> =
    providers
        .environmentVariable(name)
        .map { it.trim() }
        // An exported but empty variable must not hide the value in `.env`.
        .filter { it.isNotEmpty() }
        .orElse(provider { dotEnv[name].orEmpty() })

val sentryDsn = buildSetting("SENTRY_DSN")

// Uploading the R8 mapping to the error tracker needs all four values; without any one of them the
// build still packs the mapping id into the APK but uploads nothing.
val sentryUrl = buildSetting("SENTRY_URL")
val sentryOrg = buildSetting("SENTRY_ORG")
val sentryProject = buildSetting("SENTRY_PROJECT")
val sentryAuthToken = buildSetting("SENTRY_AUTH_TOKEN")
val sentryUploadConfigured =
    listOf(sentryUrl, sentryOrg, sentryProject, sentryAuthToken).all { it.get().isNotEmpty() }

// The release key comes from the same places: a path to the keystore (absolute, `~/` or relative to the
// repository root), its password, the key alias and the key password.
val releaseKeystore = buildSetting("RELEASE_KEYSTORE_FILE")
val releaseKeystorePassword = buildSetting("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = buildSetting("RELEASE_KEY_ALIAS")
val releaseKeyPassword = buildSetting("RELEASE_KEY_PASSWORD")
val releaseSigningSettings = listOf(releaseKeystore, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningSettings.all { it.get().isNotEmpty() }
val releaseKeystoreFile: File? =
    releaseKeystore.get().takeIf { it.isNotEmpty() }?.let { path ->
        if (path.startsWith(
                "~/",
            )
        ) {
            File(System.getProperty("user.home"), path.removePrefix("~/"))
        } else {
            rootProject.file(path)
        }
    }

val swipeCheck = providers.gradleProperty("swipeCheck")
val swipeModelOverride = providers.gradleProperty("swipeModelAssets")

android {
    namespace = "ru.tinyops.android.vboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.tinyops.android.vboard"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-beta"
        buildConfigField("String", "GIT_HASH", "\"${gitShortHash.get()}\"")
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx ships native libraries for four ABIs; phones are arm64.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    // The Silero .onnx file and the slide to type .pte file are dense tensors: compressing them gains
    // nothing and forces a full unpack when they are copied out of the APK on first use. Dictionary
    // images and word pair tables are memory-mapped straight from the APK, which works only for
    // uncompressed entries.
    androidResources {
        noCompress += listOf("onnx", "pte", "dict", "bigrams")
    }

    // `just swipe-device-check`: a package of its own, because connected tests uninstall what they
    // install and would take the keyboard on the phone and its data with them.
    if (swipeCheck.isPresent) {
        sourceSets
            .getByName("androidTest")
            .assets
            .directories
            .add("../build/swipe/check")
        swipeModelOverride.orNull?.let { directory ->
            sourceSets
                .getByName("main")
                .assets
                .directories
                .add(directory)
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            if (swipeCheck.isPresent) applicationIdSuffix = ".swipecheck"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the key the build stops at checkReleaseSigning instead of producing an unsigned APK.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric reaches into JDK internals (file descriptors) on JDK 17+.
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        // Version-freshness checks need network access and are not code issues.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        // Lint jars bundled with AndroidX libraries lag behind this lint version; not a code issue.
        disable += "ObsoleteLintCustomCheck"
        // Phones only: the sherpa-onnx native runtime is packaged for arm64-v8a.
        disable += "ChromeOsAbiSupport"
    }
}

// The plugin only links a release build to its R8 mapping and uploads that mapping. Everything else it
// can do (bytecode tracing, extra integrations, dependency lists, telemetry) stays off: reports carry
// technical data only, and the SDK is added by hand in the dependencies below.
sentry {
    url.set(sentryUrl.map { it.ifEmpty { null } })
    org.set(sentryOrg.map { it.ifEmpty { null } })
    projectName.set(sentryProject.map { it.ifEmpty { null } })
    authToken.set(sentryAuthToken.map { it.ifEmpty { null } })
    includeProguardMapping.set(sentryDsn.map { it.isNotEmpty() })
    autoUploadProguardMapping.set(sentryUploadConfigured)
    uploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    autoInstallation.enabled.set(false)
    tracingInstrumentation.enabled.set(false)
    telemetry.set(false)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val dictionaryImagesDependencies = configurations.dependencyScope("dictionaryImagesDependencies")
val dictionaryImages = configurations.resolvable("dictionaryImages") { extendsFrom(dictionaryImagesDependencies.get()) }

val copyDictionaryImages =
    tasks.register<CopyDictionaryImages>("copyDictionaryImages") {
        images.from(dictionaryImages)
        outputDir.set(layout.buildDirectory.dir("generated/dictAssets"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyDictionaryImages, CopyDictionaryImages::outputDir)
    }
}

// Regenerated on a device by `just profile`, not by every build: the result is kept in the sources.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

dependencies {
    baselineProfile(project(":benchmark"))
    dictionaryImagesDependencies(project(path = ":core", configuration = "dictionaryImagesElements"))
    implementation(project(":core"))
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sentry.android)
    implementation(libs.executorch.android)
    implementation(files(sherpaOnnxAar))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Robolectric downloads Android framework jars at test time, bypassing Gradle repositories.
// `-ProbolectricRepoUrl=<maven mirror>` (or ~/.gradle/gradle.properties) points it at a mirror.
providers.gradleProperty("robolectricRepoUrl").orNull?.let { url ->
    tasks.withType<Test>().configureEach { systemProperty("robolectric.dependency.repo.url", url) }
}

val checkVoiceRuntime =
    tasks.register("checkVoiceRuntime") {
        val aar = sherpaOnnxAar
        doFirst {
            check(
                aar.isFile,
            ) { "Missing ${aar.name}: run `just voice-runtime` (scripts/fetch_voice_assets.sh runtime)" }
        }
    }
tasks.named("preBuild") { dependsOn(checkVoiceRuntime) }

// An APK without the VAD would install fine and fail on the first dictation through a server.
val checkVoiceModels =
    tasks.register("checkVoiceModels") {
        val model = vadModel
        doFirst {
            check(model.isFile) {
                "Missing Silero VAD: run `just voice-models` (scripts/fetch_voice_assets.sh models)"
            }
        }
    }
// Exact names: unit tests and lint run bundleDebugClassesTo*Jar, and they do not need the VAD.
val packagingTasks =
    setOf(
        "assembleDebug",
        "assembleRelease",
        "assembleBenchmarkRelease",
        "assembleNonMinifiedRelease",
        "bundleDebug",
        "bundleRelease",
    )
tasks.matching { it.name in packagingTasks }.configureEach {
    dependsOn(checkVoiceModels)
}

val checkReleaseSigning =
    tasks.register("checkReleaseSigning") {
        val configured = releaseSigningConfigured
        val keystore = releaseKeystoreFile
        doFirst {
            check(configured) {
                "Release signing is not set up: fill RELEASE_KEYSTORE_FILE, RELEASE_KEYSTORE_PASSWORD, " +
                    "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD in .env (see .env.example)"
            }
            check(keystore?.isFile == true) { "Release keystore not found: $keystore" }
        }
    }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }
