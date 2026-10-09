#include <jni.h>
#include <cstring>
#include <vector>
#include "libnfbcore.h"

// Byte arrays preserve standard UTF-8, including non-BMP filenames. JNI's
// modified-UTF-8 string helpers must not be used for this JSON boundary.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_kkwans_nasfilebrowser_core_NativeTransport_nativeCall(
    JNIEnv* env, jobject, jbyteArray command) {
    if (command == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(command);
    // The Go bridge retains a 1 MiB control limit and accepts a larger envelope
    // only for bounded, explicit raw resource writes (10 MiB before base64).
    if (length > (15 << 20)) return nullptr;
    std::vector<char> bytes(length);
    env->GetByteArrayRegion(command, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    char* response = nfb_call(bytes.data(), length);
    if (response == nullptr) return nullptr;
    const auto size = static_cast<jsize>(std::strlen(response));
    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<jbyte*>(response));
    nfb_free(response);
    return result;
}
