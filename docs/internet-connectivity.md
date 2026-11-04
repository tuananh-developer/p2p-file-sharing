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
- [x] Offer/answer/ICE-candidate signaling message transport.
- [x] Short-lived TURN REST credential issuance.
- [x] coturn/Caddy Docker deployment examples.
- [ ] Desktop WebRTC ICE/DataChannel client.
- [ ] File streaming with bounded buffers, backpressure, cancellation and SHA-256 verification.
- [ ] Cross-network test matrix (LAN, mobile hotspot, school NAT, symmetric NAT, relay-only).
- [ ] Authentication-string verification UX and persistent trusted WebRTC identity.

The current desktop application still requires manual host/port for its Java TCP transport. This document describes the server foundation, not a claim that public-Internet file transfer is already available.

## Security requirements for the client

- Use HTTPS for signaling and request room-scoped, 10-minute TURN credentials.
- Never send file bytes, file names, local paths, private keys, or trusted fingerprint lists to signaling.
- Treat room IDs as identifiers and room secrets as bearer credentials.
- Enforce a human-verifiable authentication string from the two DTLS fingerprints before file transfer. A malicious signaling service can otherwise substitute SDP and perform a man-in-the-middle attack.
- Keep the existing Java TLS mutual-authentication trust store for the current TCP/LAN transport; do not weaken it or add a plaintext fallback.
- Apply file-size limits, data-channel backpressure, chunk sequencing, resume metadata and final SHA-256 checks to the DataChannel transfer protocol.
