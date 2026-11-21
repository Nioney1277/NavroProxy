# NavroProxy

独立的 Minecraft Bedrock 协议代理，在客户端与后端之间执行已经移植的 Navro 检测、
移动预测、状态补偿与纠正。公开版本面向 Windows 和 Linux，使用 Java 21。
NetherNet 入口另需 Go 1.26 或更新版本。

项目仍在开发中。启动成功或协议握手成功不代表全部检查已经与所有后端完成兼容验证。
原 Navro 的完整功能尚未全部移植；当前迁移职责见 [NAVRO_STRUCTURE.md](NAVRO_STRUCTURE.md)。

## 构建

需要 JDK 21 或更新版本。Gradle Wrapper 与所有 Java 运行依赖均由项目及公开仓库提供，
无需安装 Geyser、Nukkit、原 Navro 源码或复制开发者本机的依赖目录。

Linux：

```sh
bash gradlew installDist
bash gradlew distZip
```

Windows PowerShell：

```powershell
.\gradlew.bat installDist
.\gradlew.bat distZip
```

首次构建需要联网下载 Gradle 和 Maven 依赖。Cloudburst 快照使用固定的发布构建号，
不自动跟随最新快照。测试使用仓库内的离线参考数据：

```sh
bash gradlew test
```

当前全量套件仍有四项修改前已存在的失败；CI 执行明确列出的协议和状态回归。
构建结果、实际服务器探测及可选桥接测试的限制见 [桌面验证记录](DESKTOP_BUILD_VALIDATION.md)。

开发用原生参考生成器位于 `tools/`，只在重新导出参考数据时需要对应的原 Navro/MOT 源码或 JAR，
不是运行代理、构建发行包或执行已提交参考测试的前提。

## 启动 RakNet 代理

发行目录为 `build/install/navro-proxy/`，ZIP 位于 `build/distributions/`。
复制整个发行目录，其中 `lib/` 包含运行所需的依赖；单独的项目 JAR 不是自包含发行包。

Linux：

```sh
bin/navro-proxy --listen 0.0.0.0:19132 --backend 127.0.0.1:19133 --mode detect
```

Windows PowerShell：

```powershell
.\bin\navro-proxy.bat --listen 0.0.0.0:19132 --backend 127.0.0.1:19133 --mode detect
```

跨平台 Java 入口：

```sh
java -cp "lib/*" ac.navro.proxy.NavroProxy --listen 0.0.0.0:19132 --backend 127.0.0.1:19133 --mode detect
```

工作目录保存 `config.yml`、权限、订阅、日志和密钥。仓库只提供 `config.example.yml`，
这些运行数据不应提交到 Git。首次运行可复制示例配置，也可由代理创建默认配置。

代理监听客户端的 UDP 端口。后端地址可配置为服务器 IP 或主机名；使用你管理的后端，
将其置于受控网络并阻止客户端绕过代理直接连接。
代理终止并验证客户端登录，后端需要支持相应的可信代理/离线接入方式。
它不是把玩家已有微软登录无条件重放到任意在线服务器的透明隧道。

## 后端与协议

代理不转换 Bedrock 游戏协议版本；客户端、后端和所选 codec 必须兼容。
监听器支持的协议目录位于 `SupportedCodecs.java`，当前从 729 到 2225 的已列出版本，
包括将 2192 显式映射到同布局的 2193。

| 后端 | 接入路径与配置 |
| --- | --- |
| Nukkit/MOT、PMMP 与其他 Bedrock 核心 | RakNet；使用默认 `--backend-type navro`，并配置后端信任代理。这个名称表示现有运动规则，不要求安装 Navro 插件。 |
| BDS | 支持当前已实现的 NetherNet 接入；Java 适配器显式使用 `--backend-type bds`。是否支持 RakNet 取决于具体 BDS 版本。 |
| Geyser → Java 服务端 | 可使用私有 NetherNet 的 stock Geyser 路径，或 RakNet/Floodgate 签名转发适配。见下方文档。 |

`--backend-type bds` 启用已实现的出生、属性及权威校正适配，不根据核心名称自动猜测。
已有 Boar 传送/ACK、用户定制的碰撞策略继续保留。
不要仅因更换机器或后端就修改检测阈值、确认顺序或物理规则。

使用数字方块 runtime ID、且没有发送完整方块表的后端，需要与实际协议、实际 ID 顺序匹配的
方块表；不能把另一核心的数字 ID 顺序直接套用。NetherNet 适配器可使用
`--block-palette /path/to/palette.nbt.gz`，格式见 [NetherNet 文档](nethernet/README.md)。

`--encrypt-backend` 是现有的同机回环 RakNet 加密模式，要求 `127.0.0.1` 后端。
远程部署可使用受控私网或安全隧道；不要把这个本机信任模式改成无验证的公网接入。

## NetherNet 与 Geyser

NetherNet 的 HTTP/WebRTC 监听器与 Java 检测适配器分开启动：

- [NetherNet 构建、启动和网络要求](nethernet/README.md)
- [Geyser/Floodgate 身份转发与可选适配](backend/geyser/README.md)

Java IPC 适配器只监听回环地址。公网开放的是客户端信令和配置的 UDP 媒体端口，
内部 IPC、可信后端及身份密钥不对客户端开放。

## 检测能力与可选桥接

`--mode detect` 执行已注册检查；`--mode inspect` 仅观察协议。
`smooth-setback: false` 是默认纠正模式。
管理命令为 `/navro alert`、`/navro reload`、`/navro debug`、`/navro debug2`，
权限和配置见 [NAVRO_COMMAND_ALIGNMENT.md](NAVRO_COMMAND_ALIGNMENT.md)。

标准协议可见的方块、实体、属性、库存和运动状态由代理维护。
真实后端 tick 等协议不可见事实通过可选能力协商提供，当前提供 Nukkit/MOT 插件；
其他核心可以实现同一同步协议。未协商到能力时，相应依赖项按既有规则跳过，
不把本地时钟或核心名称当作该能力。

```sh
python backend/nukkit/build.py --nukkit-jar /path/to/Nukkit-MOT.jar
```

插件是可选的核心适配，不进入代理生产类路径。接口和限制见
[BACKEND_STATE_SYNC.md](BACKEND_STATE_SYNC.md) 与 [插件说明](backend/nukkit/README.md)。

## 实现与验证记录

- [Boar 传送和 ACK](BOAR_TELEPORT_ALIGNMENT.md)
- [后端运动与回放边界](BACKEND_PREDICTION_ALIGNMENT.md)
- [碰撞与浮点策略](FLOAT_COLLISION_ALIGNMENT.md)
- [客户端预测声明](PREDICTION_SYNC_ALIGNMENT.md)
- [历史迁移记录](BOTTOM_LAYER_ALIGNMENT.md)

历史记录包含当时的本地测试环境和路径，不是公开版本的部署要求。
真实玩家登录、长时间移动及各后端自定义内容仍应在对应实际服务器上验证。
