import { contextBridge, ipcRenderer } from "electron";
import type { BackendEvent, P2PBridgeApi } from "./shared/contracts";

const api: P2PBridgeApi = {
  backend: {
    getStatus: () => ipcRenderer.invoke("backend:status"),
    restart: () => ipcRenderer.invoke("backend:restart")
  },
  peer: {
    start: (input) => ipcRenderer.invoke("peer:start", input),
    stop: () => ipcRenderer.invoke("peer:stop"),
    getStatus: () => ipcRenderer.invoke("peer:status"),
    list: () => ipcRenderer.invoke("peer:list"),
    connect: (input) => ipcRenderer.invoke("peer:connect", input),
    disconnect: (peerId) => ipcRenderer.invoke("peer:disconnect", peerId)
  },
  security: {
    getStatus: () => ipcRenderer.invoke("security:status"),
    trustPeer: (fingerprint) => ipcRenderer.invoke("security:trust-peer", fingerprint),
    untrustPeer: (fingerprint) => ipcRenderer.invoke("security:untrust-peer", fingerprint)
  },
  share: {
    chooseAndSetDirectory: () => ipcRenderer.invoke("share:choose-directory"),
    getDirectory: () => ipcRenderer.invoke("share:get-directory"),
    listLocalFiles: () => ipcRenderer.invoke("share:list-local-files")
  },
  files: {
    listRemote: (peerId) => ipcRenderer.invoke("files:list-remote", peerId),
    search: (keyword) => ipcRenderer.invoke("files:search", keyword)
  },
  transfer: {
    download: (peerId, fileName) => ipcRenderer.invoke("transfer:download", { peerId, fileName }),
    downloadMulti: (peerIds, fileName) => ipcRenderer.invoke("transfer:download-multi", { peerIds, fileName }),
    cancel: (taskId) => ipcRenderer.invoke("transfer:cancel", taskId),
    list: () => ipcRenderer.invoke("transfer:list"),
    listResumable: () => ipcRenderer.invoke("transfer:list-resumable"),
    resume: (taskId, peerIds) => ipcRenderer.invoke("transfer:resume", { taskId, peerIds })
  },
  internet: {
    openSharedFile: (fileName) => ipcRenderer.invoke("internet:open-shared-file", fileName),
    readSharedFileChunk: (input) => ipcRenderer.invoke("internet:read-shared-file-chunk", input),
    closeSharedFile: (handleId) => ipcRenderer.invoke("internet:close-shared-file", handleId),
    beginReceive: (input) => ipcRenderer.invoke("internet:begin-receive", input),
    writeReceivedChunk: (input) => ipcRenderer.invoke("internet:write-received-chunk", input),
    completeReceive: (input) => ipcRenderer.invoke("internet:complete-receive", input),
    abortReceive: (handleId) => ipcRenderer.invoke("internet:abort-receive", handleId)
  },
  onEvent: (listener) => {
    const handler = (_event: Electron.IpcRendererEvent, payload: BackendEvent) => listener(payload);
    ipcRenderer.on("p2p:event", handler);
    return () => ipcRenderer.removeListener("p2p:event", handler);
  }
};

contextBridge.exposeInMainWorld("p2p", api);
