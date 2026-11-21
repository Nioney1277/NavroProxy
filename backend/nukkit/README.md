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
