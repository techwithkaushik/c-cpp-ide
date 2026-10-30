#include <jni.h>
#include <cstddef>

extern "C" {
extern const unsigned char code_studio_bootstrap_start[];
extern const unsigned char code_studio_bootstrap_end[];
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_techwithkaushik_cppide_integration_termuxruntime_EmbeddedBootstrap_nativeReadArchive(
    JNIEnv* env, jclass /* clazz */) {
    const auto* start = code_studio_bootstrap_start;
    const auto* end = code_studio_bootstrap_end;
    if (start == nullptr || end <= start) return nullptr;
    const auto length = static_cast<jsize>(end - start);
    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte*>(start));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_techwithkaushik_cppide_integration_termuxruntime_EmbeddedBootstrap_nativeArchiveSize(
    JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(code_studio_bootstrap_end - code_studio_bootstrap_start);
}
