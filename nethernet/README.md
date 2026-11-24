# 传输与启动

日常使用只需 Java 21 和解压后的完整发行包。在发行目录编辑
`plugins/Navro/network.yml`，然后运行 `bin/navro-proxy`；Windows 使用
`bin/navro-proxy.bat`。不再需要分别启动 Go 程序和 Java 适配器。

```yaml
frontend: raknet
listen: 0.0.0.0:19132
backend-transport: nethernet
backend-address: https://backend.example.net:19134
```

`frontend` 和 `backend-transport` 均可选择 `raknet` 或 `nethernet`，共四种组合。
RakNet 后端地址填写 `host:port`；NetherNet 后端地址填写信令 URL。
本机 NetherNet 可用 `http://127.0.0.1:19134`，远程使用 HTTPS。
检测始终启用；没有观察模式开关。

## 后端身份

`backend-auth` 默认 `auto`：RakNet 后端选用 `native`，NetherNet 后端选用
`private`。`private` 沿用代理原有玩家验证，向接受私有离线登录的后端重新
签署登录，不需要桥接插件实现协议转接。

NetherNet 客户端还可配置 `pmmp-ng` 或 `geyser`：前者使用
`backend-signing-key` 对应的代理签名密钥，后者要求 Geyser 配置
`advanced.bedrock.use-waterdogpe-forwarding: true` 和
`advanced.bedrock.validate-bedrock-login: false`。
RakNet → NetherNet 当前使用 `private` 身份方式。
客户端与后端须支持兼容的 Bedrock 协议版本；代理不转换游戏版本。

## 地址、端口与方块表

四种组合均允许远程后端地址。NetherNet 信令端口与 ICE UDP 端口分别开放，
远程后端须公布代理能访问的 ICE 地址。代理 NetherNet 传输使用 UDP
19210–19273；`local-ip` 可筛选 NetherNet 客户端入口的本地 IPv4 接口。
代理内部 Java 适配器始终只监听回环地址，无需向外开放。
NAT、公网连通性和实际后端登录需要在目标部署网络中验证。

PMMP-NG 桥接支持 `private-nethernet.signaling-bind-address`、
`ice-bind-address`、`advertised-address` 和 `ice-port-range`；默认仍监听本机。
远程接入时配置真实公告 IP、HTTPS 信令与 UDP 转发，详情见根目录 README。

`backend-type: bds` 选择 BDS 的移动语义。兼容状态同步桥接可启用
`backend-state-sync`。需要额外 runtime 方块表时，将路径放入
`block-palettes` 列表；文件为 GZIP NBT compound，包含整数 `protocol`
和按后端真实 runtime ID 排序的 compound 列表 `states`。

## 源码构建与诊断

从项目根目录运行 `bash gradlew distZip`，会自动构建并打包当前平台的 Go
传输程序；需要 JDK 21 和 Go 1.26。探针可单独构建：

```sh
cd nethernet
go test ./...
go build -o bin/navro-nethernet-probe ./probe
bin/navro-nethernet-probe -address http://127.0.0.1:19132 -protocol 2225
```

探针验证 WebRTC 连接和 NetworkSettings，不能替代完整登录和移动测试。
`relay.py` 是独立诊断工具，只转发加密流量，不运行代理检测，也不属于
上述四种代理组合。
