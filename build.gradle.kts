plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

val externalBuildRoot = System.getenv("OMNIREAD_BUILD_DIR")
    ?: "E:\\GradleBuilds\\OmniRead"

layout.buildDirectory.set(file("$externalBuildRoot\\root"))
subprojects {
    layout.buildDirectory.set(file("$externalBuildRoot\\${project.name}"))
}
