package com.techwithkaushik.cppide.ipc;

import com.techwithkaushik.cppide.ipc.ICompilerCallback;

/**
 * Process orchestration API. Implementations must validate workspace paths and
 * never execute requests on the Binder thread; stream output through callbacks.
 */
interface ICompilerService {
    int getProtocolVersion();
    String getToolchainStatusJson();
    String startCompile(String workspaceUri, String sourceRelativePath, String argumentsJson, ICompilerCallback callback);
    String startProgram(String workspaceUri, String executableRelativePath, String argumentsJson, ICompilerCallback callback);
    void sendInput(String sessionId, String input);
    void sendSignal(String sessionId, int signal);
    void cancel(String sessionId);
    void registerCallback(ICompilerCallback callback);
    void unregisterCallback(ICompilerCallback callback);
}
