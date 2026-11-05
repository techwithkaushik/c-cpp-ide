#include <jni.h>
#include <vterm.h>

#include <algorithm>
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
        (static_cast<size_t>(state->columns) * 24U + 1U) + 1U;
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


// Stable, allocation-bounded cell snapshot for the Kotlin renderer.
// Layout: [rows, columns, cursorRow, cursorColumn, cell...]
// Each cell is [codepoint0..5, foregroundRgb, backgroundRgb, attributes, width].
// RGB values are 0xRRGGBB; -1 means terminal default. Attribute bits:
// bold=1, underline=2, italic=4, blink=8, reverse=16, strike=32, conceal=64.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeSnapshot(
    JNIEnv* env, jobject, jlong handle) {
    auto* state = from_handle(handle);
    if (state == nullptr) return env->NewLongArray(0);

    constexpr jsize header_size = 4;
    constexpr jsize cell_fields = 10;
    const jsize cell_count = static_cast<jsize>(state->rows * state->columns);
    const jsize total = header_size + cell_count * cell_fields;
    std::vector<jlong> snapshot(static_cast<size_t>(total), 0);

    VTermPos cursor{};
    vterm_state_get_cursorpos(vterm_obtain_state(state->terminal), &cursor);
    snapshot[0] = state->rows;
    snapshot[1] = state->columns;
    snapshot[2] = cursor.row;
    snapshot[3] = cursor.col;

    for (int row = 0; row < state->rows; ++row) {
        for (int col = 0; col < state->columns; ++col) {
            VTermScreenCell cell{};
            const VTermPos pos{row, col};
            const size_t offset = static_cast<size_t>(
                header_size + (row * state->columns + col) * cell_fields);
            if (!vterm_screen_get_cell(state->screen, pos, &cell)) continue;

            for (int index = 0; index < VTERM_MAX_CHARS_PER_CELL; ++index) {
                snapshot[offset + static_cast<size_t>(index)] =
                    static_cast<jlong>(cell.chars[index]);
            }

            VTermColor foreground = cell.fg;
            VTermColor background = cell.bg;
            const jlong fg = VTERM_COLOR_IS_DEFAULT_FG(&foreground)
                ? -1 : (vterm_screen_convert_color_to_rgb(state->screen, &foreground),
                        static_cast<jlong>((foreground.rgb.red << 16) |
                                           (foreground.rgb.green << 8) |
                                           foreground.rgb.blue));
            const jlong bg = VTERM_COLOR_IS_DEFAULT_BG(&background)
                ? -1 : (vterm_screen_convert_color_to_rgb(state->screen, &background),
                        static_cast<jlong>((background.rgb.red << 16) |
                                           (background.rgb.green << 8) |
                                           background.rgb.blue));
            snapshot[offset + 6] = fg;
            snapshot[offset + 7] = bg;

            jlong attributes = 0;
            if (cell.attrs.bold) attributes |= 1;
            if (cell.attrs.underline) attributes |= 2;
            if (cell.attrs.italic) attributes |= 4;
            if (cell.attrs.blink) attributes |= 8;
            if (cell.attrs.reverse) attributes |= 16;
            if (cell.attrs.strike) attributes |= 32;
            if (cell.attrs.conceal) attributes |= 64;
            snapshot[offset + 8] = attributes;
            snapshot[offset + 9] = cell.width;
        }
    }

    jlongArray result = env->NewLongArray(total);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, total, snapshot.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeVTermScreen_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    std::unique_ptr<NativeVTerm> state(from_handle(handle));
    if (state && state->terminal != nullptr) vterm_free(state->terminal);
}
