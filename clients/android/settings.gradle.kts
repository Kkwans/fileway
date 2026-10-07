pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "FilewayAndroid"
include(":app")
// Native source builds; other hosts consume the verified packaged AAR instead.
if (providers.gradleProperty("filewayFfmpegWorkDir").isPresent) include(":ffmpeg-probe")
