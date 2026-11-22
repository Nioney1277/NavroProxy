# NavroProxy

An anti-cheat proxy for Minecraft Bedrock Edition. NavroProxy runs between players and a backend server, tracks the world and player state, and checks movement and gameplay packets.

It runs on Windows and Linux with Java 21. Clients can connect over RakNet or through the separate NetherNet frontend.

## Build

Install JDK 21 or later, then run the Gradle wrapper.

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

Linux

```sh
bin/navro-proxy --listen 0.0.0.0:19132 --backend 127.0.0.1:19133 --mode detect
```

Windows PowerShell

```powershell
.\bin\navro-proxy.bat --listen 0.0.0.0:19132 --backend 127.0.0.1:19133 --mode detect
```

The backend must accept trusted proxy connections. The client and backend must use compatible Bedrock protocol versions.

Settings are stored in `plugins/Navro/`:

| File | Purpose |
| --- | --- |
| `config.yml` | Movement tolerances, corrections, disabled checks and latency limits |
| `punishments.yml` | Check groups, recent violation windows and alert/log/kick thresholds |
| `messages.yml` | Prefix, alert format, command replies and disconnect messages |

All three files are bundled under `src/main/resources/` and included in the distribution. Missing files are created at startup; existing settings are preserved. A legacy prefix in `config.yml` moves to `messages.yml` when that file is first created. `/navro reload` reloads all three together; an invalid file leaves the previous configuration active.

Punishment rules use `threshold:interval action`. For example, `50:0 kick %player%` kicks at 50 recent violations, once until the group's window expires. `1:1 [alert]` reports each violation. Check selectors support exact names (`RotationPlace`, `Sprint-A`), check families (`Sprint`), `*` wildcards, and `!` exclusions. The default file has a separate section for each of the 22 implemented checks, so Timer, NegativeTimer and each Sprint type have independent counters. Each player's counters are also independent; reloading or reconnecting resets them. Default rules only alert. The proxy supports `[alert]`, `[log]`, and kicks; it does not execute arbitrary backend console commands.

Message placeholders include `%prefix%`, `%player%`, `%check_name%`, `%check%`, `%vl%` (recent group count), `%check_vl%` (individual check count), `%verbose%`, and `%message%` (the original detailed alert). Color codes use `&`.

- `detect` runs the registered checks.
- `inspect` observes protocol traffic.
- `--backend-type bds` selects the BDS adapter.
- `--block-palette` supplies a backend runtime palette for the NetherNet adapter.

Keep the backend on a private network or restrict it to connections from the proxy.

## Player commands

- `/navro alert` toggles alerts.
- `/navro reload` reloads configuration.
- `/navro debug` and `/navro debug2` control diagnostic output.

## NetherNet and backend adapters

The NetherNet frontend requires Go 1.26 or later and runs alongside the Java proxy.

- NetherNet frontend source: `nethernet/`.
- Geyser and Floodgate integration: `backend/geyser/`.
- Nukkit bridge source and build script: `backend/nukkit/`.
- PocketMine-MP 5 bridge source and build script: `backend/pocketmine-mp/`.

### PocketMine-MP-NG over NetherNet

Install `NavroBackendBridge.phar` and generate a signing key on the proxy host. Copy `navro-backend.pub.pem` to the bridge's data directory and set `private-nethernet.enabled: true` in its `config.yml`. Keep the private PEM on Navro. The bridge listens on `127.0.0.1:19608` over TCP and uses loopback UDP 20000–20127 by default. Disable `network.nethernet.enabled` in PocketMine's `pocketmine.yml` if the bridge is the sole NetherNet listener. `network.raklib.enabled` can also be disabled for a NetherNet-only setup.

```powershell
.\navro-nethernet.exe -init-backend-signing-key .\keys\navro-backend.pem
java -cp ".\lib\*" ac.navro.proxy.nethernet.NetherNetInspectServer 19138
.\navro-nethernet.exe -listen 0.0.0.0:19132 -backend http://127.0.0.1:19608 -adapter 127.0.0.1:19138 -detect -offline-backend -backend-signing-key .\keys\navro-backend.pem
```

### PocketMine-MP-NG over RakNet

Keep the PocketMine-MP-NG RakLib listener on loopback (`server-ip=127.0.0.1`, `server-port=19608`, and `network.raklib.enabled: true`). This mode uses PocketMine-MP-NG's existing local proxy login support. Xbox authentication and backend encryption can stay enabled.

Start the Java adapter from the NavroProxy distribution directory, then start the Go frontend:

```powershell
java -cp ".\lib\*" ac.navro.proxy.nethernet.NetherNetInspectServer 19138
.\navro-nethernet.exe -listen 0.0.0.0:19132 -backend raknet://127.0.0.1:19608 -adapter 127.0.0.1:19138 -detect
```

26.60 clients connect to the Go frontend over NetherNet. TCP 19132 is used for signaling and UDP 19210–19273 for ICE in either backend mode. The RakNet backend uses loopback UDP 19608.

AirLiquidPlace and RotationPlace require a valid native server tick from the backend bridge. They automatically disable when samples are missing, stale, or reset; FarPlace and ordinary inventory validation remain active. RotationPlace timeouts use only backend ticks. Missing selection data skips local placement prediction without a confirmation wait or timeout cancellation.


## License

[MIT](LICENSE)

Input timing uses a server-owned counter that advances once per input. Client tick values are stored separately for correction frame matching. Negative frames disconnect the client; duplicate, backward and jumping frames do not add VL. BadPacketsC checks respawn requests (formerly BadPacketsD).
