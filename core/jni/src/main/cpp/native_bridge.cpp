#include <jni.h>

#include <algorithm>
#include <cctype>
#include <string>
#include <string_view>

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
    char open = '\0';
    char close = '\0';
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
        // When scanning backward, the encountered closing bracket is the
        // opening side of the scan and vice versa.
        if (direction > 0) {
            if (c == open) ++depth;
            else if (c == close) --depth;
        } else {
            if (c == close) ++depth;
            else if (c == open) --depth;
        }
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
    return static_cast<jint>(1 + std::count(text.begin(), text.end(), '\n'));
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


// Native PTY support for the app-owned Linux runtime. This is independent of any
// third-party terminal emulator: the kernel PTY provides real tty semantics.
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

namespace {
struct PtyProcess {
    int master_fd = -1;
    pid_t child_pid = -1;
    std::mutex mutex;
};

std::mutex g_pty_mutex;
std::unordered_map<jlong, std::shared_ptr<PtyProcess>> g_ptys;
std::atomic<jlong> g_next_pty{1};

std::shared_ptr<PtyProcess> find_pty(jlong handle) {
    std::lock_guard<std::mutex> lock(g_pty_mutex);
    const auto it = g_ptys.find(handle);
    return it == g_ptys.end() ? nullptr : it->second;
}

void throw_state(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativePtyProcess_nativeStart(
    JNIEnv* env, jobject /* thiz */, jstring linker_value, jstring proot_value,
    jstring rootfs_value, jstring shell_value, jstring temp_value) {
    const std::string linker = to_utf8(env, linker_value);
    const std::string proot = to_utf8(env, proot_value);
    const std::string rootfs = to_utf8(env, rootfs_value);
    const std::string shell = to_utf8(env, shell_value);
    const std::string temp = to_utf8(env, temp_value);
    if (linker.empty() || proot.empty() || rootfs.empty() || shell.empty() || temp.empty()) {
        throw_state(env, "PTY launch arguments must not be empty.");
        return 0;
    }

    const int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        const int error = errno;
        if (master >= 0) close(master);
        throw_state(env, std::string("Could not allocate PTY: ") + std::strerror(error));
        return 0;
    }
    char* slave_name = ptsname(master);
    if (slave_name == nullptr) {
        const int error = errno;
        close(master);
        throw_state(env, std::string("Could not resolve PTY slave: ") + std::strerror(error));
        return 0;
    }
    const std::string slave_path(slave_name);
    const pid_t child = fork();
    if (child < 0) {
        const int error = errno;
        close(master);
        throw_state(env, std::string("Could not fork PTY shell: ") + std::strerror(error));
        return 0;
    }

    if (child == 0) {
        setsid();
        const int slave = open(slave_path.c_str(), O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) != 0) _exit(126);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        setenv("HOME", "/root", 1);
        // PRoot prepares loader files on the Android host before entering the rootfs.
        // /tmp may not exist for an unrooted Android app, so use the app-private host temp path.
        // PRoot uses TMPDIR/PROOT_TMP_DIR for host-side temporary files and
        // some builds also consult TMP. Keep all three on the same existing,
        // app-private writable directory (never Android's missing /tmp).
        setenv("TMPDIR", temp.c_str(), 1);
        setenv("TMP", temp.c_str(), 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("PROOT_TMP_DIR", temp.c_str(), 1);
        // Android 10 / vendor kernels may deliver SIGSYS with PRoot seccomp acceleration.
        // Disable the accelerator for reliable guest execution; correctness takes priority over speed.
        setenv("PROOT_NO_SECCOMP", "1", 1);
        // Android 10+ blocks execve from writable app data. PRoot's loader is
        // packaged beside the host executable in the APK native library directory.
        const size_t proot_slash = proot.find_last_of('/');
        if (proot_slash != std::string::npos) {
            const std::string loader = proot.substr(0, proot_slash + 1) + "libproot-loader.so";
            // Match LinuxRuntimeSmokeTest: always tell PRoot the bundled loader path.
            // access(X_OK) can return false for APK-extracted native libraries on some
            // Android filesystems even though the system linker can load the file.
            setenv("PROOT_LOADER", loader.c_str(), 1);
        }

        std::vector<std::string> arguments = {
            linker, proot, "-r", rootfs, "-w", "/root",
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys", shell, "-i"
        };
        std::vector<char*> argv;
        argv.reserve(arguments.size() + 1);
        for (auto& argument : arguments) argv.push_back(argument.data());
        argv.push_back(nullptr);
        execv(linker.c_str(), argv.data());
        const char* error = std::strerror(errno);
        dprintf(STDERR_FILENO, "Could not execute Android linker: %s\r\n", error);
        _exit(127);
    }

    const jlong handle = g_next_pty.fetch_add(1);
    auto pty = std::make_shared<PtyProcess>();
    pty->master_fd = master;
    pty->child_pid = child;
    {
        std::lock_guard<std::mutex> lock(g_pty_mutex);
        g_ptys.emplace(handle, std::move(pty));
    }
    return handle;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativePtyProcess_nativeRead(
    JNIEnv* env, jobject /* thiz */, jlong handle, jbyteArray buffer) {
    auto pty = find_pty(handle);
    if (!pty || buffer == nullptr) return 0;
    const jsize length = env->GetArrayLength(buffer);
    if (length <= 0) return 0;
    int fd;
    {
        std::lock_guard<std::mutex> lock(pty->mutex);
        fd = pty->master_fd;
    }
    if (fd < 0) return 0;
    std::vector<jbyte> bytes(static_cast<size_t>(length));
    ssize_t count;
    do { count = read(fd, bytes.data(), bytes.size()); } while (count < 0 && errno == EINTR);
    if (count < 0 && (errno == EIO || errno == EBADF)) return 0;
    if (count < 0) return -errno;
    if (count > 0) env->SetByteArrayRegion(buffer, 0, static_cast<jsize>(count), bytes.data());
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativePtyProcess_nativeWrite(
    JNIEnv* env, jobject /* thiz */, jlong handle, jbyteArray buffer) {
    auto pty = find_pty(handle);
    if (!pty || buffer == nullptr) return -EBADF;
    const jsize length = env->GetArrayLength(buffer);
    if (length <= 0) return 0;
    int fd;
    {
        std::lock_guard<std::mutex> lock(pty->mutex);
        fd = pty->master_fd;
    }
    if (fd < 0) return -EBADF;
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    if (bytes == nullptr) return -ENOMEM;
    ssize_t count;
    do { count = write(fd, bytes, static_cast<size_t>(length)); } while (count < 0 && errno == EINTR);
    const int error = errno;
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
    return count < 0 ? -error : static_cast<jint>(count);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativePtyProcess_nativeResize(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle, jint columns, jint rows) {
    auto pty = find_pty(handle);
    if (!pty) return JNI_FALSE;
    int fd;
    {
        std::lock_guard<std::mutex> lock(pty->mutex);
        fd = pty->master_fd;
    }
    if (fd < 0) return JNI_FALSE;
    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    return ioctl(fd, TIOCSWINSZ, &size) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativePtyProcess_nativeWait(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto pty = find_pty(handle);
    if (!pty) return -1;
    int status = 0;
    pid_t result;
    do { result = waitpid(pty->child_pid, &status, 0); } while (result < 0 && errno == EINTR);
    int exit_code = result < 0 ? -errno : WIFEXITED(status) ? WEXITSTATUS(status) : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1;
    {
        std::lock_guard<std::mutex> lock(pty->mutex);
        if (pty->master_fd >= 0) close(pty->master_fd);
        pty->master_fd = -1;
    }
    {
        std::lock_guard<std::mutex> lock(g_pty_mutex);
        g_ptys.erase(handle);
    }
    return exit_code;
}

extern "C" JNIEXPORT void JNICALL
Java_com_techwithkaushik_cppide_core_jni_NativePtyProcess_nativeClose(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto pty = find_pty(handle);
    if (!pty) return;
    int fd;
    pid_t child;
    {
        std::lock_guard<std::mutex> lock(pty->mutex);
        fd = pty->master_fd;
        pty->master_fd = -1;
        child = pty->child_pid;
    }
    if (fd >= 0) close(fd);
    if (child > 0) kill(-child, SIGTERM);
}
