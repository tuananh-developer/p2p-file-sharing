import type { P2PBridgeApi } from "../../electron/shared/contracts";

declare global {
  interface Window {
    p2p: P2PBridgeApi;
  }
}

export {};
