# 玩家命令来源

代理的 `plugins/Navro/config.yml` 使用以下选项：

```yaml
command-source: proxy
```

允许值只有 `proxy` 和 `bridge`，默认 `proxy`。此设置绑定到玩家连接；修改后执行代理控制台 `navro reload`，再让玩家重新连接。直接重启代理也会读取新值。

## proxy

代理向玩家发布 `/navro`，并处理 `alert`、`reload`、`debug <player>`、`debug2 [player]`。安装桥接不再自动禁用代理命令。

权限沿用代理 `permissions.json` 的 `navro.alert`、`navro.reload`、`navro.debug`，以及服务端同步的管理员状态。聊天警报由代理订阅投递，避免同时收到桥接的重复消息；桥接控制台日志仍按原配置接收。

## bridge

Nukkit-MOT 或 PocketMine-MP-NG 桥接向玩家发布并处理 `/navro`，代理不再注入或拦截该命令。需同时更新代理和桥接，旧桥接不提供新命令。

| 命令 | 功能 | 后端权限 |
| --- | --- | --- |
| `/navro alert` | 开关本人接收的桥接聊天警报，保留原静音文件 | `navrobridge.alerts` |
| `/navro debug <player>` | 开关目标的详细预测输出 | `navrobridge.debug` |
| `/navro debug2 [player]` | 开关精确偏移输出，不填玩家时查看自己 | `navrobridge.debug` |
| `/navro reload` | 重载代理配置 | `navrobridge.reload` |

命令入口权限 `navrobridge.command` 默认所有玩家可见；各子命令仍独立鉴权，后三个权限默认 OP。原 `/navroalerts` 更名为 `/navro alert`，没有保留旧别名。

桥接先检查真实服务端权限，再通过当前代理连接的 nonce 和单调序号发送调试或重载请求。玩家不能从客户端伪造该私有消息；旧会话、重复序号和非允许子命令被忽略。输出通过发起者对应连接返回，桥接再次检查权限；权限撤销后停止向其显示调试数据。断线清理订阅。目标必须是同一代理进程中的在线玩家，名字中含空格也作为完整参数处理。

`command-source` 控制玩家命令及聊天警报的来源，不关闭方块、物品和服务器时钟同步。代理控制台的命令入口继续可用。NetherNet 检测入口在 `bridge` 模式会自动启用桥接消息通道；纯转发链路不会因此开启反作弊或命令处理。

PMMP 桥接以真实命令包结构补齐子命令提示，Nukkit 桥接使用原生命令参数定义。发布来源与权限分离：看到命令不代表获准执行管理操作。
