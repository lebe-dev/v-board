import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.sentry) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.cli)
        ignoreFailures.set(false)
        filter {
            exclude { it.file.path.contains("generated") }
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
    }

    // `just security` writes the shipped runtime classpaths as lockfiles for the vulnerability scanners.
    if (providers.gradleProperty("securityLocks").isPresent) {
        dependencyLocking {
            lockFile.set(rootProject.layout.buildDirectory.file("security/${project.name}/gradle.lockfile"))
        }
        configurations
            .matching { it.name == "releaseRuntimeClasspath" || it.name == "runtimeClasspath" }
            .configureEach { resolutionStrategy.activateDependencyLocking() }
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}
