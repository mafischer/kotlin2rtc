import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover)
}

/** The published libraries (the CLI is a sample, the Tapo module has no code yet) */
val libraryModules = setOf("kotlin2rtc-core", "kotlin2rtc-codec", "kotlin2rtc-onvif", "kotlin2rtc-rtsp")

// Every resolved dependency is pinned in a gradle.lockfile per module (Trivy reads them): after a dependency change
// run `./gradlew resolveAndLockAll --write-locks`
allprojects {
    dependencyLocking { lockAllConfigurations() }
}

tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves every configuration of every module (with --write-locks: rewrites the lockfiles)"
    notCompatibleWithConfigurationCache("resolves configurations at execution time")
    doFirst { require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" } }
    doLast {
        allprojects.forEach { project -> project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    extensions.configure<KotlinJvmProjectExtension> {
        // Built with the local JDK (21), bytecode and API for JDK 17 (Android's desugared JVM level and older servers)
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
        if (project.name != "kotlin2rtc-cli") explicitApi()
        if (project.name in libraryModules) {
            // The public API is recorded in each module's api/ dump; `check` fails when it changes unintentionally
            // (`./gradlew updateKotlinAbi` after an intended change)
            @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension> { enabled.set(true) }
        }
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlintTool.get())
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        "testImplementation"(rootProject.libs.kotlinx.coroutines.test)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed") }
    }

    if (name in libraryModules) {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Sonatype Central Portal; credentials and the signing key come from the environment when releasing
            // (ORG_GRADLE_PROJECT_mavenCentralUsername/-Password, ORG_GRADLE_PROJECT_signingInMemoryKey...), never the repo
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set(project.name)
                description.set(project.description)
            }
        }
        // The files of a GitHub Release (.github/workflows/release.yml): `publishAllPublicationsToGithubReleaseRepository`
        // writes a Maven layout into build/github-release-repo, whose files the workflow attaches to the release flat
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "githubRelease"
                    url = uri(rootProject.layout.buildDirectory.dir("github-release-repo"))
                }
            }
        }
    }
}

// One coverage report over every module: ./gradlew koverXmlReport koverHtmlReport (build/reports/kover)
dependencies {
    subprojects.forEach { kover(it) }
}

tasks.register("coverage") {
    group = "verification"
    description = "Writes the aggregated Kover XML and HTML coverage reports"
    dependsOn("koverXmlReport", "koverHtmlReport", "koverVerify")
}

// `./gradlew build` also writes the coverage report
if (tasks.findByName("build") == null) {
    tasks.register("build") {
        group = "build"
        description = "Builds, tests and lints every module and writes the coverage report"
    }
}
tasks.named("build") { dependsOn("coverage") }

kover {
    reports {
        // `./gradlew build` fails below these (aggregated over every module): keep coverage where it is
        verify {
            rule("Line coverage") { minBound(90) }
            rule("Branch coverage") { minBound(75, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
        }
        filters {
            // The shared test fakes are not library code
            excludes { packages("io.github.mafischer.kotlin2rtc.testing") }
        }
    }
}

