import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Consumed by the Android app: keep Java 17 bytecode.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(project(":stt"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The keyboard does not parse the word lists at startup: they are compiled here into binary images.
val dictionarySources = layout.projectDirectory.dir("src/main/dict")
val dictionaryOutput = layout.buildDirectory.dir("generated/dict")

val dictionaryImages =
    tasks.register<JavaExec>("dictionaryImages") {
        description = "Compiles the word lists into binary dictionary images."
        group = "build"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("ru.tinyops.android.vboard.core.dict.DictionaryImageKt")
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        inputs.dir(dictionarySources).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(dictionaryOutput)
        args(
            dictionarySources.asFile.path,
            dictionaryOutput
                .get()
                .dir("dict")
                .asFile.path,
        )
    }

// Tests read the images and their sources from the classpath; the app packs the images as assets.
sourceSets.test {
    resources.srcDir(dictionaryImages)
}

tasks.processTestResources {
    from(dictionarySources) { into("dict") }
}

configurations.consumable("dictionaryImagesElements")

artifacts.add("dictionaryImagesElements", dictionaryOutput) { builtBy(dictionaryImages) }
