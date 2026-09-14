import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
}

group = "id.primawash"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

/**
 * Separate suites, mirroring the commands in CLAUDE.md:
 *  - `test`            unit tests, no I/O
 *  - `integrationTest` real Postgres via Testcontainers (never an in-memory database)
 *  - `contractTest`    responses validated against openapi.yaml
 */
val integrationTest: SourceSet by sourceSets.creating
val contractTest: SourceSet by sourceSets.creating

configurations {
    named(integrationTest.implementationConfigurationName) { extendsFrom(configurations.testImplementation.get()) }
    named(integrationTest.runtimeOnlyConfigurationName) { extendsFrom(configurations.testRuntimeOnly.get()) }
    named(contractTest.implementationConfigurationName) { extendsFrom(configurations.testImplementation.get()) }
    named(contractTest.runtimeOnlyConfigurationName) { extendsFrom(configurations.testRuntimeOnly.get()) }
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.persistence)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)

    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
    add(integrationTest.implementationConfigurationName, libs.bundles.test.containers)
    add(contractTest.implementationConfigurationName, sourceSets.main.get().output)
    // Contract tests boot the API against the same Testcontainers Postgres as the integration suite
    // and reuse its fixtures, then validate every response body against openapi.yaml.
    add(contractTest.implementationConfigurationName, integrationTest.output)
    add(contractTest.implementationConfigurationName, libs.bundles.test.containers)
    add(contractTest.implementationConfigurationName, libs.bundles.test.contract)
}

application {
    // Ktor's EngineMain reads src/main/resources/application.conf.
    mainClass.set("io.ktor.server.netty.EngineMain")
    applicationName = "pwl-cashier"
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Business rules are evaluated in Asia/Jakarta explicitly; the JVM default must never matter.
    systemProperty("user.timezone", "UTC")
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Integration tests against a real Postgres (Testcontainers)."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

val contractTestTask = tasks.register<Test>("contractTest") {
    group = "verification"
    description = "Validates the API surface against openapi.yaml."
    testClassesDirs = contractTest.output.classesDirs
    classpath = contractTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

/**
 * Second start script in the distribution: `bin/pwl-migrate` runs Flyway and exits. The App
 * Platform pre-deploy job (and any manual migration run) uses this instead of the API entrypoint,
 * so migrations can run as the schema owner while the service runs as the restricted role.
 */
val migrateScripts = tasks.register<CreateStartScripts>("createMigrateScripts") {
    applicationName = "pwl-migrate"
    mainClass.set("id.primawash.api.db.MigrateKt")
    // Kept out of build/scripts: the application plugin copies that whole directory into bin/.
    outputDir = layout.buildDirectory.dir("migrate-scripts").get().asFile
    classpath = tasks.named<Jar>("jar").get().outputs.files + configurations.runtimeClasspath.get()
    defaultJvmOpts = listOf("-Duser.timezone=UTC")
}

distributions {
    named("main") {
        contents {
            from(migrateScripts.map { listOf(it.unixScript, it.windowsScript) }) {
                into("bin")
                filePermissions { unix("0755") }
            }
        }
    }
}

tasks.register<JavaExec>("flywayMigrate") {
    group = "database"
    description = "Runs Flyway migrations against DATABASE_URL."
    mainClass.set("id.primawash.api.db.MigrateKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("seedPilot") {
    group = "database"
    description = "Seeds pilot master data (PRD Lampiran D). dev/staging only — refuses to run on prod."
    mainClass.set("id.primawash.api.tools.SeedPilotKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

ktlint {
    filter {
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/integrationTest/kotlin",
            "src/contractTest/kotlin",
        ),
    )
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.named("check") {
    dependsOn(integrationTestTask, contractTestTask)
}
