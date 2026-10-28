#include <jni.h>

#include <algorithm>
#include <cctype>
#include <string>
#include <string_view>
#include <vector>

namespace {

std::string to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return value;
}

jint matching_bracket(std::string_view text, jint cursor) {
    if (cursor < 0 || static_cast<size_t>(cursor) >= text.size()) return -1;
    const char current = text[static_cast<size_t>(cursor)];
    char open = '\\0';
    char close = '\\0';
    int direction = 0;
    if (current == '(' || current == '[' || current == '{') {
        open = current;
        close = current == '(' ? ')' : current == '[' ? ']' : '}';
        direction = 1;
    } else if (current == ')' || current == ']' || current == '}') {
        close = current;
        open = current == ')' ? '(' : current == ']' ? '[' : '{';
        direction = -1;
    } else {
        return -1;
    }

    int depth = 0;
    for (jint i = cursor; i >= 0 && static_cast<size_t>(i) < text.size(); i += direction) {
        const char c = text[static_cast<size_t>(i)];
        if (c == open) ++depth;
        else if (c == close) --depth;
        if (depth == 0) return i;
    }
    return -1;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    return vm == nullptr ? JNI_ERR : JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeEditorEngine_nativeEngineVersion(
    JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("cppide-native/0.1");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeEditorEngine_nativeCountLines(
    JNIEnv* env, jobject /* thiz */, jstring source) {
    const std::string text = to_utf8(env, source);
    if (text.empty()) return 1;
    return static_cast<jint>(1 + std::count(text.begin(), text.end(), '\\n'));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeEditorEngine_nativeFindMatchingBracket(
    JNIEnv* env, jobject /* thiz */, jstring source, jint cursor) {
    const std::string text = to_utf8(env, source);
    return matching_bracket(text, cursor);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativeEditorEngine_nativeDetectLanguage(
    JNIEnv* env, jobject /* thiz */, jstring file_name) {
    const std::string name = lower_ascii(to_utf8(env, file_name));
    const size_t dot = name.find_last_of('.');
    const std::string ext = dot == std::string::npos ? "" : name.substr(dot + 1);
    const char* language = "plaintext";
    if (ext == "c") language = "c";
    else if (ext == "h") language = "c-header";
    else if (ext == "cc" || ext == "cpp" || ext == "cxx" || ext == "hpp" || ext == "hh") language = "cpp";
    else if (ext == "py") language = "python";
    else if (ext == "js" || ext == "mjs" || ext == "cjs") language = "javascript";
    else if (ext == "ts" || ext == "tsx") language = "typescript";
    else if (ext == "html" || ext == "htm") language = "html";
    else if (ext == "css" || ext == "scss") language = "css";
    else if (ext == "json") language = "json";
    else if (ext == "xml") language = "xml";
    else if (ext == "md" || ext == "markdown") language = "markdown";
    else if (ext == "sh" || ext == "bash") language = "shell";
    else if (ext == "rs") language = "rust";
    else if (ext == "java") language = "java";
    else if (ext == "kt" || ext == "kts") language = "kotlin";
    else if (ext == "go") language = "go";
    else if (ext == "toml") language = "toml";
    else if (ext == "yaml" || ext == "yml") language = "yaml";
    return env->NewStringUTF(language);
}
