# NetherNet 入口

这个目录包含独立的 NetherNet/WebRTC 前端 `inspect/`、协议探针 `probe/` 和可选的
HTTP/UDP 透明中继 `relay.py`。检测前端将原生 Bedrock 批次交给 Java 适配器处理；
透明中继只转发加密流量，不执行 Navro 检测。

## 构建桌面前端

需要 Go 1.26 或更新版本。依赖来自 `go.mod` 与 `go.sum` 中固定的公开模块，
没有本地源码替换，也不需要 Android 工具链。

Linux：

```sh
cd nethernet
go build -o bin/navro-nethernet ./inspect
go build -o bin/navro-nethernet-probe ./probe
go test ./...
```

Windows PowerShell：

```powershell
cd nethernet
go build -o bin/navro-nethernet.exe ./inspect
go build -o bin/navro-nethernet-probe.exe ./probe
go test ./...
```

网络接口使用操作系统的实际接口枚举。默认自动选择可用 IPv4 接口；
多网卡部署可用 `-local-ip` 筛选客户端侧 ICE 地址。媒体端口范围为 UDP 19210–19273。
防火墙和 NAT 必须允许客户端访问 HTTP 信令端口及 ICE 实际公布的 UDP 地址。
公网和复杂 NAT 场景需要在目标网络中实测。

上游 NetherNet：<https://github.com/helix-bedrock/nethernet>，
固定提交 `3c60f0396e41d85e582ca3da2386384482467f1d`。
本目录不修改上游协议代码。

## 检测链路

```text
Bedrock 客户端 → NetherNet 前端 → Java 批次适配器 → 实际 NetherNet 后端
```

先在发行目录启动 Java 适配器。BDS 示例：

```sh
java -cp "lib/*" ac.navro.proxy.nethernet.NetherNetInspectServer 19138 --backend-type bds
```

再启动 Go 前端：

```sh
nethernet/bin/navro-nethernet -listen 0.0.0.0:19132 -adapter 127.0.0.1:19138 -backend http://127.0.0.1:19134 -offline-backend -detect
```

Windows 使用对应的 `.exe` 路径。后端例子要求你控制一台启用了 NetherNet 的实际 BDS，
其信令位于本机 19134。BDS 具体版本必须支持客户端所用的游戏协议。
旧 MOT 不支持 2225 的情况，不能靠代理转换协议解决。

`-offline-backend` 用独立的上游密钥为已验证的玩家重新签发私有后端登录。
客户端微软登录与 DTLS 身份绑定仍由公开入口验证。
当前此模式限制为同机回环后端，内部 IPC 也只接受回环地址；这些是已有的身份信任边界。
跨机器时可在受控安全隧道内暴露本地回环端点，不能直接把离线后端开放到公网。

Java 适配器默认 `--backend-type navro`。BDS 需要显式选 `bds`；
Nukkit/MOT、PMMP 等其他核心不能因为使用 NetherNet 就自动套用 BDS 适配。
后端必须实际提供 NetherNet 才能走此入口；仅提供 RakNet 的核心使用根项目的 RakNet 入口。

## Stock Geyser

支持连接未修改的私有 Geyser NetherNet 后端。Java 适配器加
`--stock-geyser-backend`，Go 前端使用：

```sh
nethernet/bin/navro-nethernet -listen 0.0.0.0:19132 -adapter 127.0.0.1:19138 -backend http://127.0.0.1:19532 -stock-geyser-backend -detect
```

私有 Geyser 开启 `advanced.bedrock.use-waterdogpe-forwarding: true`，
并配置 `advanced.bedrock.validate-bedrock-login: false`。
代理验证后的身份通过私有链路转发。Geyser 信令和媒体必须限制在回环，
Java 目标按 Geyser/Floodgate 的常规方式配置。此路径不需要改 Geyser JAR。

## 方块表

后端若使用数字 runtime ID 且未在 StartGame 提供完整表，需要传入实际使用的表：

```sh
java -cp "lib/*" ac.navro.proxy.nethernet.NetherNetInspectServer 19138 --block-palette palettes/backend-2225.nbt.gz
```

文件是 GZIP NBT compound，包含整数 `protocol` 和有序 compound 列表 `states`。
支持重复传入多个协议版本。表的顺序必须与该后端的实际数字 ID 一致。
`tools/GeyserRuntimePaletteExporter.java` 可从隔离的实际 Geyser 实例导出运行时顺序。
不能用不匹配的表制造一个“可用”的碰撞世界。

## 探针和透明中继

```sh
nethernet/bin/navro-nethernet-probe -address http://127.0.0.1:19132 -protocol 2225
```

探针验证真实 WebRTC 连接和 NetworkSettings，不能替代完整登录和玩家移动测试。

可选透明中继需要 Python 3.10 或更新版本：

```sh
python nethernet/relay.py --listen 0.0.0.0:19136 --backend 127.0.0.1:19134 --media 19140-19203
python -m unittest discover -s nethernet -p "test_*.py"
```

透明中继当前也按同机后端设计。它保持客户端到后端的 DTLS 身份和加密流量，
没有解密或运行反作弊检测。
