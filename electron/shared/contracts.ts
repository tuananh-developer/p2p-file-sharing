export type BackendState = "STARTING" | "READY" | "FAILED" | "STOPPED";

export interface BackendStatusSnapshot {
  status: BackendState;
  error?: string;
}

export interface PeerSnapshot {
  peerId: string;
  status: "STOPPED" | "STARTING" | "RUNNING" | "STOPPING";
  bindAddress: string;
  port: number;
}

export interface RemotePeerSnapshot {
  peerId: string;
  host: string;
  port: number;
  direction: "INCOMING" | "OUTGOING";
  status: "CONNECTING" | "CONNECTED" | "DISCONNECTED";
  connectedAt: string;
}

export interface SharedDirectorySnapshot {
  configured: boolean;
  path: string | null;
}

export interface SharedFileSnapshot {
  fileName: string;
  size: number;
}

export interface RemoteFileSearchResult extends SharedFileSnapshot {
  peerId: string;
  host: string;
  port: number;
}

export interface FileSearchError {
  peerId: string | null;
  code: string;
  message: string;
}

export interface FileSearchResponse {
  results: RemoteFileSearchResult[];
  errors: FileSearchError[];
}

export interface DownloadAccepted {
  taskId: string;
  targetFileName: string;
  status: TransferStatus;
}

export type TransferStatus =
  | "QUEUED"
  | "CONNECTING"
  | "TRANSFERRING"
  | "VERIFYING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export interface TransferSnapshot {
  taskId: string;
  peerId: string;
  fileName: string;
  targetFileName: string;
  status: TransferStatus;
  receivedBytes: number;
  totalBytes: number;
  detail: string;
}

export interface BackendEvent {
  event: string;
  data: Record<string, unknown>;
}

export interface P2PBridgeApi {
  backend: {
    getStatus(): Promise<BackendStatusSnapshot>;
    restart(): Promise<BackendStatusSnapshot>;
  };
  peer: {
    start(input: { bindAddress: string; port: number }): Promise<PeerSnapshot>;
    stop(): Promise<PeerSnapshot>;
    getStatus(): Promise<PeerSnapshot>;
    list(): Promise<RemotePeerSnapshot[]>;
    connect(input: { host: string; port: number }): Promise<RemotePeerSnapshot>;
    disconnect(peerId: string): Promise<RemotePeerSnapshot>;
  };
  share: {
    chooseAndSetDirectory(): Promise<SharedDirectorySnapshot | null>;
    getDirectory(): Promise<SharedDirectorySnapshot>;
    listLocalFiles(): Promise<SharedFileSnapshot[]>;
  };
  files: {
    listRemote(peerId: string): Promise<RemoteFileSearchResult[]>;
    search(keyword: string): Promise<FileSearchResponse>;
  };
  transfer: {
    download(peerId: string, fileName: string): Promise<DownloadAccepted | null>;
    cancel(taskId: string): Promise<TransferSnapshot>;
    list(): Promise<TransferSnapshot[]>;
  };
  onEvent(listener: (event: BackendEvent) => void): () => void;
}
