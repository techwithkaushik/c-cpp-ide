package com.techwithkaushik.cppide.ipc;

/** Streaming compiler, execution and debugger events back to the client process. */
oneway interface ICompilerCallback {
    void onOutput(String sessionId, int stream, String chunk);
    void onExit(String sessionId, int exitCode);
    void onError(String sessionId, int errorCode, String message);
}
