#include <jni.h>
#include <vterm.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {
struct NativeVTerm {
    VTerm* terminal = nullptr;
    VTermScreen* screen = nullptr;
    int rows = 24;
    int columns = 80;
};

NativeVTerm* from_handle(jlong handle) {
    return reinterpret_cast<NativeVTerm*>(static_cast<intptr_t>(handle));
}

void throw_state(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeCreate(
    JNIEnv* env, jobject, jint columns, jint rows) {
    auto state = std::make_unique<NativeVTerm>();
    state->columns = std::clamp(columns, 20, 400);
    state->rows = std::clamp(rows, 5, 200);
    state->terminal = vterm_new(state->rows, state->columns);
    if (state->terminal == nullptr) {
        throw_state(env, "libvterm could not allocate a terminal screen.");
        return 0;
    }
    vterm_set_utf8(state->terminal, 1);
    state->screen = vterm_obtain_screen(state->terminal);
    if (state->screen == nullptr) {
        vterm_free(state->terminal);
        throw_state(env, "libvterm could not create a screen.");
        return 0;
    }
    vterm_screen_enable_altscreen(state->screen, 1);
    vterm_screen_set_damage_merge(state->screen, VTERM_DAMAGE_ROW);
    vterm_screen_reset(state->screen, 1);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeFeed(
    JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
    auto* state = from_handle(handle);
    if (state == nullptr || bytes == nullptr) return;
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0) return;
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (data == nullptr) return;
    vterm_input_write(state->terminal, reinterpret_cast<const char*>(data), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    vterm_screen_flush_damage(state->screen);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeRender(
    JNIEnv* env, jobject, jlong handle) {
    auto* state = from_handle(handle);
    if (state == nullptr) return env->NewStringUTF("");
    const size_t capacity = static_cast<size_t>(state->rows) *
        (static_cast<size_t>(state->columns) * 4U + 1U) + 1U;
    std::vector<char> buffer(capacity, '\0');
    const VTermRect rect{0, state->rows, 0, state->columns};
    const size_t length = vterm_screen_get_text(state->screen, buffer.data(), capacity - 1, rect);
    buffer[std::min(length, capacity - 1)] = '\0';
    return env->NewStringUTF(buffer.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeResize(
    JNIEnv*, jobject, jlong handle, jint columns, jint rows) {
    auto* state = from_handle(handle);
    if (state == nullptr) return;
    state->columns = std::clamp(columns, 20, 400);
    state->rows = std::clamp(rows, 5, 200);
    vterm_set_size(state->terminal, state->rows, state->columns);
    vterm_screen_flush_damage(state->screen);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeCursor(
    JNIEnv*, jobject, jlong handle) {
    auto* state = from_handle(handle);
    if (state == nullptr) return -1;
    VTermPos cursor{};
    VTermState* terminal_state = vterm_obtain_state(state->terminal);
    vterm_state_get_cursorpos(terminal_state, &cursor);
    return (static_cast<jlong>(cursor.row) << 32) |
        static_cast<uint32_t>(cursor.col);
}

extern "C" JNIEXPORT void JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    std::unique_ptr<NativeVTerm> state(from_handle(handle));
    if (state && state->terminal != nullptr) vterm_free(state->terminal);
}
