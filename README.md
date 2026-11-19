# NavroProxy

Minecraft Bedrock 反作弊代理。代理转发 Bedrock 流量，并根据已确认的封包维护移动、方块、实体和库存状态。后端插件只补充普通封包没有提供的服务端数据。

| 路径 | 内容 |
| --- | --- |
| `src/` | Java 代理源码、资源和回归测试 |
| `nethernet/` | 可选的 NetherNet 转发和验证工具 |
| `backend/` | 按服务器核心划分的后端插件 |

## 构建代理

使用 JDK 21 和 Gradle 9.4。当前版本依赖两组本地 JAR，不随仓库分发：

- `GEYSER_RUNTIME`：Geyser 的运行时依赖目录，默认 `~/geyser-inspect/build/runtime`。
- `CLOUDBURST_RUNTIME`：兼容的 Cloudburst codec、connection、common、NBT 和 RakNet JAR 目录，默认 `~/cloudburst-latest`。实际文件名见 `build.gradle.kts`。

```sh
gradle test installDist
build/install/navro-proxy/bin/navro-proxy --listen 0.0.0.0:19132 --backend 127.0.0.1:19133
```

## 后端插件

后端插件独立构建、独立发布。每个核心使用 `backend/<core>/`，发布标签使用 `<core>-v<version>`。不要把一个核心的插件安装在另一个核心上。

## NetherNet 工具

在 `nethernet/` 中运行 `python -m unittest test_relay` 检查 Python 中继。Go 工具使用 `go.mod` 固定的 Helix NetherNet 版本；如需使用本地源码，可用 `go work edit -replace` 指向同一版本的 checkout。Android 上构建需加 `-ldflags=-checklinkname=0`。
