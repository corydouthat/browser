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
        // geckoview-omni + org.mozilla.components
        maven("https://maven.mozilla.org/maven2")
    }
}
rootProject.name = "hatch"
include(":persona", ":app")
