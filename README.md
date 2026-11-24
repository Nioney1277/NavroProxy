# NavroProxy

An anti-cheat proxy for Minecraft Bedrock Edition. NavroProxy runs between players and a backend server, tracks the world and player state, and checks movement and gameplay packets.

It runs on Windows and Linux with Java 21. Clients can connect over RakNet or through the separate NetherNet frontend.

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
| `raknet` | `raknet` | `native` |
| `nethernet` | `nethernet` | `private`, `pmmp-ng`, or `geyser` |
| `nethernet` | `raknet` | `native` |
| `raknet` | `nethernet` | `private` |

Set `listen` and `backend-address` to host and port values. NetherNet backend addresses may include an `http://` or `https://` URL; remote signaling requires HTTPS. Remote ICE/RakNet traffic needs its UDP ports reachable from the proxy host. Set `backend-signing-key` for PMMP-NG and `backend-type: bds` for BDS. Detection always runs; there is no separate observation-only mode.

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
| `config.yml` | Movement tolerances, corrections, disabled checks and latency limits |
| `punishments.yml` | Check groups, recent violation windows and alert/log/kick thresholds |
| `messages.yml` | Prefix, alert format, command replies and disconnect messages |

These files are bundled under `src/main/resources/` and included in the distribution. Missing files are created at startup; existing settings are preserved. A legacy prefix in `config.yml` moves to `messages.yml` when that file is first created. `/navro reload` reloads the anti-cheat settings; network settings are read at startup.

Punishment rules use `threshold:interval action`. For example, `50:0 kick %player%` kicks at 50 recent violations, once until the group's window expires. `1:1 [alert]` reports each violation. Check selectors support exact names (`RotationPlace`, `Sprint-A`), check families (`Sprint`), `*` wildcards, and `!` exclusions. The default file has a separate section for each of the 22 implemented checks, so Timer, NegativeTimer and each Sprint type have independent counters. Each player's counters are also independent; reloading or reconnecting resets them. Default rules only alert. The proxy supports `[alert]`, `[log]`, and kicks; it does not execute arbitrary backend console commands.

Message placeholders include `%prefix%`, `%player%`, `%check_name%`, `%check%`, `%vl%` (recent group count), `%check_vl%` (individual check count), `%verbose%`, and `%message%` (the original detailed alert). Color codes use `&`.

- `backend-type: bds` selects BDS movement semantics independently of the transport.

Keep the backend on a private network or restrict it to connections from the proxy.

## Player commands

- `/navro alert` toggles alerts.
- `/navro reload` reloads configuration.
- `/navro debug` and `/navro debug2` control diagnostic output.

## NetherNet and backend adapters

The distribution includes the Go transport and Java packet adapter. Configure `frontend`, `backend-transport`, `backend-address`, and `backend-auth` in `network.yml`; `bin/navro-proxy` starts the required components together. The transport source is in `nethernet/`.
- Geyser and Floodgate integration: `backend/geyser/`.
- Nukkit bridge source and build script: `backend/nukkit/`.
- PocketMine-MP 5 bridge source and build script: `backend/pocketmine-mp/`.

The Nukkit-MOT bridge also forwards NavroProxy alerts to the backend console and permitted staff. `/navroalerts` toggles persistent chat alerts for players with `navrobridge.alerts` (operators by default). Set `trusted-proxy-addresses`, `console-alerts`, and `staff-chat-alerts` in the plugin's `config.yml`; trusted addresses refer to the actual proxy connection, before player IP forwarding. Use the updated proxy together with bridge version 0.3.0 for alert forwarding. Existing native tick and inventory synchronization remain negotiated per connection, and firework boosts end when the attached rocket or glide ends.

Nukkit bridge 0.4.0 additionally provides an optional RSA-authenticated private NetherNet listener. Its `private-nethernet` configuration matches the PMMP bridge (TCP 19608, ICE UDP 20000–20127, bind/advertised addresses and proxy public key). On Navro, use `backend-auth: pmmp-ng` to select the existing compatible signing format; the name is retained for compatibility with PMMP. This Nukkit-MOT core requires `xbox-auth=off` on the private backend and native NetherNet enabled; Navro validates players, while the private listener authenticates the proxy and binds forwarded logins to its session key. See `backend/nukkit/README.md` for setup and remote HTTPS deployment.

### RakNet clients to a NetherNet backend

Set `frontend: raknet`, `backend-transport: nethernet`, and `backend-auth: private` in `network.yml`, then run `bin/navro-proxy`. Clients connect over UDP RakNet to `listen`; the upstream server must provide NetherNet at `backend-address`. Navro validates the signed player login and Bedrock encryption, then re-signs the verified login for its NetherNet session.

The backend must accept the existing private offline login and self-signed
NetherNet transport identity. Remote signaling uses HTTPS; its ICE media must
be reachable from the proxy over UDP. This mode uses the existing player
identity validation and does not require a bridge plugin for transport.

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

Set `frontend: nethernet`, `backend-transport: raknet`, and `backend-auth: native` in `network.yml`, then run `bin/navro-proxy`. The RakLib backend may be remote if its UDP port is reachable and it accepts proxy logins. Xbox authentication and backend encryption can stay enabled.

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
