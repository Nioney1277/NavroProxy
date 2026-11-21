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

Settings are stored in `plugins/Navro/config.yml`, matching Navro's plugin data directory. The bundled default is `src/main/resources/config.yml`, containing only settings implemented by NavroProxy. The distribution includes `plugins/Navro/config.yml`; if it is missing at startup, NavroProxy creates it from the bundled resource without replacing an existing file. Use `/navro reload` to reload configuration changes.

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


## License

[MIT](LICENSE)
