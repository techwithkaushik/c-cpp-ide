#include <jni.h>
#include <cstddef>
#include <cstdint>

extern "C" {
extern const unsigned char code_studio_bootstrap_start[];
extern const unsigned char code_studio_bootstrap_end[];
}

namespace {
std::size_t embedded_archive_size() {
    const auto start = reinterpret_cast<std::uintptr_t>(code_studio_bootstrap_start);
    const auto end = reinterpret_cast<std::uintptr_t>(code_studio_bootstrap_end);
    return end > start ? static_cast<std::size_t>(end - start) : 0U;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_techwithkaushik_cppide_integration_termuxruntime_EmbeddedBootstrap_nativeReadArchive(
    JNIEnv* env, jclass /* clazz */) {
    const std::size_t size = embedded_archive_size();
    if (size == 0 || size > static_cast<std::size_t>(512) * 1024 * 1024) return nullptr;
    const auto length = static_cast<jsize>(size);
    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, length,
                            reinterpret_cast<const jbyte*>(code_studio_bootstrap_start));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_techwithkaushik_cppide_integration_termuxruntime_EmbeddedBootstrap_nativeArchiveSize(
    JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(embedded_archive_size());
}
