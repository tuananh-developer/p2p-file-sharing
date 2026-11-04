# Internet connectivity infrastructure

This directory contains the first server-side layer for Internet peer connectivity:

- **Signaling service:** creates short-lived, two-peer rendezvous rooms and forwards WebRTC offer/answer/ICE-candidate messages.
- **TURN (coturn):** optional relay infrastructure for peers that cannot establish a direct ICE path.
- **Caddy:** terminates HTTPS for the signaling API and obtains/renews certificates automatically.

The desktop application now consumes the signaling service through its WebRTC ICE + DataChannel Internet transport. The existing Java TCP/TLS transport remains the LAN/reachable-peer transport. Public Internet use still requires deployment at a reachable HTTPS hostname; restrictive NATs require a configured and reachable TURN relay. Cross-network manual validation is still required before calling the feature production-ready.

## Why this is separate from the Java TCP listener

The current peer protocol is a TCP stream wrapped in TLS 1.3 mutual authentication. A STUN URI cannot make that TCP listener reachable through arbitrary NATs. The Internet transport should use WebRTC DataChannels, whose ICE stack gathers host/server-reflexive/relay candidates and uses DTLS for transport encryption. Keep the existing Java TCP/TLS implementation for LAN and use TURN only when direct ICE connectivity fails.

## Deploy on a Linux VPS

You need a public IPv4 or IPv6 address, two DNS records, and firewall access. The signaling process itself is dependency-free Node.js 24; Docker builds it without an npm install step.

1. Create DNS A/AAAA records:
   - \`SIGNALING_DOMAIN\` -> VPS address
   - \`TURN_PUBLIC_HOST\` -> VPS address
2. Copy \`.env.example\` to \`.env\`. Set the two hostnames and generate a shared TURN secret:
   \`\`\`bash
   cp .env.example .env
   openssl rand -hex 32
   \`\`\`
   Put the generated value in \`TURN_SHARED_SECRET\`.
3. Copy \`turnserver.conf.example\` to \`turnserver.conf\`. Set \`realm\` and \`server-name\` to the TURN hostname. Set \`static-auth-secret\` to exactly the same secret as \`TURN_SHARED_SECRET\`.
4. Place a certificate and private key for the TURN hostname in \`certs/fullchain.pem\` and \`certs/privkey.pem\`. Protect the private key and keep it out of Git.
5. Start signaling and Caddy:
   \`\`\`bash
   docker compose --env-file .env -f compose.yaml up -d --build signaling caddy
   \`\`\`
6. Start coturn:
   \`\`\`bash
   docker compose --env-file .env -f compose.yaml --profile turn up -d turn
   \`\`\`
7. Verify:
   \`\`\`bash
   curl -fsS https://$SIGNALING_DOMAIN/healthz
   docker compose --env-file .env -f compose.yaml logs --tail=100 signaling caddy
   \`\`\`

### VPS firewall

Allow inbound:
- TCP 80 and 443 for Caddy / HTTPS signaling.
- UDP and TCP 3478 for STUN/TURN.
- TCP 5349 for TURN over TLS.
- UDP and TCP 49160-49200 for coturn relay allocations.

Do not open the desktop peer's ephemeral P2P TCP port to the public Internet. TURN carries relayed traffic using its own allocated port range.

If the VPS provider places the instance behind a 1:1 NAT, set coturn's \`external-ip=PUBLIC_IPV4/PRIVATE_IPV4\`. Otherwise leave that option commented. The TURN service uses host networking so coturn can advertise and receive traffic on its relay port range.

## Signaling API

All endpoints are under \`/v1\`. Requests/responses use JSON. Room tokens are 256-bit random bearer secrets and must be shared between the two intended peers through an authenticated channel. The service stores only a SHA-256 digest of the room token.

| Method | Path | Purpose |
|---|---|---|
| POST | \`/v1/rooms\` | Create a room with \`{ "peerId": "<uuid>" }\`; returns \`roomId\`, \`roomSecret\`, and expiry |
| POST | \`/v1/rooms/{roomId}/join\` | Join with \`Authorization: Bearer <roomSecret>\` and \`{ "peerId": "<uuid>" }\` |
| GET | \`/v1/rooms/{roomId}/messages?after={sequence}&wait=20000\` | Long-poll queued signaling messages; send \`X-Peer-Id\` and bearer token |
| POST | \`/v1/rooms/{roomId}/messages\` | Send \`{ "toPeerId": "<uuid>", "type": "offer|answer|ice-candidate|hangup", "payload": {} }\` |
| POST | \`/v1/rooms/{roomId}/turn-credentials\` | Mint short-lived TURN REST credentials for a room member |
| POST | \`/v1/rooms/{roomId}/leave\` | Leave a room |

The server supports two peers per room, a 10-minute default TTL, at most 2,000 active rooms, a 64 KiB request limit, a 48 KiB signaling payload limit, at most 240 signaling messages per peer per minute, a 64 MiB global signaling-message buffer, and at most one active long poll per peer. It stores signaling messages in memory only; restart drops active rooms. Do not send file contents through this service.

TURN credentials are generated with the coturn REST shared-secret mechanism and expire after 10 minutes. The shared secret stays on the server; it is never returned to a desktop client. Keep \`.env\`, \`turnserver.conf\`, and TURN TLS private keys out of source control.

## Local test

No external service or npm packages are needed:

\`\`\`bash
node --test infra/nat-traversal/signaling/server.test.mjs
\`\`\`

## Production caveats

- Use HTTPS for signaling; Caddy terminates TLS.
- This initial service is single-process and in-memory. Run one instance; add shared storage/pub-sub before scaling horizontally.
- Configure reverse-proxy request limits and rate limits before public release. Room secrets are bearer credentials.
- Restrict access to the TURN port range and monitor bandwidth. Relay data egress is the main operating cost.
- Do not hard-code a public TURN credential in the desktop application. The client must request short-lived credentials after joining a room.
- The desktop client must validate peer identity at the application layer (for example by comparing a short authentication string derived from both DTLS fingerprints out-of-band) before offering or accepting files. WebRTC DTLS encrypts the data channel but does not by itself prove that the signaling rendezvous is not malicious.
