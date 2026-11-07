package com.techwithkaushik.cppide.ipc;

import com.techwithkaushik.cppide.ipc.ILspCallback;

/**
 * LSP process boundary. JSON-RPC framing and clangd stdio are managed by the
 * daemon; callbacks deliver asynchronous protocol messages to UI-side clients.
 */
interface ILspService {
    int getProtocolVersion();
    String startServer(String workspaceUri, String languageId, ILspCallback callback);
    void sendMessage(String serverId, long requestId, String jsonRpcMessage);
    void cancelRequest(String serverId, long requestId);
    void stopServer(String serverId);
    void registerCallback(ILspCallback callback);
    void unregisterCallback(ILspCallback callback);
}
