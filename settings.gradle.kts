pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "androCE"
include(":app")
include(":Bcore")
project(":Bcore").projectDir = file("virtual-engine/Bcore")
include(":black-reflection")
project(":black-reflection").projectDir = file("virtual-engine/black-reflection")
include(":compiler")
project(":compiler").projectDir = file("virtual-engine/compiler")
