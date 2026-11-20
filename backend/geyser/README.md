# Geyser / Floodgate 后端适配

链路：基岩客户端 → NavroProxy（RakNet、微软验签、反作弊）→ 同机 Geyser → 安装 Floodgate 的 Java 服务端。

NavroProxy 用微软登录链中的名称、XUID、UUID 和连接实际 IP 签发 30 秒有效的转发凭证。Geyser 用固定公钥验签并校验加密握手，再使用原有 Floodgate Java 握手转发玩家身份。客户端提供的 Waterdog 身份字段不会被采用。NetherNet 代理入口不使用本适配；自签转发会话不使用 Floodgate 的在线皮肤上传。

## NetherNet 检测入口

NetherNet 用 Geyser 终止 WebRTC。`navro-nethernet.patch` 在 Bedrock 批次解包前接入 `nethernet/NavroNetherNetGate.java`，将原生双向批次和 50 ms 检测节拍交给 Navro，经检测后才交给 Geyser 的 Bedrock 翻译层或客户端。Navro 验证原始微软登录和 NetherNet 信令公钥并回注订正包，不重签登录；Geyser 保留微软登录和 Floodgate 身份处理。

使用下面的同版本 Geyser 源码和 JAR，把补丁与 `NavroNetherNetGate.java` 编译并加入单独的入口 JAR（编译类路径需再加 `checker-qual`）。先用 NavroProxy 的运行类路径启动 `ac.navro.proxy.nethernet.NetherNetInspectServer 19138`，再启动入口 JAR。`-Dnavro.nethernet.adapter-port` 可改这个仅回环监听的端口。入口要求适配器确认 `NVB5` 强制检测模式；故障时断开玩家，不回退到直连。

`NVB5` 将最多 64 条检测请求合并为一次本机 IPC 往返；每个原始 Bedrock 批次仍单独按顺序检测，WebRTC 上的发包格式不变。积压时网关暂缓读取这条会话的 Java 连接，恢复后继续读取，不需要更改 Java 服务端或插件。适配器默认只记录每个方向首次出现的包类型和会话总计；可加 `-Dnavro.nethernet.trace-batches=true` 打开逐批次日志。

在入口配置 `bedrock.transport: nethernet`、`bedrock.port: 19132`、`bedrock.webrtc-port: 19141`，并保留 Java 后端的 Floodgate 配置和 `validate-bedrock-login: true`。信令 `19132/TCP` 与 RakNet 的 `19132/UDP` 独立，不要把内部适配端口 `19138/TCP` 暴露给客户端。

此 NetherNet 入口不向 Geyser 发送基岩后端插件专用的时钟握手；仍使用协议数据做检测和纠正，不把代理的 50 ms 检测节拍当作 Paper 的原生 Tick。

## 启用

1. 使用 `build/libs/Geyser-Standalone-2.12.1-b1305-Navro.jar`。Geyser 仍用 Java 后端 Floodgate 的 `key.pem`，这和下面生成的 Navro 身份签名密钥是两套不同的密钥。
2. 在 Termux 私有目录中生成 Navro 签名密钥。只把公钥交给 Geyser，不要把私钥放进 `/storage/emulated/0`、Git 仓库或发布包。

   ```sh
   umask 077
   mkdir -p "$HOME/.config/navro"
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:secp384r1 -out "$HOME/.config/navro/floodgate-private.pem"
   openssl pkey -in "$HOME/.config/navro/floodgate-private.pem" -pubout -out "$HOME/.config/navro/floodgate-public.pem"
   ```

3. 合并以下配置到 Geyser 的 `config.yml`。示例的端口须未占用；Geyser 2.12.1 的 `both` 模式要求 RakNet 和 WebRTC 使用不同的 UDP 端口。两个端口都仅绑定本机，不要对外映射。Java 地址和端口指向安装了 Floodgate 的服务端，`advanced.floodgate-key-file` 指向该 Floodgate 的密钥。

   ```yaml
   bedrock:
     address: 127.0.0.1
     port: 19133
     webrtc-port: 19134
     transport: both
   java:
     address: 127.0.0.1
     port: 25565
     auth-type: floodgate
     forward-hostname: false
   advanced:
     floodgate-key-file: key.pem
     bedrock:
       validate-bedrock-login: true
       use-waterdogpe-forwarding: false
       haproxy-protocol-whitelisted-ips: []
   ```

4. 启动 Geyser 时把 `-Dnavro.floodgate.public-key="$HOME/.config/navro/floodgate-public.pem"` 放在 `-jar` 前；NavroProxy 的 RakNet 启动参数加上 `--backend 127.0.0.1:19133 --encrypt-backend --floodgate-forwarding-key "$HOME/.config/navro/floodgate-private.pem"`。不传转发私钥时，NavroProxy 保持原来的后端行为。

## 重新构建

补丁基于 Geyser `1760ffb6bf1583d2e15ee336df5df45363b287d5`（2.12.1 build 1305）。从 Geyser 对应版本的干净源码目录运行，`NAVRO` 指向 NavroProxy 源码，`STOCK` 指向官方同构建的 Standalone JAR：

```sh
git apply "$NAVRO/backend/geyser/navro-floodgate.patch"
core=core/src/main/java/org/geysermc/geyser
out=$(mktemp -d)
javac --release 21 -proc:none -cp "$STOCK" -d "$out" \
    "$core/util/LoginEncryptionUtils.java" "$core/session/GeyserSessionAdapter.java" \
    "$NAVRO/backend/geyser/src/main/java/org/geysermc/geyser/util/NavroForwardedLogin.java"
mkdir -p "$NAVRO/build/libs"
cp "$STOCK" "$NAVRO/build/libs/Geyser-Standalone-2.12.1-b1305-Navro.jar"
jar --update --file "$NAVRO/build/libs/Geyser-Standalone-2.12.1-b1305-Navro.jar" -C "$out" .
```
