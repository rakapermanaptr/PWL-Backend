rootProject.name = "pwl-cashier"

plugins {
    // Lets Gradle provision a JDK 21 toolchain automatically when the machine only has an older JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
