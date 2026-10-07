cmake_minimum_required(VERSION 3.22.1)
project(fileway_ffmpeg_audio_probe LANGUAGES CXX)
if(NOT ANDROID OR NOT DEFINED FILEWAY_PROBE_ROOT)
    message(FATAL_ERROR "Use the pinned Android NDK and prepared probe work root")
endif()
set(FFMPEG_PREFIX "${FILEWAY_PROBE_ROOT}/prefix/${ANDROID_ABI}")
add_library(ffmpegJNI SHARED "${FILEWAY_PROBE_ROOT}/sources/media3/jni/ffmpeg_jni.cc")
target_compile_features(ffmpegJNI PRIVATE cxx_std_17)
target_include_directories(ffmpegJNI PRIVATE "${FFMPEG_PREFIX}/include")
target_link_libraries(ffmpegJNI PRIVATE
    "-Wl,--start-group"
    "${FFMPEG_PREFIX}/lib/libavcodec.a"
    "${FFMPEG_PREFIX}/lib/libswresample.a"
    "${FFMPEG_PREFIX}/lib/libavutil.a"
    "-Wl,--end-group" log z m)
# Keep this static FFmpeg isolated from libVLC's FFmpeg symbols in the same process.
target_link_options(ffmpegJNI PRIVATE
    "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,-z,max-page-size=16384")
