# Internet peer connectivity design

## Decision

Keep the current Java TCP/TLS protocol for reachable LAN peers. Add an Internet transport based on WebRTC DataChannels:

1. The desktop app joins a short-lived two-peer room on the signaling service.
2. The peers exchange SDP offers/answers and trickled ICE candidates through the signaling API.
3. ICE tries direct host and server-reflexive candidates first, then TURN relay candidates.
4. Once connected, the app transfers bounded file chunks over a WebRTC DataChannel.
5. The peers compare an authentication string derived from both DTLS certificate fingerprints out-of-band before accepting file transfer.

This is separate from the existing Java TCP listener. STUN discovers candidate addresses; it does not open a TCP port or convert a Java \`SSLSocket\` into a NAT-traversal-capable transport. TURN is the fallback when direct ICE connectivity fails. coturn supports TURN over UDP/TCP/TLS and TCP relay allocations; WebRTC typically uses UDP candidates first and can use TURN/TLS when restrictive networks block UDP.

## Current implementation status

- [x] Server-side two-party room creation and join.
- [x] Offer/answer signaling through the rendezvous API.
- [x] Short-lived TURN REST credential issuance.
- [x] coturn/Caddy Docker deployment examples.
- [x] Electron renderer WebRTC ICE negotiation using gathered SDP candidates.
- [x] Reliable ordered DataChannel file streaming with a four-chunk acknowledgement window.
- [x] File offers, recipient save dialog, cancellation and final SHA-256 verification.
- [x] DTLS fingerprint-derived eight-digit safety code; both users must confirm the same code before file operations.
- [ ] WebRTC interrupted-transfer resume. The existing TCP/LAN transfer resume remains available.
- [ ] Production deployment with a real public DNS name, HTTPS certificate, VPS firewall and TURN certificate.
- [ ] Manual end-to-end tests across independent networks, including school NAT, mobile hotspot, TURN-only and restrictive firewalls.

The desktop now has the client-side WebRTC path, but public connectivity depends on deploying the signaling service at a reachable HTTPS hostname. Direct ICE can work with STUN where NAT permits it. Networks with symmetric or restrictive NAT require a reachable TURN relay. The sample public STUN fallback is best-effort, not a service-level guarantee.

To create a connection, both users run the desktop app, start their local peer, and exchange the temporary invite code through a separate channel. The invite code contains a room bearer secret and must be treated like a password. The room expires after ten minutes. The recipient must accept each file and select a save location.

## Security requirements for the client

- Use HTTPS for signaling and request room-scoped, 10-minute TURN credentials.
- Never send file bytes, file names, local paths, private keys, or trusted fingerprint lists to signaling.
- Treat room IDs as identifiers and room secrets as bearer credentials.
- Enforce a human-verifiable authentication string from the two DTLS fingerprints before file transfer. A malicious signaling service can otherwise substitute SDP and perform a man-in-the-middle attack.
- Keep the existing Java TLS mutual-authentication trust store for the current TCP/LAN transport; do not weaken it or add a plaintext fallback.
- Apply file-size limits, a bounded four-chunk acknowledgement window, sequential chunk offsets, cancellation and final SHA-256 checks to the DataChannel transfer protocol. Resume metadata is not yet implemented for WebRTC.
