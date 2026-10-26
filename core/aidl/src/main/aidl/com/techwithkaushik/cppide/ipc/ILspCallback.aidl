package com.techwithkaushik.cppide.ipc;

/** Asynchronous JSON-RPC messages and lifecycle notifications from the LSP host. */
oneway interface ILspCallback {
    void onMessage(String serverId, long requestId, String json);
    void onServerStateChanged(String serverId, int state, String detail);
}
