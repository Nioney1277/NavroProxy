# NetherNet 代理

NetherNet 前端在 Navro 侧终止 WebRTC，把原生 Bedrock 批次交给 Java 适配器。可以使用原版 Geyser 作为私有 NetherNet 后端。

## 构建

在此目录执行 `go build -o navro-nethernet ./inspect`。Android 上可加 `-ldflags=-checklinkname=0`。Java 依赖和构建方法见[项目根目录](../README.md)。

## 原版 Geyser 后端

- Geyser 的信令和媒体仅绑定回环地址，启用内置 NetherNet 信令。
- 在私有 Geyser 中配置 `advanced.bedrock.use-waterdogpe-forwarding: true` 和 `advanced.bedrock.validate-bedrock-login: false`。公开入口继续由 Navro 验证微软登录和前端 DTLS 身份。后端信任代理转发的数据，必须限制直连。此配置下原版 Geyser 不启用 Floodgate 皮肤上传。
- Java 适配器接受 `--stock-geyser-backend`；Go 前端使用 `-detect -stock-geyser-backend`。

```sh
java -cp 'build/install/navro-proxy/lib/*' ac.navro.proxy.nethernet.NetherNetInspectServer 19138 \
  --stock-geyser-backend --block-palette /path/to/palette.nbt.gz --operator-command-table

./navro-nethernet -listen 0.0.0.0:19132 -local-ip "$LAN_IPV4" \
  -detect -stock-geyser-backend -backend http://127.0.0.1:19532 -adapter 127.0.0.1:19138
```

`--operator-command-table` 可选。它向后端确认有 OP 的玩家发送完整命令表。在线撤销 OP 时发送空表清除缓存。命令执行权限由后端控制。

## 方块表

后端使用数字运行时 ID、又未在 `StartGame` 发送完整表时，可传 `--block-palette`。支持为不同协议多次指定。文件为 gzip NBT compound，包含数字字段 `protocol` 及按运行时 ID 排序的 compound 列表 `states`。

[导出器](../tools/GeyserRuntimePaletteExporter.java)可在隔离 Geyser 实例中使用。针对同一个 Geyser JAR 编译，打包时在 manifest 写入 `Premain-Class: GeyserRuntimePaletteExporter`，启动时加 `-javaagent:exporter.jar=2225:/path/to/palette.nbt.gz`。导出时使用与实际后端相同的版本、配置和自定义方块。
