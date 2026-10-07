plugins { id("com.android.library") }

val probeRoot = file(providers.gradleProperty("filewayFfmpegWorkDir").get()).canonicalFile
require(probeRoot.resolve("input-lock.json").isFile) { "Prepare the pinned FFmpeg sources before configuring this probe" }
require(probeRoot.resolve("input-lock.json").readText() == rootProject.file("native/media3-ffmpeg.lock.json").readText()) {
    "Probe sources do not match this checkout's lock file"
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += listOf("-DFILEWAY_PROBE_ROOT=${probeRoot.path}", "-DANDROID_STL=c++_static") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir(probeRoot.resolve("sources/media3/java"))
    sourceSets.getByName("main").assets.srcDir(probeRoot.resolve("sources/licenses"))
    externalNativeBuild { cmake { path = file("CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // Same compile-only annotation version as the pinned upstream catalog.
    compileOnly("org.checkerframework:checker-qual:3.13.0")
    compileOnly("androidx.annotation:annotation:1.6.0")
}
