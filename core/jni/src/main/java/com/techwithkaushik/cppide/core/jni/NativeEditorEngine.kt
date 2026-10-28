package com.techwithkaushik.cppide.core.jni

/**
 * Small, deterministic native editor primitives.
 *
 * The Android/Compose layer owns UI and lifecycle; native code owns these
 * CPU-bound text helpers. Keep this API narrow so implementations can be
 * unit-tested independently and later expanded without coupling UI to JNI.
 */
class NativeEditorEngine {
    init {
        System.loadLibrary("cppide_native")
    }

    external fun nativeEngineVersion(): String
    external fun nativeCountLines(source: String): Int
    external fun nativeFindMatchingBracket(source: String, cursorUtf16Offset: Int): Int
    external fun nativeDetectLanguage(fileName: String): String
}
