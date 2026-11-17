import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import type {
  BackendEvent,
  BackendStatusSnapshot,
  FileSearchResponse,
  PeerSnapshot,
  RemoteFileSearchResult,
  RemotePeerSnapshot,
  SharedDirectorySnapshot,
  SharedFileSnapshot,
  TransferSnapshot,
  ResumableDownloadSnapshot,
  SecuritySnapshot,
} from "../../electron/shared/contracts";
import {
  InternetPeerSession,
  type InternetIncomingFileOffer,
  type InternetSessionSnapshot,
  type InternetTransferProgress,
} from "./internetPeer";

type WorkspacePage =
  | "home"
  | "my-files"
  | "shared"
  | "devices"
  | "transfers"
  | "recent"
  | "security"
  | "settings";

interface ActivityItem {
  id: string;
  message: string;
  level: string;
  category: string;
  time: string;
}

function App() {
  const [backend, setBackend] = useState<BackendStatusSnapshot>({
    status: "STARTING",
  });
  const [activePage, setActivePage] = useState<WorkspacePage>("home");
  const [fileLayout, setFileLayout] = useState<"list" | "grid">("list");
  const [peer, setPeer] = useState<PeerSnapshot | null>(null);
  const [peers, setPeers] = useState<RemotePeerSnapshot[]>([]);
  const [directory, setDirectory] = useState<SharedDirectorySnapshot | null>(
    null,
  );
  const [localFiles, setLocalFiles] = useState<SharedFileSnapshot[]>([]);
  const [keyword, setKeyword] = useState("");
  const [searchResults, setSearchResults] = useState<RemoteFileSearchResult[]>(
    [],
  );
  const [searchErrors, setSearchErrors] = useState<
    FileSearchResponse["errors"]
  >([]);
  const [transfers, setTransfers] = useState<TransferSnapshot[]>([]);
  const [resumableDownloads, setResumableDownloads] = useState<
    ResumableDownloadSnapshot[]
  >([]);
  const [activities, setActivities] = useState<ActivityItem[]>([]);
  const [security, setSecurity] = useState<SecuritySnapshot | null>(null);
  const [trustedFingerprintInput, setTrustedFingerprintInput] = useState("");
  const [bindAddress, setBindAddress] = useState("0.0.0.0");
  const [listenPort, setListenPort] = useState("0");
  const [remoteHost, setRemoteHost] = useState("");
  const [remotePort, setRemotePort] = useState("5002");
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [notice, setNotice] = useState<{
    kind: "success" | "error" | "info";
    message: string;
  } | null>(null);
  const [signalingUrl, setSignalingUrl] = useState(
    import.meta.env.VITE_SIGNALING_URL ?? "",
  );
  const [inviteInput, setInviteInput] = useState("");
  const [internetState, setInternetState] = useState<InternetSessionSnapshot>({
    status: "IDLE",
    invite: null,
    authenticationCode: null,
    localConfirmed: false,
    remoteConfirmed: false,
    secure: false,
    detail: "No Internet peer session.",
  });
  const [incomingInternetFile, setIncomingInternetFile] =
    useState<InternetIncomingFileOffer | null>(null);
  const [internetProgress, setInternetProgress] =
    useState<InternetTransferProgress | null>(null);
  const internetSession = useRef<InternetPeerSession | null>(null);

  const addActivity = useCallback(
    (
      message: string,
      level = "INFO",
      timestamp?: string,
      id?: string,
      category = "APPLICATION",
    ) => {
      const parsedTime = timestamp ? new Date(timestamp) : new Date();
      const time = Number.isNaN(parsedTime.getTime())
        ? new Date().toLocaleTimeString()
        : parsedTime.toLocaleTimeString();
      setActivities((current) => {
        const entryId = id && id.length > 0 ? id : crypto.randomUUID();
        if (current.some((item) => item.id === entryId)) {
          return current;
        }
        return [
          {
            id: entryId,
            message,
            level,
            category,
            time,
          },
          ...current,
        ].slice(0, 100);
      });
    },
    [],
  );

  const refreshAll = useCallback(async () => {
    const status = await window.p2p.backend.getStatus();
    setBackend(status);
    if (status.status !== "READY") {
      setPeer(null);
      setPeers([]);
      setDirectory(null);
      setLocalFiles([]);
      setTransfers([]);
      setSecurity(null);
      return;
    }

    const [peerState, knownPeers, shareState, transferList, securityState] =
      await Promise.all([
        window.p2p.peer.getStatus().catch(() => null),
        window.p2p.peer.list().catch(() => []),
        window.p2p.share.getDirectory().catch(() => null),
        window.p2p.transfer.list().catch(() => []),
        window.p2p.security.getStatus().catch(() => null),
      ]);

    setPeer(peerState);
    setPeers(knownPeers);
    setDirectory(shareState);
    setTransfers(transferList);
    setSecurity(securityState);

    if (shareState?.configured) {
      const files = await window.p2p.share.listLocalFiles().catch(() => []);
      setLocalFiles(files);
    } else {
      setLocalFiles([]);
    }
  }, []);

  useEffect(() => {
    let active = true;

    const handleEvent = (event: BackendEvent) => {
      const data = event.data;
      switch (event.event) {
        case "backendProcessStatusChanged": {
          const status = data.status;
          if (
            status === "READY" ||
            status === "FAILED" ||
            status === "STOPPED" ||
            status === "STARTING"
          ) {
            setBackend({
              status,
              error: typeof data.error === "string" ? data.error : undefined,
            });
            if (status === "READY") {
              void refreshAll().catch((error: unknown) =>
                addActivity(errorMessage(error), "ERROR"),
              );
            } else {
              setPeer(null);
              setPeers([]);
              setDirectory(null);
              setLocalFiles([]);
              setTransfers([]);
            }
          }
          break;
        }
        case "backendProcessError":
          setBackend({
            status: "FAILED",
            error:
              typeof data.message === "string"
                ? data.message
                : "Java backend process failed.",
          });
          setPeer(null);
          setPeers([]);
          setDirectory(null);
          setLocalFiles([]);
          setTransfers([]);
          addActivity("Java backend process failed.", "ERROR");
          break;
        case "backendStatusChanged":
          if (data.status === "READY") {
            void refreshAll().catch((error: unknown) =>
              addActivity(errorMessage(error), "ERROR"),
            );
          }
          break;
        case "peerStatusChanged":
          void window.p2p.peer
            .getStatus()
            .then(setPeer)
            .catch(() => setPeer(null));
          break;
        case "connectionStatusChanged":
          void window.p2p.peer
            .list()
            .then(setPeers)
            .catch(() => setPeers([]));
          break;
        case "sharedDirectoryChanged": {
          const next = data as unknown as SharedDirectorySnapshot;
          setDirectory(next);
          if (next.configured) {
            void window.p2p.share
              .listLocalFiles()
              .then(setLocalFiles)
              .catch(() => setLocalFiles([]));
          } else {
            setLocalFiles([]);
          }
          break;
        }
        case "transferProgress":
          setTransfers((current) => mergeTransferProgress(current, data));
          break;
        case "transferStatusChanged":
          setTransfers((current) => mergeTransferSnapshot(current, data));
          break;
        case "activityLogged":
          addActivity(
            typeof data.message === "string"
              ? data.message
              : "Activity event received.",
            typeof data.level === "string" ? data.level : "INFO",
            typeof data.timestamp === "string" ? data.timestamp : undefined,
            typeof data.id === "string" ? data.id : undefined,
            typeof data.category === "string" ? data.category : "APPLICATION",
          );
          break;
        case "backendProcessExited":
          if (data.expected !== true) {
            setBackend({
              status: "FAILED",
              error: "Java backend exited unexpectedly.",
            });
            setPeer(null);
            setPeers([]);
            setDirectory(null);
            setLocalFiles([]);
            setTransfers([]);
            addActivity("Java backend exited unexpectedly.", "ERROR");
          }
          break;
        default:
          break;
      }
    };

    const unsubscribe = window.p2p.onEvent(handleEvent);
    void refreshAll().catch((error: unknown) => {
      if (active) {
        setBackend({ status: "FAILED", error: errorMessage(error) });
      }
    });

    return () => {
      active = false;
      unsubscribe();
    };
  }, [addActivity, refreshAll]);

  const connectedPeers = useMemo(
    () => peers.filter((item) => item.status === "CONNECTED"),
    [peers],
  );

  const runAction = useCallback(
    async (key: string, action: () => Promise<void>) => {
      setBusy((current) => ({ ...current, [key]: true }));
      setNotice(null);
      try {
        await action();
      } catch (error) {
        const message = errorMessage(error);
        setNotice({ kind: "error", message });
        addActivity(message, "ERROR");
      } finally {
        setBusy((current) => ({ ...current, [key]: false }));
      }
    },
    [addActivity],
  );

  const handleStartPeer = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const port = parseListeningPort(listenPort);
    if (port === null) {
      setNotice({
        kind: "error",
        message:
          "Listening port must be between 0 and 65535. Use 0 for automatic allocation.",
      });
      return;
    }
    void runAction("peer-start", async () => {
      const snapshot = await window.p2p.peer.start({ bindAddress, port });
      setPeer(snapshot);
      const reachableAddresses = snapshot.addresses.map(
        (address) => `${address}:${snapshot.port}`,
      );
      setNotice({
        kind: "success",
        message:
          reachableAddresses.length > 0
            ? `Peer is listening. Share one of these addresses: ${reachableAddresses.join(", ")}.`
            : `Peer is listening on ${snapshot.bindAddress}:${snapshot.port}.`,
      });
    });
  };

  const handleTrustFingerprint = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const fingerprint = trustedFingerprintInput.trim();
    if (!fingerprint) {
      setNotice({
        kind: "error",
        message: "Enter the other device's SHA-256 certificate fingerprint.",
      });
      return;
    }
    void runAction("security-trust", async () => {
      const snapshot = await window.p2p.security.trustPeer(fingerprint);
      setSecurity(snapshot);
      setTrustedFingerprintInput("");
      setNotice({
        kind: "success",
        message: "Peer fingerprint added to the local trust list.",
      });
    });
  };

  const handleUntrustFingerprint = (fingerprint: string) => {
    void runAction("security-untrust-" + fingerprint, async () => {
      const snapshot = await window.p2p.security.untrustPeer(fingerprint);
      setSecurity(snapshot);
      setNotice({
        kind: "info",
        message:
          "Peer fingerprint removed. Future TLS connections from that identity will be rejected.",
      });
    });
  };

  const handleStopPeer = () => {
    void runAction("peer-stop", async () => {
      const snapshot = await window.p2p.peer.stop();
      setPeer(snapshot);
      setPeers([]);
      setNotice({ kind: "success", message: "Local peer stopped." });
    });
  };

  const handleConnect = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const host = remoteHost.trim();
    if (!host) {
      setNotice({
        kind: "error",
        message: "Enter the other device's LAN or VPN IP address.",
      });
      return;
    }
    const port = parsePort(remotePort);
    if (port === null) {
      setNotice({
        kind: "error",
        message: "Remote port must be between 1 and 65535.",
      });
      return;
    }
    void runAction("peer-connect", async () => {
      const connected = await window.p2p.peer.connect({
        host,
        port,
      });
      setPeers(await window.p2p.peer.list());
      setNotice({
        kind: "success",
        message: "Connected to " + connected.host + ":" + connected.port + ".",
      });
    });
  };

  const handleDisconnect = (peerId: string) => {
    void runAction("peer-disconnect-" + peerId, async () => {
      await window.p2p.peer.disconnect(peerId);
      setPeers(await window.p2p.peer.list());
      setNotice({ kind: "success", message: "Peer disconnected." });
    });
  };

  const handleChooseDirectory = () => {
    void runAction("share-directory", async () => {
      const result = await window.p2p.share.chooseAndSetDirectory();
      if (!result) {
        return;
      }
      setDirectory(result);
      setLocalFiles(await window.p2p.share.listLocalFiles());
      setNotice({ kind: "success", message: "Shared directory updated." });
    });
  };

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void runAction("file-search", async () => {
      const result = await window.p2p.files.search(keyword);
      setSearchResults(result.results);
      setSearchErrors(result.errors);
      setNotice({
        kind: "success",
        message:
          result.results.length +
          " file result(s) from " +
          new Set(result.results.map((item) => item.peerId)).size +
          " peer(s).",
      });
    });
  };

  const matchingSourceIds = (file: RemoteFileSearchResult) =>
    [
      ...new Set(
        searchResults
          .filter(
            (candidate) =>
              candidate.fileName === file.fileName &&
              candidate.size === file.size,
          )
          .map((candidate) => candidate.peerId),
      ),
    ].slice(0, 16);

  const handleDownload = (file: RemoteFileSearchResult) => {
    const sourcePeerIds = matchingSourceIds(file);
    void runAction("download-" + file.fileName, async () => {
      const accepted =
        sourcePeerIds.length > 1
          ? await window.p2p.transfer.downloadMulti(
              sourcePeerIds,
              file.fileName,
            )
          : await window.p2p.transfer.download(file.peerId, file.fileName);
      if (!accepted) {
        return;
      }
      setTransfers(await window.p2p.transfer.list());
      setNotice({
        kind: "success",
        message:
          "Download queued as " +
          accepted.targetFileName +
          (sourcePeerIds.length > 1
            ? " using " + sourcePeerIds.length + " candidate peer(s)."
            : "."),
      });
    });
  };

  const handleDownloadFromThisPeer = (file: RemoteFileSearchResult) => {
    void runAction(
      "download-single-" + file.peerId + "-" + file.fileName,
      async () => {
        const accepted = await window.p2p.transfer.download(
          file.peerId,
          file.fileName,
        );
        if (!accepted) {
          return;
        }
        setTransfers(await window.p2p.transfer.list());
        setNotice({
          kind: "success",
          message: "Download queued as " + accepted.targetFileName + ".",
        });
      },
    );
  };

  const handleFindResumable = () => {
    void runAction("transfer-resumable", async () => {
      const found = await window.p2p.transfer.listResumable();
      setResumableDownloads(found ?? []);
      addActivity("Scanned the selected folder for interrupted downloads.");
      setNotice({
        kind: "info",
        message:
          found === null
            ? "Folder selection cancelled."
            : found.length === 0
              ? "No valid interrupted downloads were found."
              : "Found " + found.length + " interrupted download(s).",
      });
    });
  };

  const handleResumeTransfer = (taskId: string) => {
    if (connectedPeers.length === 0) {
      setNotice({
        kind: "error",
        message: "Connect to at least one source peer before resuming.",
      });
      return;
    }
    void runAction("resume-" + taskId, async () => {
      await window.p2p.transfer.resume(
        taskId,
        connectedPeers.map((item) => item.peerId),
      );
      setTransfers(await window.p2p.transfer.list());
      setResumableDownloads((current) =>
        current.filter((item) => item.taskId !== taskId),
      );
      setNotice({
        kind: "success",
        message:
          "Resume queued. Sources will be checked against the saved SHA-256.",
      });
    });
  };

  const handleCancelTransfer = (taskId: string) => {
    void runAction("cancel-" + taskId, async () => {
      await window.p2p.transfer.cancel(taskId);
      setTransfers(await window.p2p.transfer.list());
      setNotice({
        kind: "success",
        message: "Transfer cancellation requested.",
      });
    });
  };

  const handleRestartBackend = () => {
    void runAction("backend-restart", async () => {
      const status = await window.p2p.backend.restart();
      setBackend(status);
      if (status.status !== "READY") {
        throw new Error(status.error ?? "Java backend did not become ready.");
      }
      await refreshAll();
      setNotice({ kind: "success", message: "Java backend is ready." });
    });
  };

  const createInternetSession = (): InternetPeerSession => {
    if (!peer?.peerId) {
      throw new Error(
        "The local peer identity is not available. Check that the Java backend is ready.",
      );
    }
    if (internetSession.current) {
      throw new Error(
        "Close the current Internet session before starting another one.",
      );
    }

    const session = new InternetPeerSession(
      peer.peerId,
      {
        openSharedFile: (fileName) =>
          window.p2p.internet.openSharedFile(fileName),
        readSharedFileChunk: (input) =>
          window.p2p.internet.readSharedFileChunk(input),
        closeSharedFile: (handleId) =>
          window.p2p.internet.closeSharedFile(handleId),
        beginReceive: (input) => window.p2p.internet.beginReceive(input),
        writeReceivedChunk: (input) =>
          window.p2p.internet.writeReceivedChunk(input),
        completeReceive: (input) => window.p2p.internet.completeReceive(input),
        abortReceive: (handleId) => window.p2p.internet.abortReceive(handleId),
      },
      {
        onState: setInternetState,
        onIncomingFile: setIncomingInternetFile,
        onProgress: setInternetProgress,
        onError: (message) =>
          addActivity(message, "WARN", undefined, undefined, "INTERNET"),
      },
    );
    internetSession.current = session;
    return session;
  };

  const handleCreateInternetInvite = () => {
    void runAction("internet-create", async () => {
      const session = createInternetSession();
      const invite = await session.createInvite(signalingUrl);
      setNotice({
        kind: "success",
        message:
          "Invite created. Send the complete code to the other peer through a trusted channel.",
      });
    });
  };

  const handleJoinInternetInvite = () => {
    void runAction("internet-join", async () => {
      const session = createInternetSession();
      await session.joinInvite(inviteInput);
      setNotice({
        kind: "success",
        message: "Joined the rendezvous room. Waiting for ICE connectivity.",
      });
    });
  };

  const handleConfirmInternetIdentity = () => {
    void runAction("internet-confirm", async () => {
      if (!internetSession.current)
        throw new Error("No Internet session is active.");
      internetSession.current.confirmSafetyCode();
      setNotice({
        kind: "info",
        message:
          "Your side confirmed the safety code. File transfer unlocks after the other peer confirms too.",
      });
    });
  };

  const handleSendInternetFile = (fileName: string) => {
    void runAction("internet-send-" + fileName, async () => {
      if (!internetSession.current)
        throw new Error("No Internet session is active.");
      await internetSession.current.sendFile(fileName);
      setNotice({
        kind: "success",
        message: "File offer sent to the remote peer.",
      });
    });
  };

  const handleAcceptInternetFile = () => {
    void runAction("internet-accept-file", async () => {
      if (!internetSession.current)
        throw new Error("No Internet session is active.");
      await internetSession.current.acceptIncomingFile();
      setNotice({
        kind: "success",
        message: "Download destination selected. Receiving file.",
      });
    });
  };

  const handleRejectInternetFile = () => {
    void runAction("internet-reject-file", async () => {
      internetSession.current?.rejectIncomingFile();
      setNotice({ kind: "info", message: "Incoming file offer rejected." });
    });
  };

  const handleCancelInternetTransfer = () => {
    void runAction("internet-cancel-transfer", async () => {
      await internetSession.current?.cancelTransfer();
      setNotice({ kind: "info", message: "Internet file transfer cancelled." });
    });
  };

  const handleCloseInternetSession = () => {
    void runAction("internet-close", async () => {
      const session = internetSession.current;
      internetSession.current = null;
      await session?.close();
      setInternetState({
        status: "CLOSED",
        invite: null,
        authenticationCode: null,
        localConfirmed: false,
        remoteConfirmed: false,
        secure: false,
        detail: "Internet peer session closed.",
      });
      setIncomingInternetFile(null);
      setInternetProgress(null);
    });
  };

  const title = (
    {
      home: "Overview",
      "my-files": "Local library",
      shared: "Find files",
      devices: "Network",
      transfers: "Transfers",
      recent: "Activity log",
      security: "Trust & security",
      settings: "Preferences",
    } as const
  )[activePage];
  const activeTransferCount = transfers.filter(
    (item) =>
      !["COMPLETED", "FAILED", "CANCELLED", "PAUSED"].includes(item.status),
  ).length;
  const completedTransfers = transfers.filter(
    (item) => item.status === "COMPLETED",
  ).length;

  return (
    <div className="mesh-app">
      <header className="mesh-topbar">
        <button
          className="mesh-brand"
          type="button"
          onClick={() => setActivePage("home")}
          aria-label="Mesh home"
        >
          <span className="mesh-mark">
            <i />
            <i />
            <i />
          </span>
          <span className="mesh-brand-copy">
            <b>MESH</b>
            <small>PRIVATE FILE NETWORK</small>
          </span>
        </button>
        <form
          className="mesh-search"
          onSubmit={(event) => {
            handleSearch(event);
            setActivePage("shared");
          }}
        >
          <Icon name="search" />
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="Search across connected devices…"
            aria-label="Search files on connected devices"
            disabled={backend.status !== "READY"}
          />
          {keyword && (
            <button
              type="button"
              className="icon-button"
              onClick={() => setKeyword("")}
              aria-label="Clear search"
            >
              <Icon name="x" />
            </button>
          )}
          <kbd>↵</kbd>
        </form>
        <div className="mesh-top-actions">
          <span className={"engine-status " + backend.status.toLowerCase()}>
            <i />
            {backend.status === "READY" ? "Engine online" : backend.status}
          </span>
          <button
            className="icon-button"
            type="button"
            onClick={() => void refreshAll()}
            disabled={backend.status !== "READY"}
            title="Refresh network"
          >
            <Icon name="refresh" />
          </button>
          <span className="mesh-avatar">M</span>
        </div>
      </header>

      <div className="mesh-layout">
        <aside className="mesh-sidebar">
          <div className="side-section-label">WORKSPACE</div>
          <nav className="side-nav" aria-label="Workspace navigation">
            {(
              [
                { id: "home", label: "Overview", icon: "home" },
                { id: "my-files", label: "Local library", icon: "folder" },
                { id: "shared", label: "Find files", icon: "search" },
                { id: "devices", label: "Network", icon: "nodes" },
                { id: "transfers", label: "Transfers", icon: "transfer" },
              ] as const
            ).map((item) => (
              <button
                key={item.id}
                className={
                  "nav-item " + (activePage === item.id ? "active" : "")
                }
                type="button"
                onClick={() => setActivePage(item.id)}
              >
                <Icon name={item.icon} />
                <span>{item.label}</span>
                {item.id === "my-files" && localFiles.length > 0 && (
                  <small>{localFiles.length}</small>
                )}
                {item.id === "devices" && connectedPeers.length > 0 && (
                  <small>{connectedPeers.length}</small>
                )}
                {item.id === "transfers" && activeTransferCount > 0 && (
                  <i className="nav-dot" />
                )}
              </button>
            ))}
          </nav>

          <div className="side-section-label side-section-secondary">
            SYSTEM
          </div>
          <nav className="side-nav" aria-label="System navigation">
            <button
              className={
                "nav-item " + (activePage === "recent" ? "active" : "")
              }
              type="button"
              onClick={() => setActivePage("recent")}
            >
              <Icon name="activity" />
              <span>Activity log</span>
            </button>
            <button
              className={
                "nav-item " + (activePage === "security" ? "active" : "")
              }
              type="button"
              onClick={() => setActivePage("security")}
            >
              <Icon name="shield" />
              <span>Trust & security</span>
            </button>
            <button
              className={
                "nav-item " + (activePage === "settings" ? "active" : "")
              }
              type="button"
              onClick={() => setActivePage("settings")}
            >
              <Icon name="settings" />
              <span>Preferences</span>
            </button>
          </nav>

          <div className="sidebar-spacer" />
          <section className="local-node-card">
            <div className="node-card-top">
              <span className="node-pulse" />
              <span>LOCAL NODE</span>
              <b className={peer?.status === "RUNNING" ? "online" : ""}>
                {peer?.status === "RUNNING" ? "ACTIVE" : "IDLE"}
              </b>
            </div>
            <div className="node-card-id">
              {peer?.peerId
                ? peer.peerId.slice(0, 16) + "…"
                : "Node not started"}
            </div>
            <div className="node-card-meta">
              <span>JAVA CORE</span>
              <span>{backend.status}</span>
            </div>
            <button type="button" onClick={() => setActivePage("devices")}>
              Manage node <Icon name="arrow" />
            </button>
          </section>
          <div className="sidebar-footer">
            <span className="footer-mark">M</span>
            <span>
              <b>Mesh workspace</b>
              <small>Direct device-to-device</small>
            </span>
          </div>
        </aside>

        <main className="mesh-main">
          {backend.status !== "READY" && (
            <div className="backend-banner">
              <Icon name="alert" />
              <div>
                <strong>
                  {backend.status === "STARTING"
                    ? "Starting your desktop engine"
                    : "Java Core is unavailable"}
                </strong>
                <p>
                  {backend.error ??
                    "Waiting for the local Java service to become ready."}
                </p>
              </div>
              {backend.status === "FAILED" && (
                <button
                  className="button primary"
                  onClick={handleRestartBackend}
                  disabled={!!busy["backend-restart"]}
                >
                  Try again
                </button>
              )}
            </div>
          )}
          {notice && (
            <div className={"toast " + notice.kind}>
              <Icon
                name={
                  notice.kind === "error"
                    ? "alert"
                    : notice.kind === "success"
                      ? "check"
                      : "info"
                }
              />
              <span>{notice.message}</span>
              <button
                className="icon-button"
                type="button"
                aria-label="Dismiss"
                onClick={() => setNotice(null)}
              >
                <Icon name="x" />
              </button>
            </div>
          )}

          <div className="screen-heading">
            <div>
              <div className="screen-eyebrow">
                <span className="eyebrow-line" />
                {activePage === "home"
                  ? "NETWORK OVERVIEW"
                  : activePage === "my-files"
                    ? "LOCAL STORAGE"
                    : activePage === "shared"
                      ? "PEER DISCOVERY"
                      : activePage === "devices"
                        ? "CONNECTION MANAGER"
                        : activePage === "transfers"
                          ? "TRANSFER CENTER"
                          : activePage === "recent"
                            ? "EVENT STREAM"
                            : activePage === "security"
                              ? "IDENTITY & TRUST"
                              : "SYSTEM CONFIGURATION"}
              </div>
              <h1>{title}</h1>
              <p>
                {activePage === "home"
                  ? "A live view of your private, peer-to-peer workspace."
                  : activePage === "my-files"
                    ? "Files exposed by this device to peers you trust."
                    : activePage === "shared"
                      ? "Discover files available from devices in your network."
                      : activePage === "devices"
                        ? "Bring devices together over LAN, VPN or the public Internet."
                        : activePage === "transfers"
                          ? "Monitor every incoming and outgoing file transfer."
                          : activePage === "recent"
                            ? "A local timeline of connection and transfer events."
                            : activePage === "security"
                              ? "Review the identities allowed to communicate with this device."
                              : "Tune how this device participates in your private network."}
              </p>
            </div>
            <div className="page-actions">
              {activePage === "my-files" && (
                <button
                  className="button primary"
                  onClick={handleChooseDirectory}
                  disabled={backend.status !== "READY"}
                >
                  <Icon name="plus" />
                  Add shared folder
                </button>
              )}
              {activePage === "transfers" && (
                <button
                  className="button secondary"
                  onClick={handleFindResumable}
                  disabled={
                    backend.status !== "READY" || !!busy["transfer-resumable"]
                  }
                >
                  <Icon name="refresh" />
                  Scan for partials
                </button>
              )}
              {activePage === "devices" && (
                <span className="state-chip">
                  <i className={connectedPeers.length ? "online" : ""} />
                  {connectedPeers.length} peers online
                </span>
              )}
            </div>
          </div>

          {activePage === "home" && (
            <div className="page-content">
              <section className="network-hero">
                <div className="hero-copy">
                  <div className="hero-kicker">
                    <span className="hero-kicker-dot" /> PRIVATE BY DESIGN{" "}
                    <span className="hero-kicker-divider">/</span> PEER TO PEER
                  </div>
                  <h2>
                    Your devices.
                    <br />
                    <em>One private network.</em>
                  </h2>
                  <p>
                    Move files directly between trusted devices. Your data
                    travels peer to peer, not through a cloud drive.
                  </p>
                  <div className="hero-actions">
                    <button
                      className="button hero-primary"
                      onClick={() => setActivePage("devices")}
                    >
                      <Icon name="nodes" />
                      Open network <Icon name="arrow" />
                    </button>
                    <button
                      className="button hero-quiet"
                      onClick={() => setActivePage("my-files")}
                    >
                      <Icon name="folder" />
                      Browse library
                    </button>
                  </div>
                </div>
                <div
                  className="topology-panel"
                  aria-label="Network topology illustration"
                >
                  <div className="topology-orbit orbit-outer" />
                  <div className="topology-orbit orbit-inner" />
                  <svg
                    className="topology-lines"
                    viewBox="0 0 420 260"
                    aria-hidden="true"
                  >
                    <path d="M210 130 L91 58 M210 130 L329 58 M210 130 L92 211 M210 130 L330 211" />
                    <circle cx="91" cy="58" r="3" />
                    <circle cx="329" cy="58" r="3" />
                    <circle cx="92" cy="211" r="3" />
                    <circle cx="330" cy="211" r="3" />
                  </svg>
                  <div className="topology-core">
                    <span className="core-halo" />
                    <Icon name="monitor" />
                    <b>THIS DEVICE</b>
                    <small>
                      {peer?.status === "RUNNING"
                        ? "LISTENING"
                        : "NOT LISTENING"}
                    </small>
                  </div>
                  <div className="topology-node node-a">
                    <Icon name="laptop" />
                    <span>
                      {connectedPeers[0] ? "PEER 01" : "PEER SLOT 01"}
                    </span>
                    <small>
                      {connectedPeers[0] ? "CONNECTED" : "AVAILABLE"}
                    </small>
                  </div>
                  <div className="topology-node node-b">
                    <Icon name="phone" />
                    <span>
                      {connectedPeers[1] ? "PEER 02" : "PEER SLOT 02"}
                    </span>
                    <small>
                      {connectedPeers[1] ? "CONNECTED" : "AVAILABLE"}
                    </small>
                  </div>
                  <div className="topology-node node-c">
                    <Icon name="server" />
                    <span>
                      {connectedPeers[2] ? "PEER 03" : "PEER SLOT 03"}
                    </span>
                    <small>
                      {connectedPeers[2] ? "CONNECTED" : "AVAILABLE"}
                    </small>
                  </div>
                  <div className="topology-node node-d">
                    <Icon name="monitor" />
                    <span>
                      {connectedPeers[3] ? "PEER 04" : "PEER SLOT 04"}
                    </span>
                    <small>
                      {connectedPeers[3] ? "CONNECTED" : "AVAILABLE"}
                    </small>
                  </div>
                  <div className="topology-caption">
                    <span className="legend-dot connected" />{" "}
                    {connectedPeers.length} ACTIVE{" "}
                    <span className="legend-dot idle" /> DIRECT LINKS
                  </div>
                </div>
                <div className="hero-status-strip">
                  <span>
                    <i
                      className={backend.status === "READY" ? "status-on" : ""}
                    />{" "}
                    ENGINE <b>{backend.status}</b>
                  </span>
                  <span>
                    TRANSPORT <b>TCP / TLS</b>
                  </span>
                  <span>
                    INTERNET <b>WEBRTC</b>
                  </span>
                  <span>
                    IDENTITY <b>{peer?.peerId ? "ESTABLISHED" : "PENDING"}</b>
                  </span>
                </div>
              </section>

              <section className="metric-grid">
                <button
                  className="metric-card metric-accent"
                  onClick={() => setActivePage("my-files")}
                >
                  <span className="metric-label">LOCAL LIBRARY</span>
                  <b>{localFiles.length.toString().padStart(2, "0")}</b>
                  <small>
                    Files shared from this device <Icon name="arrow" />
                  </small>
                  <span className="metric-glyph">
                    <Icon name="folder" />
                  </span>
                </button>
                <button
                  className="metric-card"
                  onClick={() => setActivePage("devices")}
                >
                  <span className="metric-label">LIVE PEERS</span>
                  <b>{connectedPeers.length.toString().padStart(2, "0")}</b>
                  <small>
                    Devices connected now <Icon name="arrow" />
                  </small>
                  <span className="metric-glyph">
                    <Icon name="nodes" />
                  </span>
                </button>
                <button
                  className="metric-card"
                  onClick={() => setActivePage("transfers")}
                >
                  <span className="metric-label">IN FLIGHT</span>
                  <b>{activeTransferCount.toString().padStart(2, "0")}</b>
                  <small>
                    Transfers in progress <Icon name="arrow" />
                  </small>
                  <span className="metric-glyph">
                    <Icon name="transfer" />
                  </span>
                </button>
                <button
                  className="metric-card"
                  onClick={() => setActivePage("transfers")}
                >
                  <span className="metric-label">COMPLETED</span>
                  <b>{completedTransfers.toString().padStart(2, "0")}</b>
                  <small>
                    Successful transfers <Icon name="arrow" />
                  </small>
                  <span className="metric-glyph">
                    <Icon name="check" />
                  </span>
                </button>
              </section>

              <section className="home-content-grid">
                <article className="surface library-surface">
                  <div className="surface-heading">
                    <div>
                      <span className="surface-overline">ON THIS DEVICE</span>
                      <h2>Shared library</h2>
                      <p>Files your trusted peers can discover.</p>
                    </div>
                    <button
                      className="text-link"
                      onClick={() => setActivePage("my-files")}
                    >
                      Open library <Icon name="arrow" />
                    </button>
                  </div>
                  {!directory?.configured ? (
                    <Empty
                      icon="folder"
                      title="Set up your shared library"
                      detail="Choose a local folder. Only its top-level files will be offered to connected peers."
                      action="Choose shared folder"
                      onAction={handleChooseDirectory}
                    />
                  ) : localFiles.length === 0 ? (
                    <Empty
                      icon="file"
                      title="Nothing in the library yet"
                      detail="Add files to your selected folder to make them available."
                    />
                  ) : (
                    <FileTable
                      files={localFiles.slice(0, 6)}
                      onSend={handleSendInternetFile}
                      canSend={internetState.secure}
                      busy={busy}
                    />
                  )}
                </article>
                <article className="surface activity-surface">
                  <div className="surface-heading">
                    <div>
                      <span className="surface-overline">RECENT SIGNALS</span>
                      <h2>Activity stream</h2>
                      <p>Events from this desktop session.</p>
                    </div>
                    <button
                      className="icon-button"
                      onClick={() => setActivePage("recent")}
                      title="Open activity log"
                    >
                      <Icon name="arrow" />
                    </button>
                  </div>
                  {activities.length === 0 ? (
                    <Empty
                      icon="activity"
                      title="Waiting for activity"
                      detail="Peer discovery, connections and transfers will appear here."
                    />
                  ) : (
                    <ActivityList activities={activities.slice(0, 6)} />
                  )}
                </article>
              </section>
              <section className="surface peer-summary">
                <div className="surface-heading">
                  <div>
                    <span className="surface-overline">NETWORK MEMBERS</span>
                    <h2>Connected peers</h2>
                    <p>Devices currently known to this node.</p>
                  </div>
                  <button
                    className="text-link"
                    onClick={() => setActivePage("devices")}
                  >
                    Manage network <Icon name="arrow" />
                  </button>
                </div>
                {connectedPeers.length === 0 ? (
                  <div className="empty-line">
                    <Icon name="nodes" />
                    No peers connected. Start your node and connect another
                    device to begin.
                  </div>
                ) : (
                  <PeerList
                    peers={connectedPeers}
                    onDisconnect={handleDisconnect}
                    busy={busy}
                  />
                )}
              </section>
            </div>
          )}

          {activePage === "my-files" && (
            <div className="page-content">
              <section className="location-bar">
                <div>
                  <Icon name="folder" />
                  <span>
                    <b>Shared folder</b>
                    <small>{directory?.path ?? "No folder selected"}</small>
                  </span>
                </div>
                <div className="view-actions">
                  <small>{localFiles.length} item(s)</small>
                  <button
                    className={fileLayout === "list" ? "view-active" : ""}
                    onClick={() => setFileLayout("list")}
                    title="List view"
                  >
                    <Icon name="list" />
                  </button>
                  <button
                    className={fileLayout === "grid" ? "view-active" : ""}
                    onClick={() => setFileLayout("grid")}
                    title="Grid view"
                  >
                    <Icon name="grid" />
                  </button>
                </div>
              </section>
              {!directory?.configured ? (
                <Empty
                  icon="folder"
                  title="Your shared folder is waiting"
                  detail="Choose a local folder. Mesh will make its top-level files available to connected devices."
                  action="Choose a folder"
                  onAction={handleChooseDirectory}
                />
              ) : localFiles.length === 0 ? (
                <Empty
                  icon="file"
                  title="This folder is empty"
                  detail="Add files to the folder on your computer."
                />
              ) : fileLayout === "grid" ? (
                <div className="file-grid">
                  {localFiles.map((file) => (
                    <div className="file-tile" key={file.fileName}>
                      <FileTypeIcon name={file.fileName} large />
                      <b title={file.fileName}>{file.fileName}</b>
                      <small>{formatBytes(file.size)}</small>
                    </div>
                  ))}
                </div>
              ) : (
                <section className="surface file-browser">
                  <FileTable
                    files={localFiles}
                    onSend={handleSendInternetFile}
                    canSend={internetState.secure}
                    busy={busy}
                  />
                </section>
              )}
              {directory?.configured && (
                <p className="footnote">
                  <Icon name="shield" />
                  Only files inside this selected folder are exposed to peers.
                  Files elsewhere remain untouched.
                </p>
              )}
            </div>
          )}

          {activePage === "shared" && (
            <div className="page-content">
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Find files on your network</h2>
                    <p>Search connected peers for a file by name.</p>
                  </div>
                  <span className="count-chip">
                    {searchResults.length} results
                  </span>
                </div>
                <form className="search-in-page" onSubmit={handleSearch}>
                  <Icon name="search" />
                  <input
                    value={keyword}
                    onChange={(event) => setKeyword(event.target.value)}
                    placeholder="Try a file name, for example report.pdf"
                    disabled={backend.status !== "READY"}
                  />
                  <button
                    className="button primary"
                    type="submit"
                    disabled={
                      backend.status !== "READY" ||
                      connectedPeers.length === 0 ||
                      !!busy["file-search"]
                    }
                  >
                    {busy["file-search"] ? "Searching…" : "Search devices"}
                  </button>
                </form>
                {searchErrors.length > 0 && (
                  <div className="inline-warning">
                    {searchErrors.length} peer(s) could not be refreshed.
                    Available results are still shown.
                  </div>
                )}
                {connectedPeers.length === 0 && (
                  <div className="empty-line">
                    <Icon name="monitor" />
                    Connect to a device before searching its files.
                    <button
                      className="text-link"
                      onClick={() => setActivePage("devices")}
                    >
                      Go to devices
                    </button>
                  </div>
                )}
                {searchResults.length === 0 ? (
                  <Empty
                    icon="search"
                    title="No files to show yet"
                    detail="Search connected devices to see files available for download."
                  />
                ) : (
                  <div className="remote-table">
                    <div className="table-head">
                      <span>Name</span>
                      <span>Device</span>
                      <span>Size</span>
                      <span>Actions</span>
                    </div>
                    {searchResults.map((file) => (
                      <div
                        className="table-row"
                        key={file.peerId + ":" + file.fileName}
                      >
                        <div className="file-cell">
                          <FileTypeIcon name={file.fileName} />
                          <span>
                            <b>{file.fileName}</b>
                            <small>Available from another device</small>
                          </span>
                        </div>
                        <span className="peer-address">
                          {file.host}:{file.port}
                        </span>
                        <small>{formatBytes(file.size)}</small>
                        <div className="row-actions">
                          <button
                            className="button primary small"
                            onClick={() => handleDownload(file)}
                            disabled={
                              backend.status !== "READY" ||
                              !!busy["download-" + file.fileName]
                            }
                          >
                            <Icon name="download" />
                            {matchingSourceIds(file).length > 1
                              ? "Multi-source"
                              : "Download"}
                          </button>
                          {matchingSourceIds(file).length > 1 && (
                            <button
                              className="icon-button"
                              title="This peer only"
                              onClick={() => handleDownloadFromThisPeer(file)}
                            >
                              <Icon name="more" />
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </div>
          )}

          {activePage === "devices" && (
            <div className="page-content">
              <section className="two-column">
                <article className="surface">
                  <div className="surface-heading">
                    <div>
                      <h2>This device</h2>
                      <p>Manage your local peer listener.</p>
                    </div>
                    <span
                      className={
                        "state-chip " +
                        (peer?.status === "RUNNING" ? "success" : "")
                      }
                    >
                      {peer?.status ?? "UNKNOWN"}
                    </span>
                  </div>
                  <form className="settings-form" onSubmit={handleStartPeer}>
                    <label>
                      Bind address
                      <input
                        value={bindAddress}
                        onChange={(event) => setBindAddress(event.target.value)}
                        placeholder="0.0.0.0"
                        disabled={backend.status !== "READY"}
                      />
                    </label>
                    <label>
                      Listening port
                      <input
                        type="number"
                        min="0"
                        max="65535"
                        value={listenPort}
                        onChange={(event) => setListenPort(event.target.value)}
                        disabled={backend.status !== "READY"}
                      />
                    </label>
                    <small className="hint">
                      Default 0.0.0.0 listens on all local IPv4 interfaces for
                      LAN/VPN peers. Share a reachable address below; only
                      devices with trusted TLS fingerprints can connect.
                      Port 0 lets the operating system choose an available port.
                    </small>
                    <div className="row-actions">
                      <button
                        className="button primary"
                        type="submit"
                        disabled={
                          backend.status !== "READY" ||
                          peer?.status === "RUNNING" ||
                          peer?.status === "STARTING" ||
                          peer?.status === "STOPPING" ||
                          !!busy["peer-start"]
                        }
                      >
                        {busy["peer-start"] ? "Starting…" : "Start peer"}
                      </button>
                      <button
                        className="button secondary"
                        type="button"
                        onClick={handleStopPeer}
                        disabled={
                          peer?.status !== "RUNNING" || !!busy["peer-stop"]
                        }
                      >
                        Stop
                      </button>
                    </div>
                  </form>
                  <div className="detail-line">
                    <small>Peer identity</small>
                    <code>{peer?.peerId ?? "Not started"}</code>
                  </div>
                  <div className="detail-line">
                    <small>Reachable addresses</small>
                    <div className="address-list">
                      {peer?.status === "RUNNING" &&
                      peer.addresses.length > 0 ? (
                        peer.addresses.map((address) => (
                          <code key={address}>
                            {address}:{peer.port}
                          </code>
                        ))
                      ) : (
                        <small>Start your peer to see addresses.</small>
                      )}
                    </div>
                  </div>
                </article>
                <article className="surface" id="connect-device">
                  <div className="surface-heading">
                    <div>
                      <h2>Connect to a device</h2>
                      <p>Connect over a reachable LAN or VPN address.</p>
                    </div>
                    <span className="count-chip">
                      {connectedPeers.length} online
                    </span>
                  </div>
                  <form className="settings-form" onSubmit={handleConnect}>
                    <label>
                      Host or IP address
                      <input
                        value={remoteHost}
                        onChange={(event) => setRemoteHost(event.target.value)}
                        placeholder="192.168.1.10"
                        disabled={backend.status !== "READY"}
                      />
                    </label>
                    <label>
                      Peer port
                      <input
                        type="number"
                        min="1"
                        max="65535"
                        value={remotePort}
                        onChange={(event) => setRemotePort(event.target.value)}
                        disabled={backend.status !== "READY"}
                      />
                    </label>
                    <small className="hint">
                      The other device must be running and reachable.
                    </small>
                    <div className="row-actions">
                      <button
                        className="button primary"
                        type="submit"
                        disabled={
                          backend.status !== "READY" ||
                          peer?.status !== "RUNNING" ||
                          !!busy["peer-connect"]
                        }
                      >
                        {busy["peer-connect"] ? "Connecting…" : "Connect"}
                      </button>
                    </div>
                  </form>
                </article>
              </section>
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Connected devices</h2>
                    <p>Peers currently known to this device.</p>
                  </div>
                  <button
                    className="button secondary small"
                    onClick={() =>
                      void runAction("peer-refresh", async () =>
                        setPeers(await window.p2p.peer.list()),
                      )
                    }
                    disabled={backend.status !== "READY"}
                  >
                    <Icon name="refresh" />
                    Refresh
                  </button>
                </div>
                {peers.length === 0 ? (
                  <Empty
                    icon="monitor"
                    title="No devices connected"
                    detail="Connect with a peer's IP address and port, or create an Internet invite below."
                  />
                ) : (
                  <PeerList
                    peers={peers}
                    onDisconnect={handleDisconnect}
                    busy={busy}
                  />
                )}
              </section>
              <section className="surface internet-card">
                <div className="surface-heading">
                  <div>
                    <h2>Connect over the Internet</h2>
                    <p>
                      Use a temporary invite when devices are on different
                      networks.
                    </p>
                  </div>
                  <span
                    className={
                      "state-chip " + (internetState.secure ? "success" : "")
                    }
                  >
                    {internetState.status}
                  </span>
                </div>
                <div className="info-strip">
                  <Icon name="shield" />
                  WebRTC encrypts the connection. Compare the safety code with
                  the other person over a separate trusted channel before
                  sharing files.
                </div>
                <label className="field-full">
                  Signaling HTTPS URL
                  <input
                    value={signalingUrl}
                    onChange={(event) => setSignalingUrl(event.target.value)}
                    placeholder="https://signal.example.com"
                    disabled={
                      internetState.status !== "IDLE" &&
                      internetState.status !== "CLOSED" &&
                      internetState.status !== "FAILED"
                    }
                  />
                </label>
                <div className="two-column invite-columns">
                  <div className="invite-panel">
                    <h3>Create an invite</h3>
                    <p>
                      Generate a short-lived private code for the other device.
                    </p>
                    <button
                      className="button primary"
                      onClick={handleCreateInternetInvite}
                      disabled={
                        backend.status !== "READY" ||
                        !peer?.peerId ||
                        !!internetSession.current ||
                        !!busy["internet-create"]
                      }
                    >
                      {busy["internet-create"] ? "Creating…" : "Create invite"}
                    </button>
                    {internetState.invite && (
                      <label className="field-full">
                        Private invite code
                        <textarea
                          rows={3}
                          readOnly
                          value={internetState.invite}
                          onFocus={(event) => event.currentTarget.select()}
                        />
                      </label>
                    )}
                  </div>
                  <div className="invite-panel">
                    <h3>Join an invite</h3>
                    <p>Paste the complete code shared by the other person.</p>
                    <label className="field-full">
                      <textarea
                        rows={3}
                        value={inviteInput}
                        onChange={(event) => setInviteInput(event.target.value)}
                        placeholder="Paste p2pfs1 invite code"
                        disabled={
                          internetState.status !== "IDLE" &&
                          internetState.status !== "CLOSED" &&
                          internetState.status !== "FAILED"
                        }
                      />
                    </label>
                    <button
                      className="button secondary"
                      onClick={handleJoinInternetInvite}
                      disabled={
                        backend.status !== "READY" ||
                        !peer?.peerId ||
                        !inviteInput.trim() ||
                        !!internetSession.current ||
                        !!busy["internet-join"]
                      }
                    >
                      {busy["internet-join"] ? "Joining…" : "Join device"}
                    </button>
                  </div>
                </div>
                <div className="session-footer">
                  <span>{internetState.detail}</span>
                  {internetSession.current && (
                    <button
                      className="button secondary small"
                      onClick={handleCloseInternetSession}
                      disabled={!!busy["internet-close"]}
                    >
                      Close session
                    </button>
                  )}
                </div>
                {internetState.authenticationCode && (
                  <div className="safety-code">
                    <div>
                      <small>VERIFY THE OTHER DEVICE</small>
                      <b>{internetState.authenticationCode}</b>
                      <p>Both people must confirm that this code matches.</p>
                    </div>
                    <div>
                      <span>
                        {internetState.localConfirmed
                          ? "You confirmed"
                          : "Waiting for your confirmation"}
                      </span>
                      <span>
                        {internetState.remoteConfirmed
                          ? "Peer confirmed"
                          : "Waiting for peer"}
                      </span>
                      <button
                        className="button primary"
                        onClick={handleConfirmInternetIdentity}
                        disabled={
                          internetState.localConfirmed ||
                          !internetState.authenticationCode
                        }
                      >
                        Confirm code
                      </button>
                    </div>
                  </div>
                )}
                {internetState.secure && (
                  <div className="secure-send">
                    <div className="surface-heading">
                      <div>
                        <h3>Files ready to send</h3>
                        <p>
                          Only files from your configured shared folder are
                          listed.
                        </p>
                      </div>
                      <span className="state-chip success">Verified</span>
                    </div>
                    {localFiles.length === 0 ? (
                      <div className="empty-line">
                        Choose a shared folder and add files to send.
                      </div>
                    ) : (
                      localFiles.map((file) => (
                        <div className="table-row" key={file.fileName}>
                          <div className="file-cell">
                            <FileTypeIcon name={file.fileName} />
                            <b>{file.fileName}</b>
                          </div>
                          <small>{formatBytes(file.size)}</small>
                          <button
                            className="button secondary small"
                            onClick={() =>
                              handleSendInternetFile(file.fileName)
                            }
                            disabled={
                              !!busy["internet-send-" + file.fileName] ||
                              (!!internetProgress &&
                                (internetProgress.status === "OFFERED" ||
                                  internetProgress.status === "TRANSFERRING"))
                            }
                          >
                            Send file
                          </button>
                        </div>
                      ))
                    )}
                  </div>
                )}
                {incomingInternetFile && (
                  <div className="incoming-offer">
                    <Icon name="file" />
                    <div>
                      <b>Incoming file: {incomingInternetFile.fileName}</b>
                      <small>
                        {formatBytes(incomingInternetFile.size)} · SHA-256
                        verification required
                      </small>
                      <code>{incomingInternetFile.sha256}</code>
                    </div>
                    <div className="row-actions">
                      <button
                        className="button primary small"
                        onClick={handleAcceptInternetFile}
                        disabled={!!busy["internet-accept-file"]}
                      >
                        Accept
                      </button>
                      <button
                        className="button secondary small"
                        onClick={handleRejectInternetFile}
                      >
                        Decline
                      </button>
                    </div>
                  </div>
                )}
                {internetProgress && (
                  <TransferProgressCard
                    progress={internetProgress}
                    onCancel={handleCancelInternetTransfer}
                  />
                )}
              </section>
            </div>
          )}

          {activePage === "transfers" && (
            <div className="page-content">
              <section className="stats-grid">
                <div className="stat-card">
                  <span className="stat-icon blue">
                    <Icon name="transfer" />
                  </span>
                  <span>
                    <b>{transfers.length}</b>
                    <small>Total tasks</small>
                  </span>
                </div>
                <div className="stat-card">
                  <span className="stat-icon green">
                    <Icon name="activity" />
                  </span>
                  <span>
                    <b>{activeTransferCount}</b>
                    <small>In progress</small>
                  </span>
                </div>
                <div className="stat-card">
                  <span className="stat-icon purple">
                    <Icon name="check" />
                  </span>
                  <span>
                    <b>{completedTransfers}</b>
                    <small>Completed</small>
                  </span>
                </div>
                <div className="stat-card">
                  <span className="stat-icon yellow">
                    <Icon name="pause" />
                  </span>
                  <span>
                    <b>{resumableDownloads.length}</b>
                    <small>Ready to resume</small>
                  </span>
                </div>
              </section>
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Download queue</h2>
                    <p>Transfers handled by your peer connections.</p>
                  </div>
                  <button
                    className="button secondary small"
                    onClick={() =>
                      void runAction("transfer-refresh", async () =>
                        setTransfers(await window.p2p.transfer.list()),
                      )
                    }
                    disabled={backend.status !== "READY"}
                  >
                    <Icon name="refresh" />
                    Refresh
                  </button>
                </div>
                {transfers.length === 0 ? (
                  <Empty
                    icon="download"
                    title="No downloads yet"
                    detail="Files you download from other devices will appear here."
                    action="Browse shared files"
                    onAction={() => setActivePage("shared")}
                  />
                ) : (
                  <div className="transfer-list">
                    {transfers.map((transfer) => {
                      const percent =
                        transfer.totalBytes > 0
                          ? Math.min(
                              100,
                              (transfer.receivedBytes / transfer.totalBytes) *
                                100,
                            )
                          : transfer.status === "COMPLETED"
                            ? 100
                            : 0;
                      const terminal = [
                        "COMPLETED",
                        "FAILED",
                        "CANCELLED",
                        "PAUSED",
                      ].includes(transfer.status);
                      return (
                        <div className="transfer-row" key={transfer.taskId}>
                          <FileTypeIcon
                            name={transfer.targetFileName || transfer.fileName}
                          />
                          <div className="transfer-main">
                            <div className="transfer-top">
                              <b>
                                {transfer.targetFileName || transfer.fileName}
                              </b>
                              <span
                                className={
                                  "state-chip " +
                                  (transfer.status === "COMPLETED"
                                    ? "success"
                                    : "")
                                }
                              >
                                {transfer.status}
                              </span>
                            </div>
                            <small>{transfer.detail}</small>
                            <div className="progress-track">
                              <div
                                className="progress-fill"
                                style={{ width: percent.toFixed(1) + "%" }}
                              />
                            </div>
                            <div className="transfer-meta">
                              <span>
                                {formatBytes(transfer.receivedBytes)} of{" "}
                                {formatBytes(transfer.totalBytes)} ·{" "}
                                {percent.toFixed(1)}%
                              </span>
                              {!terminal && (
                                <button
                                  className="text-link danger"
                                  onClick={() =>
                                    handleCancelTransfer(transfer.taskId)
                                  }
                                  disabled={!!busy["cancel-" + transfer.taskId]}
                                >
                                  Cancel
                                </button>
                              )}
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </section>
              {resumableDownloads.length > 0 && (
                <section className="surface">
                  <div className="surface-heading">
                    <div>
                      <h2>Interrupted downloads</h2>
                      <p>
                        Continue a verified partial transfer from a matching
                        source.
                      </p>
                    </div>
                    <span className="count-chip">
                      {resumableDownloads.length} available
                    </span>
                  </div>
                  {resumableDownloads.map((item) => {
                    const percent =
                      item.totalBytes > 0
                        ? Math.min(
                            100,
                            (item.receivedBytes / item.totalBytes) * 100,
                          )
                        : 0;
                    return (
                      <div className="transfer-row" key={item.taskId}>
                        <FileTypeIcon name={item.targetFileName} />
                        <div className="transfer-main">
                          <div className="transfer-top">
                            <b>{item.targetFileName}</b>
                            <span className="state-chip">Paused</span>
                          </div>
                          <small>
                            {item.completedChunks}/{item.totalChunks} verified
                            ranges saved
                          </small>
                          <div className="progress-track">
                            <div
                              className="progress-fill"
                              style={{ width: percent.toFixed(1) + "%" }}
                            />
                          </div>
                          <div className="transfer-meta">
                            <span>
                              {formatBytes(item.receivedBytes)} of{" "}
                              {formatBytes(item.totalBytes)} ·{" "}
                              {percent.toFixed(1)}%
                            </span>
                            <button
                              className="text-link"
                              onClick={() => handleResumeTransfer(item.taskId)}
                              disabled={
                                connectedPeers.length === 0 ||
                                !!busy["resume-" + item.taskId]
                              }
                            >
                              Resume download
                            </button>
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </section>
              )}
              {internetProgress && (
                <section className="surface">
                  <div className="surface-heading">
                    <div>
                      <h2>Internet transfer</h2>
                      <p>WebRTC DataChannel transfer progress.</p>
                    </div>
                  </div>
                  <TransferProgressCard
                    progress={internetProgress}
                    onCancel={handleCancelInternetTransfer}
                  />
                  {incomingInternetFile && (
                    <div className="incoming-offer">
                      <Icon name="file" />
                      <div>
                        <b>{incomingInternetFile.fileName}</b>
                        <small>
                          {formatBytes(incomingInternetFile.size)} · Incoming
                          file offer
                        </small>
                      </div>
                      <div className="row-actions">
                        <button
                          className="button primary small"
                          onClick={handleAcceptInternetFile}
                        >
                          Accept
                        </button>
                        <button
                          className="button secondary small"
                          onClick={handleRejectInternetFile}
                        >
                          Decline
                        </button>
                      </div>
                    </div>
                  )}
                </section>
              )}
            </div>
          )}

          {activePage === "recent" && (
            <div className="page-content">
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Activity timeline</h2>
                    <p>Events recorded by this desktop session.</p>
                  </div>
                  <button
                    className="button secondary small"
                    onClick={() => setActivities([])}
                    disabled={activities.length === 0}
                  >
                    Clear history
                  </button>
                </div>
                {activities.length === 0 ? (
                  <Empty
                    icon="clock"
                    title="Nothing here yet"
                    detail="Peer and transfer activity will show up as you use Mesh."
                  />
                ) : (
                  <ActivityList activities={activities} />
                )}
              </section>
            </div>
          )}

          {activePage === "security" && (
            <div className="page-content">
              <section className="security-banner">
                <Icon name="shield" />
                <div>
                  <b>Your connections are protected</b>
                  <p>
                    LAN peer sessions use TLS 1.3 mutual authentication. Only
                    fingerprints you explicitly trust can connect.
                  </p>
                </div>
                <span>TLS 1.3</span>
              </section>
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>This device identity</h2>
                    <p>
                      Share this fingerprint over a separate trusted channel.
                    </p>
                  </div>
                  <span className="count-chip">
                    {security?.protocol ?? "Loading…"}
                  </span>
                </div>
                <div className="fingerprint-box">
                  <small>SHA-256 certificate fingerprint</small>
                  <code>
                    {security?.localFingerprint ?? "Loading local identity…"}
                  </code>
                </div>
                <p className="hint">
                  Never trust a fingerprint received only through the same
                  connection you are trying to authenticate.
                </p>
              </section>
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Trusted devices</h2>
                    <p>
                      Fingerprints that are allowed to authenticate to this
                      device.
                    </p>
                  </div>
                  <span className="count-chip">
                    {security?.trustedFingerprints.length ?? 0} trusted
                  </span>
                </div>
                <form className="trust-form" onSubmit={handleTrustFingerprint}>
                  <label>
                    Device fingerprint
                    <input
                      value={trustedFingerprintInput}
                      onChange={(event) =>
                        setTrustedFingerprintInput(event.target.value)
                      }
                      placeholder="AA:BB:CC:… (64 hexadecimal characters)"
                      disabled={backend.status !== "READY"}
                      autoComplete="off"
                      spellCheck={false}
                    />
                  </label>
                  <button
                    className="button primary"
                    type="submit"
                    disabled={
                      backend.status !== "READY" || !!busy["security-trust"]
                    }
                  >
                    {busy["security-trust"] ? "Adding…" : "Trust device"}
                  </button>
                </form>
                {security?.trustedFingerprints.length ? (
                  <div className="trusted-list">
                    {security.trustedFingerprints.map((fingerprint) => (
                      <div className="trusted-row" key={fingerprint}>
                        <Icon name="monitor" />
                        <code>{fingerprint}</code>
                        <button
                          className="button secondary small"
                          onClick={() => handleUntrustFingerprint(fingerprint)}
                          disabled={!!busy["security-untrust-" + fingerprint]}
                        >
                          Revoke
                        </button>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="empty-line">
                    <Icon name="shield" />
                    No trusted devices have been added.
                  </div>
                )}
              </section>
              <div className="info-strip">
                Internet sessions also require both peers to compare and confirm
                the DTLS fingerprint-derived safety code.
              </div>
            </div>
          )}

          {activePage === "settings" && (
            <div className="page-content">
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Local peer network</h2>
                    <p>
                      Configure how this device listens for direct connections.
                    </p>
                  </div>
                  <span
                    className={
                      "state-chip " +
                      (peer?.status === "RUNNING" ? "success" : "")
                    }
                  >
                    {peer?.status ?? "UNKNOWN"}
                  </span>
                </div>
                <form className="settings-form" onSubmit={handleStartPeer}>
                  <label>
                    Bind address
                    <input
                      value={bindAddress}
                      onChange={(event) => setBindAddress(event.target.value)}
                      placeholder="127.0.0.1"
                      disabled={backend.status !== "READY"}
                    />
                  </label>
                  <label>
                    Listening port
                    <input
                      type="number"
                      min="0"
                      max="65535"
                      value={listenPort}
                      onChange={(event) => setListenPort(event.target.value)}
                      disabled={backend.status !== "READY"}
                    />
                  </label>
                  <small className="hint">
                    Default 0.0.0.0 listens on all local IPv4 interfaces for
                    LAN/VPN peers. Use a specific local IP to restrict the
                    listener to one interface. Port 0 selects an available port.
                  </small>
                  <div className="row-actions">
                    <button
                      className="button primary"
                      type="submit"
                      disabled={
                        backend.status !== "READY" ||
                        peer?.status === "RUNNING" ||
                        peer?.status === "STARTING" ||
                        peer?.status === "STOPPING" ||
                        !!busy["peer-start"]
                      }
                    >
                      Start peer
                    </button>
                    <button
                      className="button secondary"
                      type="button"
                      onClick={handleStopPeer}
                      disabled={
                        peer?.status !== "RUNNING" || !!busy["peer-stop"]
                      }
                    >
                      Stop
                    </button>
                  </div>
                </form>
              </section>
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Internet connectivity</h2>
                    <p>HTTPS rendezvous service used by WebRTC.</p>
                  </div>
                  <span className="count-chip">WebRTC / ICE / TURN</span>
                </div>
                <label className="field-full">
                  Signaling HTTPS URL
                  <input
                    value={signalingUrl}
                    onChange={(event) => setSignalingUrl(event.target.value)}
                    placeholder="https://signal.example.com"
                    disabled={
                      internetState.status !== "IDLE" &&
                      internetState.status !== "CLOSED" &&
                      internetState.status !== "FAILED"
                    }
                  />
                </label>
                <p className="hint">
                  Do not put TURN shared secrets or server credentials in this
                  field.
                </p>
                <div className="detail-line">
                  <small>Current session</small>
                  <span>{internetState.detail}</span>
                </div>
                <button
                  className="button secondary small"
                  onClick={() => setActivePage("devices")}
                >
                  Manage Internet session
                </button>
              </section>
              <section className="surface">
                <div className="surface-heading">
                  <div>
                    <h2>Desktop engine</h2>
                    <p>Local Java process and bridge status.</p>
                  </div>
                  <span
                    className={
                      "state-chip " +
                      (backend.status === "READY" ? "success" : "")
                    }
                  >
                    {backend.status}
                  </span>
                </div>
                <div className="detail-line">
                  <small>Java P2P Core</small>
                  <span>
                    {backend.error ?? "JSON Lines bridge is available."}
                  </span>
                </div>
                <div className="detail-line">
                  <small>Shared directory</small>
                  <span>{directory?.path ?? "No directory selected"}</span>
                </div>
                {backend.status === "FAILED" && (
                  <button
                    className="button primary"
                    onClick={handleRestartBackend}
                    disabled={!!busy["backend-restart"]}
                  >
                    Restart backend
                  </button>
                )}
              </section>
            </div>
          )}
          <footer className="workspace-footer">
            <span>Mesh · Files move directly between your devices</span>
            <span>
              Java 27 <i /> TCP/TLS <i /> WebRTC
            </span>
          </footer>
        </main>
      </div>
    </div>
  );
}

function Icon({ name }: { name: string }) {
  const glyphs: Record<string, ReactNode> = {
    nodes: (
      <>
        <circle cx="12" cy="12" r="3" />
        <circle cx="5" cy="5" r="2" />
        <circle cx="19" cy="5" r="2" />
        <circle cx="5" cy="19" r="2" />
        <circle cx="19" cy="19" r="2" />
        <path d="m7 7 3 3m4 4 3 3m0-10-3 3m-4 4-3 3" />
      </>
    ),
    laptop: (
      <>
        <rect x="4" y="4" width="16" height="12" rx="1.8" />
        <path d="M2 20h20l-2-4H4z" />
      </>
    ),
    phone: (
      <>
        <rect x="7" y="2.5" width="10" height="19" rx="2" />
        <path d="M10 5h4M11 18.5h2" />
      </>
    ),
    server: (
      <>
        <rect x="3" y="4" width="18" height="7" rx="1.5" />
        <rect x="3" y="13" width="18" height="7" rx="1.5" />
        <path d="M7 7.5h.01M7 16.5h.01M11 7.5h6M11 16.5h6" />
      </>
    ),
    search: (
      <>
        <circle cx="10.8" cy="10.8" r="6.8" />
        <path d="m16 16 4.5 4.5" />
      </>
    ),
    x: <path d="m6 6 12 12M18 6 6 18" />,
    refresh: (
      <>
        <path d="M20 7v5h-5" />
        <path d="M19 12a7.5 7.5 0 1 1-2-5.1L20 12" />
      </>
    ),
    plus: <path d="M12 5v14M5 12h14" />,
    home: (
      <>
        <path d="m3 10 9-7 9 7" />
        <path d="M5.5 9v11h13V9M9.5 20v-6h5v6" />
      </>
    ),
    folder: (
      <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2h6.5A2.5 2.5 0 0 1 21 9.5v8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z" />
    ),
    users: (
      <>
        <path d="M16 20v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 18.5V20" />
        <circle cx="10" cy="8" r="3.2" />
        <path d="M17 4.8a3.3 3.3 0 0 1 0 6.4M20 20v-1.5a3.5 3.5 0 0 0-2.6-3.4" />
      </>
    ),
    monitor: (
      <>
        <rect x="3" y="4" width="18" height="13" rx="2" />
        <path d="M8 21h8M12 17v4" />
      </>
    ),
    transfer: (
      <>
        <path d="M7 7h13l-3.5-3.5M20 7l-3.5 3.5" />
        <path d="M17 17H4l3.5-3.5M4 17l3.5 3.5" />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3.5 2" />
      </>
    ),
    shield: (
      <>
        <path d="M12 22s8-3.8 8-10V5l-8-3-8 3v7c0 6.2 8 10 8 10Z" />
        <path d="m9 12 2 2 4-4" />
      </>
    ),
    settings: (
      <>
        <circle cx="12" cy="12" r="3" />
        <path
          d="m19.4 15 .1.1 1.4 1.1-1.4 2.4-1.7-.7a8 8 0 0 1-1.7 1l-.3 1.8h-2.8l-.3-1.8a8 8 0 0 1-1.7-1l-1.7.7-1.4-2.4 1.4-1.1a7 7 0 0 1 0-2l-1.4-1.1 1.4-2.4 1.7.7a8 8 0 0 1 1.7-1l.3-1.8h2.8l.3 1.8a8 8 0 0 1 1.7 1l1.7-.7 1.4 2.4-1.4 1.1a7 7 0 0 1 0 2Z"
          transform="translate(-1 -1) scale(1.08)"
        />
      </>
    ),
    alert: (
      <>
        <path d="M12 3 2.7 19.2A1.2 1.2 0 0 0 3.8 21h16.4a1.2 1.2 0 0 0 1.1-1.8Z" />
        <path d="M12 9v4.5M12 17h.01" />
      </>
    ),
    check: <path d="m5 12.5 4.3 4.2L19 7" />,
    info: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 11v5M12 7.5h.01" />
      </>
    ),
    chevron: <path d="m9 5 7 7-7 7" />,
    arrow: (
      <>
        <path d="M7 17 17 7M8 7h9v9" />
      </>
    ),
    file: (
      <>
        <path d="M6 3h8l5 5v13H6z" />
        <path d="M14 3v5h5M9 13h7M9 17h7" />
      </>
    ),
    download: (
      <>
        <path d="M12 3v12M7 10l5 5 5-5" />
        <path d="M5 20h14" />
      </>
    ),
    activity: <path d="M3 12h4l3-8 4 16 3-8h4" />,
    pause: (
      <>
        <path d="M8 5v14M16 5v14" />
      </>
    ),
    list: (
      <>
        <path d="M9 6h12M9 12h12M9 18h12" />
        <path d="M4 6h.01M4 12h.01M4 18h.01" />
      </>
    ),
    grid: (
      <>
        <rect x="4" y="4" width="6" height="6" rx="1" />
        <rect x="14" y="4" width="6" height="6" rx="1" />
        <rect x="4" y="14" width="6" height="6" rx="1" />
        <rect x="14" y="14" width="6" height="6" rx="1" />
      </>
    ),
    more: (
      <>
        <circle cx="12" cy="5" r="1" />
        <circle cx="12" cy="12" r="1" />
        <circle cx="12" cy="19" r="1" />
      </>
    ),
  };
  return (
    <span className="ui-glyph" aria-hidden="true">
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {glyphs[name] ?? <circle cx="12" cy="12" r="2" />}
      </svg>
    </span>
  );
}

function FileTypeIcon({
  name,
  large = false,
}: {
  name: string;
  large?: boolean;
}) {
  const lower = name.toLowerCase();
  const extension = lower.split(".").pop() ?? "file";
  const kind = /\.(png|jpe?g|gif|webp|svg|heic)$/.test(lower)
    ? "image"
    : /\.(zip|7z|rar|tar|gz)$/.test(lower)
      ? "archive"
      : /\.(xls|xlsx|csv)$/.test(lower)
        ? "sheet"
        : /\.(ppt|pptx)$/.test(lower)
          ? "slide"
          : /\.(js|jsx|ts|tsx|java|kt|py|json|html|css|xml)$/.test(lower)
            ? "code"
            : "document";
  const mark =
    kind === "image"
      ? "IMG"
      : kind === "sheet"
        ? "XLS"
        : kind === "code"
          ? extension.slice(0, 3).toUpperCase()
          : kind === "archive"
            ? "ZIP"
            : kind === "slide"
              ? "PPT"
              : extension === "pdf"
                ? "PDF"
                : "DOC";
  return (
    <span
      className={"file-type " + kind + (large ? " large" : "")}
      aria-hidden="true"
    >
      <span>{mark}</span>
    </span>
  );
}

function Empty({
  icon,
  title,
  detail,
  action,
  onAction,
}: {
  icon: string;
  title: string;
  detail: string;
  action?: string;
  onAction?: () => void;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <Icon name={icon} />
      </span>
      <b>{title}</b>
      <p>{detail}</p>
      {action && onAction && (
        <button className="button primary" onClick={onAction}>
          {action}
        </button>
      )}
    </div>
  );
}

function FileTable({
  files,
  onSend,
  canSend,
  busy,
}: {
  files: SharedFileSnapshot[];
  onSend: (fileName: string) => void;
  canSend: boolean;
  busy: Record<string, boolean>;
}) {
  return (
    <div className="file-table">
      <div className="table-head">
        <span>Name</span>
        <span>Size</span>
        <span>Availability</span>
        <span>Actions</span>
      </div>
      {files.map((file) => (
        <div className="table-row" key={file.fileName}>
          <div className="file-cell">
            <FileTypeIcon name={file.fileName} />
            <span>
              <b title={file.fileName}>{file.fileName}</b>
              <small>Local shared file</small>
            </span>
          </div>
          <small>{formatBytes(file.size)}</small>
          <small className="shared-label">
            <i />
            Shared
          </small>
          <div className="row-actions">
            {canSend && (
              <button
                className="button secondary small"
                onClick={() => onSend(file.fileName)}
                disabled={!!busy["internet-send-" + file.fileName]}
              >
                Send
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

function PeerList({
  peers,
  onDisconnect,
  busy,
}: {
  peers: RemotePeerSnapshot[];
  onDisconnect: (peerId: string) => void;
  busy: Record<string, boolean>;
}) {
  return (
    <div className="peer-list">
      {peers.map((item) => (
        <div className="peer-row" key={item.peerId}>
          <span className="peer-icon">
            <Icon name="monitor" />
          </span>
          <div>
            <b>
              {item.host}:{item.port}
            </b>
            <small>{item.peerId}</small>
          </div>
          <span
            className={
              "state-chip " + (item.status === "CONNECTED" ? "success" : "")
            }
          >
            {item.status}
          </span>
          <button
            className="button secondary small"
            onClick={() => onDisconnect(item.peerId)}
            disabled={
              item.status !== "CONNECTED" ||
              !!busy["peer-disconnect-" + item.peerId]
            }
          >
            Disconnect
          </button>
        </div>
      ))}
    </div>
  );
}

function ActivityList({ activities }: { activities: ActivityItem[] }) {
  return (
    <div className="activity-list">
      {activities.map((activity) => (
        <div className="activity-row" key={activity.id}>
          <span
            className={"activity-indicator " + activity.level.toLowerCase()}
          />
          <div>
            <b>{activity.message}</b>
            <small>
              {activity.category} · {activity.time}
            </small>
          </div>
        </div>
      ))}
    </div>
  );
}

function TransferProgressCard({
  progress,
  onCancel,
}: {
  progress: InternetTransferProgress;
  onCancel: () => void;
}) {
  const percent =
    progress.totalBytes > 0
      ? Math.min(100, (progress.transferredBytes / progress.totalBytes) * 100)
      : progress.status === "COMPLETED"
        ? 100
        : 0;
  return (
    <div className="transfer-row">
      <FileTypeIcon name={progress.fileName} />
      <div className="transfer-main">
        <div className="transfer-top">
          <b>{progress.fileName}</b>
          <span
            className={
              "state-chip " + (progress.status === "COMPLETED" ? "success" : "")
            }
          >
            {progress.status}
          </span>
        </div>
        <small>{progress.detail}</small>
        <div className="progress-track">
          <div
            className="progress-fill"
            style={{ width: percent.toFixed(1) + "%" }}
          />
        </div>
        <div className="transfer-meta">
          <span>
            {formatBytes(progress.transferredBytes)} of{" "}
            {formatBytes(progress.totalBytes)} · {percent.toFixed(1)}%
          </span>
          {progress.status !== "COMPLETED" && (
            <button className="text-link danger" onClick={onCancel}>
              Cancel
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function parseListeningPort(value: string): number | null {
  if (value.trim() === "") {
    return null;
  }
  const port = Number(value);
  return Number.isInteger(port) && port >= 0 && port <= 65_535 ? port : null;
}

function parsePort(value: string): number | null {
  const port = Number(value);
  return Number.isInteger(port) && port >= 1 && port <= 65_535 ? port : null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(
    Math.floor(Math.log(value) / Math.log(1024)),
    units.length - 1,
  );
  return (
    (value / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 1) +
    " " +
    (units[index] ?? "B")
  );
}

function mergeTransferProgress(
  current: TransferSnapshot[],
  data: Record<string, unknown>,
): TransferSnapshot[] {
  if (typeof data.taskId !== "string") {
    return current;
  }
  const existing = current.find((item) => item.taskId === data.taskId);
  if (!existing) {
    return current;
  }
  return current.map((item) =>
    item.taskId === data.taskId
      ? {
          ...item,
          receivedBytes:
            typeof data.receivedBytes === "number"
              ? data.receivedBytes
              : item.receivedBytes,
          totalBytes:
            typeof data.totalBytes === "number"
              ? data.totalBytes
              : item.totalBytes,
          completedChunks:
            typeof data.completedChunks === "number"
              ? data.completedChunks
              : item.completedChunks,
          totalChunks:
            typeof data.totalChunks === "number"
              ? data.totalChunks
              : item.totalChunks,
        }
      : item,
  );
}

function mergeTransferSnapshot(
  current: TransferSnapshot[],
  data: Record<string, unknown>,
): TransferSnapshot[] {
  if (typeof data.taskId !== "string") {
    return current;
  }
  const next = data as unknown as TransferSnapshot;
  const existing = current.some((item) => item.taskId === next.taskId);
  return existing
    ? current.map((item) =>
        item.taskId === next.taskId ? { ...item, ...next } : item,
      )
    : [next, ...current];
}

export default App;
