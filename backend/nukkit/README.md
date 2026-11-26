# Nukkit-MOT backend bridge

`NavroFireworkBridge` keeps its existing plugin name and firework conversion. Version
0.2 also supplements two facts that a packet-only proxy cannot reliably observe:

- actual `Server.getTick()` samples, sent on the player's ordered backend connection;
- final server-owned item identities for transaction inventory slots that have not
  already been confirmed by ordinary `InventorySlot` / `InventoryContent` packets.

The plugin does not replicate positions, movement flags, chunk/block data, abilities,
or other state already carried by normal Bedrock traffic. Inventory results use
Nukkit's actual inventory objects and standard slot encoders; no client claims or
successful transaction event alone are accepted as a committed result.

## Negotiation and clock

The proxy starts `navro:backend_sync_v1` with `hello|<connection UUID nonce>` after
StartGame. The plugin opts that player in and replies:

```text
tick|<same nonce>|<monotonic sample sequence>|<actual signed Server.getTick()>
```

One sample is sent per native server tick and before native block update messages
when needed. The proxy consumes these messages instead of forwarding them to the
client. A client cannot inject this reserved channel upstream. New StartGame/session
negotiation discards old nonces; duplicate/out-of-order samples do not refresh the
clock. A sample expires after one second without a valid update. No local +1 clock
or engine-name check substitutes for it.

These are ordered samples, not zero-latency access to the backend's current tick.
The proxy gates AirLiquidPlace on a valid clock sample; missing/expired capability
skips this check and does not hold placement requests. An explicit disabled-checks
entry still disables it even when a backend supplies the capability. Other backends
can implement the same protocol, regardless of engine name or transport.

## Transactions and inventories

Like original Navro, the bridge reads actual native slots when a successful
`ItemStackResponse` is queued. It sends standard slot packets after the response
for full identities the response cannot represent. It does not interpret bundle
internal slots as regular slots.
Workstations use their native component-local slots and send through the standard
UI container (`124`). The proxy mirrors those wire slots to the corresponding
open workstation only after the closing ACK. Shared recipe output stays in the
HUD slot; opening or closing a window clears old pending workstation slots.

Legacy transaction events track native slot changes. Forwarded transaction and
AuthInput packets also retain a conservative next-tick fallback for other visible
slots, since item use can change slots outside the request or event. Cancellation
and rejection use the final server contents, never the event's proposed target.

Already-sent full item identities are compared to final server contents. If the
ordinary protocol has already confirmed a slot, no duplicate supplement is sent.
Otherwise the plugin calls native `sendSlot` / `sendArmorSlot`, retaining Nukkit's
window ID, armor/UI mapping, full NBT and stack network identity. When the API exposes
`afterSend`, supplements wait for successful send completion. The current upstream MOT
API does not expose that callback, so it schedules the work on the next server tick;
this preserves main-thread ordering but cannot confirm network delivery. Closed/reused
windows and quit sessions discard old pending state. No periodic full inventory
synchronization runs.

Standard packets update the proxy's backend-owned snapshot on arrival and its
client-visible compensated inventory only after the same closing ACK as other
inventory packets. The bridge does not invent a transaction acceptance result.

## Existing firework conversion

An accepted attached-rocket spawn starts a standard long `GLIDE_BOOST` effect after
send completion when the API exposes that callback, or on the next server tick with
the current upstream MOT API. Removing the last visible rocket stops it. Only direct
native rocket propulsion calls are filtered while the effect is active.
Explosion/ordinary knockback remains.
Do not install alongside original Navro's firework converter.

## Build and validation

Run `python build.py --nukkit-jar /path/to/Nukkit-MOT.jar` against the actual server JAR. Integration
executes native `InventoryTransaction.execute`, `SlotChangeAction`, `BaseInventory`
and slot encoding, plus the existing real rocket update/motion integration. Generated
native transaction packet fixtures are consumed by proxy ACK/cache tests.

Use `build/NavroBackendBridge.jar` in Nukkit's `plugins` directory and restart
normally after saving the world. Remove any old `NavroFireworkBridge.jar` from
that directory: the internal plugin name is unchanged, so do not load both jars.
Restart player sessions to negotiate the bridge.
It is a Nukkit-MOT plugin; no BDS plugin installation is implied. Proxy capability
detection itself does not hard-code Nukkit-MOT or BDS names.


## Private NetherNet proxy listener (v0.4.0)

The optional `private-nethernet` section matches the PMMP bridge: `enabled`,
`signaling-port` (19608), `signaling-bind-address`, `ice-bind-address`,
`advertised-address`, `ice-port-range` ([20000, 20127]), `max-connections`, and
`proxy-public-key-file`. Default: disabled, loopback only.

Generate the proxy key pair once in the Navro distribution directory:

```sh
bin/navro-nethernet -init-backend-signing-key keys/navro-backend.pem
```

On Windows use `bin/navro-nethernet.exe`. Keep the existing key pair when reusing a
proxy already configured for PMMP.

Copy the proxy RSA public PEM to `plugins/NavroFireworkBridge/navro-backend.pub.pem`.
The private PEM stays on Navro. Enable `private-nethernet.enabled` and restart the
backend. Missing/invalid keys or incompatible core settings leave this listener
closed and report an error; normal bridge features continue running.

Use a Nukkit-MOT build with native NetherNet, keeping
`network-settings.nethernet.enabled: true`. The bridge reuses its native packet
codec, compression, bounded network queues, login timeout and server tick processing.
Set `xbox-auth=off` on this private backend: Navro performs the player authentication;
the additional RS256 token authenticates the proxy. This does not turn the forwarded
login into a Microsoft-signed token. Keep ordinary backend ports restricted to the
proxy, as with other private backend deployments.

On Navro, reuse the existing PMMP-compatible signing mechanism:

```yaml
frontend: nethernet
backend-transport: nethernet
backend-address: http://127.0.0.1:19608
backend-auth: pmmp-ng
backend-signing-key: keys/navro-backend.pem
```

The `pmmp-ng` name is retained for compatibility; this Nukkit bridge accepts the same
wire authentication, including issuer `navro-proxy`, audience
`urn:navro:nethernet-backend`, one-minute RSA token and P-384 session-key binding.
The listener verifies the forwarded game login and client-data signatures against
that same session key. It preserves the proxy-verified account UUID/XUID without
changing authentication rules on the public Nukkit listener.

For a remote proxy, use `https://host:port` in `backend-address`, terminate HTTPS at a
reverse proxy in front of the private signaling port, and configure the bridge's
bind addresses, advertised IP and reachable ICE UDP range. Authentication does not
rely on a forwarded HTTP IP header. RSA-authenticated private sessions can negotiate
state/alerts even if the proxy address is not listed in `trusted-proxy-addresses`;
ordinary connections continue using the source-address allowlist.

## Private RakNet proxy listener (v0.5.0)

The optional `private-raknet` section starts a second RakNet UDP listener and
assigns a dedicated authenticated player class only to that interface. Defaults
are disabled, loopback bind, port 19134, and
`navro-backend.pub.pem` in the plugin data folder. Enable native public/private
RakNet support in the core. Keep `xbox-auth` enabled globally if the public
listener requires it; the private interface accepts the proxy-authenticated
self-signed login only after the signed assertion is verified.

Generate the key pair once on Navro with
`bin/navro-nethernet -init-backend-signing-key keys/navro-backend.pem`. Keep the
private key on Navro and copy only the `.pub.pem` file to the bridge data folder.
Configure Navro with `backend-transport: raknet`, `backend-auth: pmmp-ng`,
`backend-signing-key: keys/navro-backend.pem`, and a `backend-address` pointing
at the private port. For example, clients can continue using UDP 19132 while
Navro connects to `127.0.0.1:19134`. The public listener keeps its normal auth
policy; the private listener also checks assertion expiry, audience, one-time
ID, session-key match, UUID, XUID, name, and Bedrock client-data signature.

```yaml
private-raknet:
  enabled: true
  bind-address: "127.0.0.1"
  port: 19134
  proxy-public-key-file: navro-backend.pub.pem
```

Build this bridge against the same latest Nukkit-MOT core JAR that you deploy;
the core includes the separate-listener and authenticated-player-class hooks.
The release includes `Nukkit-MOT-private-raknet.jar` with those hooks. To build
from upstream instead, apply `core-patches/private-raknet.patch` at the exact
commit recorded in `core-patches/upstream.json` before compiling the core.

The current Navro RakNet-to-NetherNet path only supports `backend-auth: private`;
it cannot use this RSA listener yet. NetherNet-to-RakNet remains available through
Nukkit's normal RakNet listener.
