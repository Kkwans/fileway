pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "FilewayAndroid"
include(":app")
// Explicit native-comparison builds only; the app does not depend on this module.
if (providers.gradleProperty("filewayFfmpegWorkDir").isPresent) include(":ffmpeg-probe")
