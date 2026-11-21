# Navro 命令对齐

对照原版 `Navro/command/NavroCommand.java`、`check/impl/prediction/DebugOffsetA.java`、`config/ConfigLoader.java` 实现。

| 命令 | 行为 | 权限 |
|---|---|---|
| `/navro alert` | 切换自己的报警订阅，持久化到 alert-subscriptions.json | navro.alert |
| `/navro reload` | 从代理工作目录的 config.yml 重载已移植功能的配置 | navro.reload |
| `/navro debug <player>` | 切换目标玩家的四行预测调试输出 | navro.debug |
| `/navro debug2 [player]` | 十位小数的 offset／预测位移／实际位移；玩家不填目标时选择自己 | navro.debug |

OP 默认拥有三个命令权限，permissions.json 中的显式拒绝优先。debug2 与 debug 共用权限节点，原版没有单独的 navro.debug2。控制台可执行同样命令，debug 和 debug2 都需要显式玩家名。裸命令和未知子命令显示原版的四条用法；`/navro:navro` 同样处理。玩家名按完整名称忽略大小写查找，含空格名字可加引号。

命令声明现在包含全部四个子命令和可选 player 参数。后端已经声明 navro 时由代理替换该声明，其他命令保持原样；不会重复添加。RakNet 和 NetherNet 使用同一个命令处理器。

## 调试输出

订阅挂在目标会话。实际预测完成后、接受客户端位置之前发送，沿用原版 DebugOffset 的触发条件与颜色。debug 是带 Navro 前缀的四行，debug2 是不加前缀的多行 DEBUGGER。两种订阅可同时开启，再次执行对应命令关闭；它们不会开启报警订阅，也不会修改预测状态或容差。目标断开清除该目标的订阅；观察者断开清除其在其他目标上的订阅，重连不会复用旧连接的消息回调。

## 配置重载范围

自动生成代理自身的 config.yml，使用原版键名：

- prefix
- player-rewind-history-size-ticks
- smooth-setback
- player-position-acceptance-threshold
- differ-till-alert
- disabled-checks
- max-latency-wait
- max-balance-advantage

AirLiquidPlace 还受有效服务端 tick 能力门控；仅移除 disabled-checks 中的名字不会使无能力后端触发该项。

在线会话在下一次输入边界读取完整的新配置；不从命令线程修改运动状态。消息前缀立即使用新值。断线等待、回放历史窗口和已移植检测的启用状态均使用重载设置。配置文件存在时优先于初始化默认值，启动参数提供首次生成文件的默认值。地址、协议、后端类型等连接设置仍是启动参数。

原版配置还有未迁移检查的选项，代理不会声称这些检查已实现。为避免损坏配置导致意外改变检测，解析失败时会回复失败并保留上一份有效配置；这比原版 ConfigLoader 的默认值回退更明确，是代理文件适配的行为差异。

## 验证与限制

命令测试通过真实 NavroCommand 处理器切换订阅、通过真实 SessionState/PredictionRunner 生成输出，并检查 reload 对在线预测配置和纠正选择的影响。原版 NavroCommand.execute 的 14 组结果及 DebugOffsetA.debug2Message 的格式由独立原版执行器导出。协议测试核对各版本命令表编解码和真实 BDS 命令表原有内容。

专项、失效检查、源码差异及构建哈希记录于 tools/navro-command-alignment-validation.json。未跑全量回归；保留用户 BadPacketsD 修改。此轮命令工作不宣称解决 BDS 受击误判或硬传送确认丢失。


本轮结果：144 项相关专项通过，5 项可编译失效变体均被目标断言捕获；原版 14 组命令回复和 debug2 格式对照通过；实际 Java 适配器控制台的帮助、debug2 缺参数和 reload 已验证。JAR 与 installDist 一致，SHA-256 为 `4a7e759766260fce06b2522fcdf27a7f03a393b71c61332d880dfaed27271db7`。新版已构建，并已按用户要求替换到测试入口 `192.168.0.104:19137`；代理与后端连接接口均返回 200，原 BDS 后端未重启，配置、权限和报警订阅文件已保留。真实客户端重连验证仍待进行。
