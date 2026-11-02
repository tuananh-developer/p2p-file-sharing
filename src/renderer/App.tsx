import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
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
  ResumableDownloadSnapshot
} from "../../electron/shared/contracts";

interface ActivityItem {
  id: string;
  message: string;
  level: string;
  time: string;
}

function App() {
  const [backend, setBackend] = useState<BackendStatusSnapshot>({ status: "STARTING" });
  const [peer, setPeer] = useState<PeerSnapshot | null>(null);
  const [peers, setPeers] = useState<RemotePeerSnapshot[]>([]);
  const [directory, setDirectory] = useState<SharedDirectorySnapshot | null>(null);
  const [localFiles, setLocalFiles] = useState<SharedFileSnapshot[]>([]);
  const [keyword, setKeyword] = useState("");
  const [searchResults, setSearchResults] = useState<RemoteFileSearchResult[]>([]);
  const [searchErrors, setSearchErrors] = useState<FileSearchResponse["errors"]>([]);
  const [transfers, setTransfers] = useState<TransferSnapshot[]>([]);
  const [resumableDownloads, setResumableDownloads] = useState<ResumableDownloadSnapshot[]>([]);
  const [activities, setActivities] = useState<ActivityItem[]>([]);
  const [bindAddress, setBindAddress] = useState("127.0.0.1");
  const [listenPort, setListenPort] = useState("5001");
  const [remoteHost, setRemoteHost] = useState("127.0.0.1");
  const [remotePort, setRemotePort] = useState("5002");
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [notice, setNotice] = useState<{ kind: "success" | "error" | "info"; message: string } | null>(null);

  const addActivity = useCallback((message: string, level = "INFO") => {
    setActivities((current) => [
      {
        id: crypto.randomUUID(),
        message,
        level,
        time: new Date().toLocaleTimeString()
      },
      ...current
    ].slice(0, 30));
  }, []);

  const refreshAll = useCallback(async () => {
    const status = await window.p2p.backend.getStatus();
    setBackend(status);
    if (status.status !== "READY") {
      setPeer(null);
      setPeers([]);
      setDirectory(null);
      setLocalFiles([]);
      setTransfers([]);
      return;
    }

    const [peerState, knownPeers, shareState, transferList] = await Promise.all([
      window.p2p.peer.getStatus().catch(() => null),
      window.p2p.peer.list().catch(() => []),
      window.p2p.share.getDirectory().catch(() => null),
      window.p2p.transfer.list().catch(() => [])
    ]);

    setPeer(peerState);
    setPeers(knownPeers);
    setDirectory(shareState);
    setTransfers(transferList);

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
          if (status === "READY" || status === "FAILED" || status === "STOPPED" || status === "STARTING") {
            setBackend({
              status,
              error: typeof data.error === "string" ? data.error : undefined
            });
            if (status === "READY") {
              void refreshAll().catch((error: unknown) => addActivity(errorMessage(error), "ERROR"));
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
            error: typeof data.message === "string" ? data.message : "Java backend process failed."
          });
          setPeer(null);
          setPeers([]);
          setDirectory(null);
          setLocalFiles([]);
          setTransfers([]);
          break;
        case "backendStatusChanged":
          if (data.status === "READY") {
            void refreshAll().catch((error: unknown) => addActivity(errorMessage(error), "ERROR"));
          }
          break;
        case "peerStatusChanged":
          void window.p2p.peer.getStatus().then(setPeer).catch(() => setPeer(null));
          break;
        case "connectionStatusChanged":
          void window.p2p.peer.list().then(setPeers).catch(() => setPeers([]));
          break;
        case "sharedDirectoryChanged": {
          const next = data as unknown as SharedDirectorySnapshot;
          setDirectory(next);
          if (next.configured) {
            void window.p2p.share.listLocalFiles().then(setLocalFiles).catch(() => setLocalFiles([]));
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
            typeof data.message === "string" ? data.message : "Activity event received.",
            typeof data.level === "string" ? data.level : "INFO"
          );
          break;
        case "backendProcessExited":
          if (data.expected !== true) {
            setBackend({ status: "FAILED", error: "Java backend exited unexpectedly." });
            setPeer(null);
            setPeers([]);
            setDirectory(null);
            setLocalFiles([]);
            setTransfers([]);
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
    [peers]
  );

  const runAction = useCallback(async (key: string, action: () => Promise<void>) => {
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
  }, [addActivity]);

  const handleStartPeer = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const port = parsePort(listenPort);
    if (port === null) {
      setNotice({ kind: "error", message: "Listening port must be between 1 and 65535." });
      return;
    }
    void runAction("peer-start", async () => {
      const snapshot = await window.p2p.peer.start({ bindAddress, port });
      setPeer(snapshot);
      setNotice({ kind: "success", message: "Peer is listening on " + snapshot.bindAddress + ":" + snapshot.port + "." });
      addActivity("Peer started on " + snapshot.bindAddress + ":" + snapshot.port + ".");
    });
  };

  const handleStopPeer = () => {
    void runAction("peer-stop", async () => {
      const snapshot = await window.p2p.peer.stop();
      setPeer(snapshot);
      setPeers([]);
      setNotice({ kind: "success", message: "Local peer stopped." });
      addActivity("Local peer stopped.");
    });
  };

  const handleConnect = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const port = parsePort(remotePort);
    if (port === null) {
      setNotice({ kind: "error", message: "Remote port must be between 1 and 65535." });
      return;
    }
    void runAction("peer-connect", async () => {
      const connected = await window.p2p.peer.connect({ host: remoteHost.trim(), port });
      setPeers(await window.p2p.peer.list());
      setNotice({ kind: "success", message: "Connected to " + connected.host + ":" + connected.port + "." });
      addActivity("Connected to peer " + connected.peerId + ".");
    });
  };

  const handleDisconnect = (peerId: string) => {
    void runAction("peer-disconnect-" + peerId, async () => {
      await window.p2p.peer.disconnect(peerId);
      setPeers(await window.p2p.peer.list());
      setNotice({ kind: "success", message: "Peer disconnected." });
      addActivity("Disconnected peer " + peerId + ".");
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
      addActivity("Shared directory changed.");
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
        message: result.results.length + " file result(s) from " +
          new Set(result.results.map((item) => item.peerId)).size + " peer(s)."
      });
    });
  };

  const matchingSourceIds = (file: RemoteFileSearchResult) => [...new Set(searchResults
    .filter((candidate) => candidate.fileName === file.fileName && candidate.size === file.size)
    .map((candidate) => candidate.peerId))].slice(0, 16);

  const handleDownload = (file: RemoteFileSearchResult) => {
    const sourcePeerIds = matchingSourceIds(file);
    void runAction("download-" + file.fileName, async () => {
      const accepted = sourcePeerIds.length > 1
        ? await window.p2p.transfer.downloadMulti(sourcePeerIds, file.fileName)
        : await window.p2p.transfer.download(file.peerId, file.fileName);
      if (!accepted) {
        return;
      }
      setTransfers(await window.p2p.transfer.list());
      setNotice({
        kind: "success",
        message: "Download queued as " + accepted.targetFileName +
          (sourcePeerIds.length > 1 ? " using " + sourcePeerIds.length + " candidate peer(s)." : ".")
      });
      addActivity((sourcePeerIds.length > 1 ? "Multi-source" : "Single-source") +
        " download queued: " + file.fileName + ".");
    });
  };

  const handleDownloadFromThisPeer = (file: RemoteFileSearchResult) => {
    void runAction("download-single-" + file.peerId + "-" + file.fileName, async () => {
      const accepted = await window.p2p.transfer.download(file.peerId, file.fileName);
      if (!accepted) {
        return;
      }
      setTransfers(await window.p2p.transfer.list());
      setNotice({ kind: "success", message: "Download queued as " + accepted.targetFileName + "." });
    });
  };

  const handleFindResumable = () => {
    void runAction("transfer-resumable", async () => {
      const found = await window.p2p.transfer.listResumable();
      setResumableDownloads(found ?? []);
      addActivity("Scanned the selected folder for interrupted downloads.");
      setNotice({
        kind: "info",
        message: found === null ? "Folder selection cancelled."
          : found.length === 0 ? "No valid interrupted downloads were found."
            : "Found " + found.length + " interrupted download(s)."
      });
    });
  };

  const handleResumeTransfer = (taskId: string) => {
    if (connectedPeers.length === 0) {
      setNotice({ kind: "error", message: "Connect to at least one source peer before resuming." });
      return;
    }
    void runAction("resume-" + taskId, async () => {
      await window.p2p.transfer.resume(taskId, connectedPeers.map((item) => item.peerId));
      setTransfers(await window.p2p.transfer.list());
      setResumableDownloads((current) => current.filter((item) => item.taskId !== taskId));
      addActivity("Resumed interrupted download " + taskId + ".");
      setNotice({ kind: "success", message: "Resume queued. Sources will be checked against the saved SHA-256." });
    });
  };

  const handleCancelTransfer = (taskId: string) => {
    void runAction("cancel-" + taskId, async () => {
      await window.p2p.transfer.cancel(taskId);
      setTransfers(await window.p2p.transfer.list());
      setNotice({ kind: "success", message: "Transfer cancellation requested." });
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

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark">P2P</div>
          <div>
            <div className="brand-title">File Sharing</div>
            <div className="brand-subtitle">Direct peer-to-peer transfers</div>
          </div>
        </div>
        <div className="topbar-right">
          <span className={"status-pill status-" + backend.status.toLowerCase()}>
            <span className="status-dot" />
            Java backend · {backend.status}
          </span>
          <button className="button button-quiet button-small" onClick={() => void refreshAll()} disabled={backend.status !== "READY" || busy.refresh}>
            Refresh state
          </button>
        </div>
      </header>

      {backend.status !== "READY" && (
        <section className="backend-banner">
          <div>
            <strong>{backend.status === "STARTING" ? "Starting Java Core…" : "Java Core is unavailable"}</strong>
            <p>{backend.error ?? "The desktop app is waiting for the JSON Lines bridge to report READY."}</p>
          </div>
          {backend.status === "FAILED" && (
            <button className="button button-primary" onClick={handleRestartBackend} disabled={busy["backend-restart"]}>
              {busy["backend-restart"] ? "Restarting…" : "Retry backend"}
            </button>
          )}
        </section>
      )}

      {notice && (
        <div className={"notice notice-" + notice.kind} role="status">
          <span>{notice.message}</span>
          <button aria-label="Dismiss notification" onClick={() => setNotice(null)}>×</button>
        </div>
      )}

      <main className="main-content">
        <section className="hero-grid">
          <article className="panel local-peer-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">LOCAL NODE</span>
                <h1>Peer lifecycle</h1>
              </div>
              <span className={"state-chip " + (peer?.status === "RUNNING" ? "state-running" : "state-idle")}>
                {peer?.status ?? "UNKNOWN"}
              </span>
            </div>

            <form className="form-grid" onSubmit={handleStartPeer}>
              <label>
                <span>Bind address</span>
                <input value={bindAddress} onChange={(event) => setBindAddress(event.target.value)} disabled={backend.status !== "READY"} />
              </label>
              <label>
                <span>Listening port</span>
                <input type="number" min="1" max="65535" value={listenPort} onChange={(event) => setListenPort(event.target.value)} disabled={backend.status !== "READY"} />
              </label>
              <div className="form-actions">
                <button className="button button-primary" type="submit" disabled={backend.status !== "READY" || peer?.status === "RUNNING" || peer?.status === "STARTING" || peer?.status === "STOPPING" || busy["peer-start"]}>
                  {busy["peer-start"] ? "Starting…" : "Start peer"}
                </button>
                <button className="button button-danger" type="button" onClick={handleStopPeer} disabled={backend.status !== "READY" || peer?.status !== "RUNNING" || busy["peer-stop"]}>
                  {busy["peer-stop"] ? "Stopping…" : "Stop peer"}
                </button>
              </div>
            </form>

            <div className="local-peer-meta">
              <span>Peer ID</span>
              <code>{peer?.peerId ?? "Not started"}</code>
            </div>
          </article>

          <article className="panel connect-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">REMOTE NODE</span>
                <h2>Connect to peer</h2>
              </div>
              <span className="metric">{connectedPeers.length} connected</span>
            </div>
            <form className="form-grid" onSubmit={handleConnect}>
              <label>
                <span>Host / IP address</span>
                <input value={remoteHost} onChange={(event) => setRemoteHost(event.target.value)} placeholder="192.168.1.10" disabled={backend.status !== "READY"} />
              </label>
              <label>
                <span>Remote port</span>
                <input type="number" min="1" max="65535" value={remotePort} onChange={(event) => setRemotePort(event.target.value)} disabled={backend.status !== "READY"} />
              </label>
              <div className="form-actions">
                <button className="button button-primary" type="submit" disabled={backend.status !== "READY" || peer?.status !== "RUNNING" || busy["peer-connect"]}>
                  {busy["peer-connect"] ? "Connecting…" : "Connect"}
                </button>
                <span className="helper-text">Start your local peer before connecting.</span>
              </div>
            </form>
          </article>
        </section>

        <section className="content-grid">
          <article className="panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">PEER DIRECTORY</span>
                <h2>Connected peers</h2>
              </div>
              <button className="button button-quiet button-small" onClick={() => void runAction("peer-refresh", async () => setPeers(await window.p2p.peer.list()))} disabled={backend.status !== "READY"}>
                Refresh
              </button>
            </div>

            {peers.length === 0 ? (
              <div className="empty-state">No peers have been discovered yet. Connect to a peer by IP address and port.</div>
            ) : (
              <div className="peer-list">
                {peers.map((item) => (
                  <div className="peer-row" key={item.peerId}>
                    <div className="peer-avatar">{item.host.slice(0, 1).toUpperCase()}</div>
                    <div className="peer-main">
                      <strong>{item.host}:{item.port}</strong>
                      <code>{item.peerId}</code>
                    </div>
                    <span className={"state-chip " + (item.status === "CONNECTED" ? "state-running" : "state-idle")}>{item.status}</span>
                    <button className="button button-quiet button-small" onClick={() => handleDisconnect(item.peerId)} disabled={item.status !== "CONNECTED" || busy["peer-disconnect-" + item.peerId]}>
                      Disconnect
                    </button>
                  </div>
                ))}
              </div>
            )}
          </article>

          <article className="panel share-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">LOCAL STORAGE</span>
                <h2>Shared directory</h2>
              </div>
              <button className="button button-secondary button-small" onClick={handleChooseDirectory} disabled={backend.status !== "READY" || busy["share-directory"]}>
                {busy["share-directory"] ? "Opening…" : "Choose folder"}
              </button>
            </div>
            <div className="directory-path">
              <span className="folder-icon">▰</span>
              <span>{directory?.path ?? "No directory selected"}</span>
            </div>
            <div className="section-caption">{localFiles.length} local file(s) available to connected peers</div>
            {localFiles.length === 0 ? (
              <div className="empty-state compact">Choose a folder with files to share. Only supported top-level regular files are listed.</div>
            ) : (
              <div className="file-list">
                {localFiles.slice(0, 8).map((file) => (
                  <div className="file-row" key={file.fileName}>
                    <span className="file-icon">▤</span>
                    <span className="file-name">{file.fileName}</span>
                    <span className="file-size">{formatBytes(file.size)}</span>
                  </div>
                ))}
                {localFiles.length > 8 && <div className="more-row">+ {localFiles.length - 8} more file(s)</div>}
              </div>
            )}
          </article>

          <article className="panel search-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">REMOTE CATALOG</span>
                <h2>Find files</h2>
              </div>
              <span className="metric">{searchResults.length} result(s)</span>
            </div>
            <form className="search-form" onSubmit={handleSearch}>
              <input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="Search by file name…" disabled={backend.status !== "READY"} />
              <button className="button button-primary" type="submit" disabled={backend.status !== "READY" || peer?.status !== "RUNNING" || busy["file-search"]}>
                {busy["file-search"] ? "Searching…" : "Search"}
              </button>
            </form>
            {searchErrors.length > 0 && (
              <div className="inline-warning">
                {searchErrors.length} peer(s) could not be refreshed. Successful results are still shown.
              </div>
            )}
            {searchResults.length === 0 ? (
              <div className="empty-state compact">Search connected peers to browse files available for download.</div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr><th>File</th><th>Source peer</th><th>Size</th><th /></tr>
                  </thead>
                  <tbody>
                    {searchResults.map((file) => (
                      <tr key={file.peerId + ":" + file.fileName}>
                        <td className="file-name-cell">{file.fileName}</td>
                        <td><span className="source-address">{file.host}:{file.port}</span></td>
                        <td className="file-size">{formatBytes(file.size)}</td>
                        <td className="action-cell">
                          <button
                            className="button button-secondary button-small"
                            onClick={() => handleDownload(file)}
                            disabled={backend.status !== "READY" || busy["download-" + file.fileName]}
                          >
                            {matchingSourceIds(file).length > 1
                              ? "Multi-source (" + matchingSourceIds(file).length + ")"
                              : "Download"}
                          </button>
                          {matchingSourceIds(file).length > 1 && (
                            <button
                              className="button button-quiet button-small"
                              onClick={() => handleDownloadFromThisPeer(file)}
                              disabled={backend.status !== "READY" || busy["download-single-" + file.peerId + "-" + file.fileName]}
                            >
                              This peer only
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </article>

          <article className="panel transfer-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">TRANSFER QUEUE</span>
                <h2>Downloads</h2>
              </div>
              <div className="form-actions">
                <button className="button button-quiet button-small" onClick={handleFindResumable} disabled={backend.status !== "READY" || busy["transfer-resumable"]}>
                  Find interrupted
                </button>
                <button className="button button-quiet button-small" onClick={() => void runAction("transfer-refresh", async () => setTransfers(await window.p2p.transfer.list()))} disabled={backend.status !== "READY"}>
                  Refresh
                </button>
              </div>
            </div>
            {transfers.length === 0 ? (
              <div className="empty-state compact">Download tasks and their progress will appear here.</div>
            ) : (
              <div className="transfer-list">
                {transfers.map((transfer) => {
                  const percent = transfer.totalBytes > 0
                    ? Math.min(100, transfer.receivedBytes / transfer.totalBytes * 100)
                    : transfer.status === "COMPLETED" ? 100 : 0;
                  const terminal = ["COMPLETED", "FAILED", "CANCELLED", "PAUSED"].includes(transfer.status);
                  return (
                    <div className="transfer-row" key={transfer.taskId}>
                      <div className="transfer-topline">
                        <div>
                          <strong>{transfer.targetFileName || transfer.fileName}</strong>
                          <span>{transfer.detail}</span>
                          {transfer.sourcePeerIds && transfer.sourcePeerIds.length > 0 && (
                            <span>{transfer.sourcePeerIds.length} source peer(s)
                              {typeof transfer.totalChunks === "number" && transfer.totalChunks > 0
                                ? " · " + (transfer.completedChunks ?? 0) + "/" + transfer.totalChunks + " ranges"
                                : ""}
                            </span>
                          )}
                        </div>
                        <span className={"state-chip " + (transfer.status === "COMPLETED" ? "state-running" : "state-idle")}>
                          {transfer.status}
                        </span>
                      </div>
                      <div className="progress-track">
                        <div className="progress-fill" style={{ width: percent.toFixed(1) + "%" }} />
                      </div>
                      <div className="transfer-bottomline">
                        <span>{formatBytes(transfer.receivedBytes)} / {formatBytes(transfer.totalBytes)} · {percent.toFixed(1)}%</span>
                        {!terminal && (
                          <button className="text-button" onClick={() => handleCancelTransfer(transfer.taskId)} disabled={busy["cancel-" + transfer.taskId]}>
                            Cancel
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
            {resumableDownloads.length > 0 && (
              <div className="transfer-list resumable-list">
                <div className="eyebrow">INTERRUPTED DOWNLOADS</div>
                {resumableDownloads.map((item) => {
                  const percent = item.totalBytes > 0
                    ? Math.min(100, item.receivedBytes / item.totalBytes * 100)
                    : 0;
                  return (
                    <div className="transfer-row" key={item.taskId}>
                      <div className="transfer-topline">
                        <div>
                          <strong>{item.targetFileName}</strong>
                          <span>{item.completedChunks}/{item.totalChunks} verified ranges saved</span>
                        </div>
                        <span className="state-chip state-idle">PAUSED</span>
                      </div>
                      <div className="progress-track">
                        <div className="progress-fill" style={{ width: percent.toFixed(1) + "%" }} />
                      </div>
                      <div className="transfer-bottomline">
                        <span>{formatBytes(item.receivedBytes)} / {formatBytes(item.totalBytes)} · {percent.toFixed(1)}%</span>
                        <button className="text-button" onClick={() => handleResumeTransfer(item.taskId)}
                          disabled={connectedPeers.length === 0 || busy["resume-" + item.taskId]}>
                          Resume
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </article>

          <article className="panel activity-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">SYSTEM EVENTS</span>
                <h2>Recent activity</h2>
              </div>
              <button className="button button-quiet button-small" onClick={() => setActivities([])}>Clear</button>
            </div>
            {activities.length === 0 ? (
              <div className="empty-state compact">Connection, transfer and backend events will be recorded here.</div>
            ) : (
              <div className="activity-list">
                {activities.map((activity) => (
                  <div className="activity-row" key={activity.id}>
                    <span className={"activity-dot level-" + activity.level.toLowerCase()} />
                    <span className="activity-message">{activity.message}</span>
                    <time>{activity.time}</time>
                  </div>
                ))}
              </div>
            )}
          </article>
        </section>
      </main>

      <footer className="footer">
        <span>Java 21 · TCP P2P · JSON Lines</span>
        <span>{connectedPeers.length} active connection(s)</span>
      </footer>
    </div>
  );
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
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return (value / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 1) + " " + (units[index] ?? "B");
}

function mergeTransferProgress(
  current: TransferSnapshot[],
  data: Record<string, unknown>
): TransferSnapshot[] {
  if (typeof data.taskId !== "string") {
    return current;
  }
  const existing = current.find((item) => item.taskId === data.taskId);
  if (!existing) {
    return current;
  }
  return current.map((item) => item.taskId === data.taskId
    ? {
        ...item,
        receivedBytes: typeof data.receivedBytes === "number" ? data.receivedBytes : item.receivedBytes,
        totalBytes: typeof data.totalBytes === "number" ? data.totalBytes : item.totalBytes,
        completedChunks: typeof data.completedChunks === "number" ? data.completedChunks : item.completedChunks,
        totalChunks: typeof data.totalChunks === "number" ? data.totalChunks : item.totalChunks
      }
    : item);
}

function mergeTransferSnapshot(
  current: TransferSnapshot[],
  data: Record<string, unknown>
): TransferSnapshot[] {
  if (typeof data.taskId !== "string") {
    return current;
  }
  const next = data as unknown as TransferSnapshot;
  const existing = current.some((item) => item.taskId === next.taskId);
  return existing
    ? current.map((item) => item.taskId === next.taskId ? { ...item, ...next } : item)
    : [next, ...current];
}

export default App;
