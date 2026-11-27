# NavroProxy

载具预测支持船/运输船及马类，按独立公式接入共享检测链路；版本门槛、检测范围与已知限制见 [载具物理说明](docs/vehicle-physics.md)。

An anti-cheat proxy for Minecraft Bedrock Edition. NavroProxy runs between players and a backend server, tracks the world and player state, and checks movement and gameplay packets.

It runs on Windows and Linux with Java 21. Clients can connect over RakNet or through the separate NetherNet frontend.

## Client versions

All four transport combinations accept the known Bedrock protocols from
**1.7.0 (291) through 1.26.60 (2225)** provided by the current protocol library,
subject to compatible client transport and backend protocol support. Versions
with an existing detection codec keep the normal anti-cheat session. Other
known versions use connection compatibility without creating anti-cheat world state.

Native forwarding requires `frontend: raknet`, `backend-transport: raknet`,
`backend-auth: native` (or `auto`), and `backend-encryption: false`. The backend
must support the player's own protocol version; Navro does not translate an old
client's protocol into a newer backend protocol. This native RakNet forwarding
path does not add private RSA assertions or Floodgate forwarding tokens.

The three routes involving NetherNet validate player signatures, bind the
transport identity to the game login, and sign the backend login using its
session key. They support legacy certificate-chain and modern token login
formats, old/new compression framing, and the applicable RakNet encryption
handshake. A compatible private backend must accept the forwarded login format.
Clients using a NetherNet entry must themselves implement NetherNet; this does
not add NetherNet support to an unmodified old Minecraft client. Use the RakNet
entry for those clients. Backend compression negotiation currently requires ZLIB.

## Build

Install JDK 21 or later, then run the Gradle wrapper.
Go 1.26 or later is also required; the wrapper builds the bundled transport executable for the current platform.

Linux

```sh
bash gradlew distZip
```

Windows PowerShell

```powershell
.\gradlew.bat distZip
```

The distribution ZIP is written to `build/distributions/`. Extract the entire archive to keep the launcher and its dependencies together.

To build an unpacked distribution

```sh
bash gradlew installDist
```

## Run

Configure `plugins/Navro/network.yml`, then start the proxy. The file is created with the RakNet-to-RakNet defaults on first run.

```sh
bin/navro-proxy
```

On Windows, run `bin/navro-proxy.bat` from the extracted distribution.

The four transport combinations are selected independently in `network.yml`:

```yaml
frontend: raknet
listen: 0.0.0.0:19132
backend-transport: nethernet
backend-address: https://backend.example.net:19134
```

`backend-auth: auto` is the default: it selects `native` for RakNet and `private` for NetherNet. Set a different authentication method only when required by the backend.

| `frontend` | `backend-transport` | `backend-auth` |
|---|---|---|
| `raknet` | `raknet` | `native` or `pmmp-ng` |
| `nethernet` | `nethernet` | `private`, `pmmp-ng`, or `geyser` |
| `nethernet` | `raknet` | `native` or `pmmp-ng` |
| `raknet` | `nethernet` | `private` |

Set `listen` and `backend-address` to host and port values. NetherNet backend addresses may include an `http://` or `https://` URL; remote signaling requires HTTPS. Remote ICE/RakNet traffic needs its UDP ports reachable from the proxy host. Set `backend-signing-key` with `backend-auth: pmmp-ng` to use a private authenticated RakNet listener or the PMMP-NG NetherNet listener. Detection always runs; there is no separate observation-only mode.

Manual command-line options are available for RakNet-to-RakNet deployments:

```sh
bin/navro-proxy --listen 0.0.0.0:19132 --backend play.example.net:19132 --backend-type navro
```

Windows PowerShell

```powershell
.\bin\navro-proxy.bat --listen 0.0.0.0:19132 --backend play.example.net:19132 --backend-type navro
```

The backend must accept trusted proxy connections. The client and backend must use compatible Bedrock protocol versions.

Settings are stored in `plugins/Navro/`:

| File | Purpose |
| --- | --- |
| `network.yml` | Client listener, backend transport, address and backend authentication |
| `config.yml` | Movement tolerances, corrections, disabled checks, latency limits and player command source |
| `punishments.yml` | Check groups, recent violation windows and alert/log/kick thresholds |
| `messages.yml` | Prefix, alert format, command replies and disconnect messages |

These files are bundled under `src/main/resources/` and included in the distribution. Missing files are created at startup; existing settings are preserved. A legacy prefix in `config.yml` moves to `messages.yml` when that file is first created. `/navro reload` reloads the anti-cheat settings; network settings are read at startup.

Punishment rules use `threshold:interval action`. For example, `50:0 kick %player%` kicks at 50 recent violations, once until the group's window expires. `1:1 [alert]` reports each violation. Check selectors support exact names (`RotationPlace`, `Sprint-A`), check families (`Sprint`), `*` wildcards, and `!` exclusions. The default file has a separate section for each of the 22 implemented checks, so Timer, NegativeTimer and each Sprint type have independent counters. Each player's counters are also independent; reloading or reconnecting resets them. Default rules only alert. The proxy supports `[alert]`, `[log]`, and kicks; it does not execute arbitrary backend console commands.

Message placeholders include `%prefix%`, `%player%`, `%check_name%`, `%check%`, `%vl%` (recent group count), `%check_vl%` (individual check count), `%verbose%`, and `%message%` (the original detailed alert). Color codes use `&`.

- `backend-type: bds` selects BDS movement semantics independently of the transport.

Keep the backend on a private network or restrict it to connections from the proxy.

## Player commands

- `command-source: proxy` (default) publishes and handles player commands in the proxy; `bridge` delegates them to the updated Nukkit/PMMP bridge. Reload and reconnect after changing this option. See [command-source.md](docs/command-source.md).
- `/navro alert` toggles alerts.
- `/navro reload` reloads configuration.
- `/navro debug` and `/navro debug2` control diagnostic output.
- `/navro proxyping [player]` reports upstream/downstream proxy processing time (average, P95 and maximum) and client RTT. See [proxyping.md](docs/proxyping.md).

## NetherNet and backend adapters

The distribution includes the Go transport and Java packet adapter. Configure `frontend`, `backend-transport`, `backend-address`, and `backend-auth` in `network.yml`; `bin/navro-proxy` starts the required components together. The transport source is in `nethernet/`.
- Geyser and Floodgate integration: `backend/geyser/`.
- Nukkit bridge source and build script: `backend/nukkit/`.
- PocketMine-MP 5 bridge source and build script: `backend/pocketmine-mp/`.

The Nukkit-MOT bridge also forwards NavroProxy alerts to the backend console and permitted staff. `/navro alert` in bridge mode toggles persistent chat alerts for players with `navrobridge.alerts` (operators by default). Set `trusted-proxy-addresses`, `console-alerts`, and `staff-chat-alerts` in the plugin's `config.yml`; trusted addresses refer to the actual proxy connection, before player IP forwarding. Use the updated proxy together with bridge version 0.3.0 for alert forwarding. Existing native tick and inventory synchronization remain negotiated per connection, and firework boosts end when the attached rocket or glide ends.

Nukkit bridge 0.4.0 provides an optional RSA-authenticated private NetherNet listener, and 0.5.0 adds a separate private RakNet listener. The NetherNet listener uses TCP 19608 and ICE UDP 20000–20127; the RakNet listener defaults to UDP 19134. Both use the same proxy public key and private signing key. The Nukkit RakNet listener keeps global `xbox-auth` policy intact and only admits the verified proxy player class on its private interface. See `backend/nukkit/README.md` for setup.

The PMMP-NG and Nukkit-MOT bridges also offer an optional private RakNet listener on a separate UDP port. Enable `private-raknet`, bind it to loopback unless the proxy is remote, and copy only `navro-backend.pub.pem` from Navro's `keys` directory into the bridge data folder. On Navro set `backend-transport: raknet`, `backend-auth: pmmp-ng`, `backend-signing-key: keys/navro-backend.pem`, and point `backend-address` at that private port. For example:

```yaml
frontend: raknet
listen: 0.0.0.0:19132
backend-transport: raknet
backend-address: 127.0.0.1:19134
backend-auth: pmmp-ng
backend-signing-key: keys/navro-backend.pem
```

The bridge verifies a one-minute RS256 assertion and binds it to Navro's ES384 game-login key and forwarded UUID/XUID/name; each assertion ID can be used only once. Player login/client-data signatures are still checked by the server core. Keep the private RakNet port reachable only from Navro. The ordinary public RakNet listener keeps its normal authentication policy.

### RakNet clients to a NetherNet backend

Set `frontend: raknet`, `backend-transport: nethernet`, and `backend-auth: private` in `network.yml`, then run `bin/navro-proxy`. Clients connect over UDP RakNet to `listen`; the upstream server must provide NetherNet at `backend-address`. Navro validates the signed player login and Bedrock encryption, then re-signs the verified login for its NetherNet session.

The backend must accept the existing private offline login and self-signed
NetherNet transport identity. Remote signaling uses HTTPS; its ICE media must
be reachable from the proxy over UDP. For a PMMP-NG or Nukkit-MOT private
RakNet listener, set `backend-auth: pmmp-ng` and configure the signing key.
Ordinary unauthenticated RakNet backends continue using `native`.

### PocketMine-MP-NG over NetherNet

Set `frontend: nethernet`, `backend-transport: nethernet`, `backend-auth: pmmp-ng`, and `backend-signing-key` in `network.yml`, then run `bin/navro-proxy`. Install `NavroBackendBridge.phar` and generate a signing key on the proxy host. Copy `navro-backend.pub.pem` to the bridge's data directory and set `private-nethernet.enabled: true` in its `config.yml`. Keep the private PEM on Navro. For a remote proxy, configure the bridge to advertise its public IP and bind the signaling and ICE sockets to reachable interfaces:

```yaml
private-nethernet:
  enabled: true
  signaling-port: 19608
  signaling-bind-address: "0.0.0.0"
  ice-bind-address: "0.0.0.0"
  advertised-address: "203.0.113.20"
  ice-port-range: [20000, 20127]
```

Terminate HTTPS for the signaling URL at the host or a reverse proxy, and forward TCP 19608 plus UDP 20000–20127 to PMMP. Add the proxy host's address to `trusted-proxy-addresses` for remote bridge synchronization. Disable `network.nethernet.enabled` in PocketMine's `pocketmine.yml` if the bridge is the sole NetherNet listener. `network.raklib.enabled` can also be disabled for a NetherNet-only setup.

### PocketMine-MP-NG over RakNet

Set `frontend: nethernet`, `backend-transport: raknet`, and `backend-auth: native` for a normal backend, or `backend-auth: pmmp-ng` plus `backend-signing-key` for a private authenticated RakNet listener. The backend may be remote if its UDP port is reachable. Public Xbox authentication stays enabled; the private listener validates Navro's signed proxy assertion and forwarded player login.

Enable the bridge's `private-raknet` block on PMMP-NG or Nukkit-MOT, using a separate UDP port bound to loopback when possible. Copy only `navro-backend.pub.pem` to the plugin data folder; keep `keys/navro-backend.pem` on Navro. The PMMP listener requires the matching PMMP-NG core patch; stock PMMP 5 does not provide it. Build the Nukkit bridge against the same latest Nukkit-MOT core JAR that is deployed.

The bridge releases include matching patched cores. Source patches and exact
upstream commits are recorded in `backend/pocketmine-mp/core-patches/` and
`backend/nukkit/core-patches/`. Apply the patch before building a replacement core.

26.60 clients connect to the Go frontend over NetherNet. TCP 19132 is used for signaling and UDP 19210–19273 for ICE. The RakNet backend can be on any reachable host; its server must accept proxy logins.

AirLiquidPlace and RotationPlace require a valid native server tick from the backend bridge. They automatically disable when samples are missing, stale, or reset; FarPlace and ordinary inventory validation remain active. RotationPlace timeouts use only backend ticks. Missing selection data skips local placement prediction without a confirmation wait or timeout cancellation.


## Contributors

- [Nioney1277](https://github.com/nioney1277-lab)
- [GLOBEsg](https://github.com/globesg)
- [GhostNgEnd](https://github.com/GhostNgEnd)
- [huangyxHUTAO](https://github.com/huangyxHUTAO)
- [Theu5er](https://github.com/Theu5er)

## License

[MIT](LICENSE)

Input timing uses a server-owned counter that advances once per input. Client tick values are stored separately for correction frame matching. Negative frames disconnect the client; duplicate, backward and jumping frames do not add VL. BadPacketsC checks respawn requests (formerly BadPacketsD).

## Vehicle setbacks

With `smooth-setback: false`, a vehicle setback ends the ride, waits for backend dismount confirmation, then sends a strict on-foot teleport. It does not automatically remount the player. With `true`, the proxy keeps the player mounted and uses vehicle prediction correction. See [vehicle physics](docs/vehicle-physics.md).
