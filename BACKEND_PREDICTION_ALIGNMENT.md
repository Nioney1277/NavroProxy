# 后端校正与原版 Navro 对齐

本记录替代之前将 BDS 策略称为“通用 Navro 行为”的说明。上次会话从 BDS 受击排查开始的生产改动，连同此前 NetherNet 的 BDS 重生/疾跑接线，已逐项重新审查。运动、击退归属与历史回放重新实现；命令表编码和诊断中有独立验证依据的改动保留。

## 明确的后端选择

- `--backend-type navro` 为默认值：Nukkit/MOT、非 BDS 使用原版 Navro 的 ACK 和击退规则。
- `--backend-type bds` 显式开启 BDS 协议适配。
- RakNet 主启动器和 NetherNet Java 适配器均接受该选项。`SessionState` 的无参构造默认 `NAVRO`。
- 不根据 NetherNet/RakNet、端口、坐标、协议版本或 `serverEngine` 猜测后端。独立 BDS 抓包的 `serverEngine` 实际只有 `1.26.63`，不能作为可靠引擎标识。

NetherNet BDS 启动例：

```sh
java -cp 'path/to/lib/*' ac.navro.proxy.nethernet.NetherNetInspectServer 19138 --backend-type bds
```

原版 Navro 本身使用 Bedrock 客户端物理。`BdsTrigMath`、碰撞求解及浮点边界等名称里的 BDS 不表示“只适用于 BDS 后端”；本轮按实际契约拆分后端适配，而非按名称删除客户端算法。

## 两条路径的实际差异

| 行为 | NAVRO 默认路径 | 显式 BDS 路径 |
|---|---|---|
| Respawn SERVER_READY 坐标 | 原版握手，不作为移动基线 | 原生移动坐标在自己的 closing ACK 后建立基线 |
| 疾跑与 movement 属性 | 原版属性、modifier、metadata 处理 | 按后端 source tick 保留有效速度和较新疾跑动作 |
| 软校正 ACK | marker 回调立即接受，之后的输入处理 | 只将校正接受延迟到该入站批次结束 |
| 校正前外部击退 | 不额外删除；首个重放预测消费一次 | 同时按发送序号和原始后端 tick 判断是否已包含于校正基线 |
| 校正之后的新击退 | 保留其 ACK 与候选 | 同样保留，不按速度值比较 |

BDS 的 ACK 适配类已移到 `proxy/bds/BdsCorrectionAcknowledgments`。
`SessionState.setBdsCorrectionAcknowledgmentDispatcher` 拒绝在 NAVRO 会话安装延迟器。方块、属性、普通 motion ACK 均保持既有逐包时序。

## 重新实现的公共重放

依据原版 `AuthInputPackets.onPacketSend`、`TeleportHandler.processRewind`、`MovementReplayState` 和 `PlayerVelocityPackets`：

1. 不在收到校正包或 ACK 时移动预测位置；ACK 只接受队列条目。
2. 在后续 AuthInput 恢复 source tick 的权威位置、末速度、落地状态、碰撞和流体基线。优先使用与发送包完全匹配的不可变物理帧，否则平移历史六边界；Y offset 使用原历史观测。
3. 顺序执行真实 `PredictionRunner` 重放中间输入，执行原版的历史接受规则；历史输入不重新制造已消费击退，也不执行历史报警。
4. 输入恢复只恢复输入字段、运动声明和姿态，不再调用整份快照 `restore`。物理位置、末速度、碰撞连续状态、后端观测及元数据 ACK 队列不被历史覆盖。
5. 在 `finally` 恢复当前输入、item-use 和激流状态；当前输入交回正常入口预测。显式 BDS 路径在应用回放的这一帧使用已有的校正冷却：仍模拟并转发预测位置，不采纳客户端位置或速度，也不因回放前的位置误差再发一次纠正。下一帧恢复正常检查，NAVRO 默认路径不变。当前姿态提交后的结果不会被旧尺寸覆盖。
6. 源帧或中间历史缺失时使用权威硬传送重建基线，不填造输入。代理当前只保留 20 帧历史；这是明确的实现边界。
7. 已 ACK 但 source tick 尚未早于当前输入的校正留在队列，避免提前取出后静默丢失。

BDS 的外部 motion 归属使用具体包/候选身份与单调序号，替代原先两个 WeakHashMap。截止点在校正接收时冻结；未来的 ACK 不会复活已由 BDS 校正包含的候选。

## 命令和诊断的审查

管理命令现已补齐 `alert/reload/debug/debug2`，详情见 `NAVRO_COMMAND_ALIGNMENT.md`。保留并验证 `/navro` 帮助、`/navro:navro`、`/navro alert` 权限与订阅；命令表保留原有后端命令。Cloudburst 链式命令的空可选值按协议编码为缺省索引，真实 BDS 命令表含 11 个此类值。这个序列化修复属于协议数据处理，不需要按服务器引擎分支。

原始输入、运动和 ACK 诊断继续保留；`PredictionRunner` 在上次会话新增的部分仅为消费日志，不改变物理公式或检查阈值。`BadPacketsD` 保留用户版本。

## 验证依据与限制

- 已有原版 Navro 的连续状态、metadata、post-input、传送和碰撞参考。
- 构造协议场景验证实际 SessionState、NetherNet 与 RakNet 编解码/ACK 边界，并验证原版待处理击退不会被 BDS 策略吞掉。
- 三段真实连续 BDS 原始输入、motion/correction 与 ACK 记录经过生产入口。旧测试存在两个初始化问题：使用执行时钟瞬间灌包导致 Timer 拒绝；把空中起点设为 onGround。现按记录中的到达时间和 marker 发送时间驱动真实 Timer，并依据原始输入恢复接触状态。时钟夹具只替换外部时间条件，不替换 ACK 回调、物理或检查结果。
- 七组只有端点的旧诊断继续明确标注中间输入为构造条件，不作为完整客户端回放证据。
- 验证结果、失效敏感性和构建哈希见 `tools/backend-rewrite-validation.json`；逐文件审查见 `tools/backend-rewrite-review.md`。
- 用户随后要求更新测试入口。新版现按显式 BDS 模式部署到 `192.168.0.104:19137`，原测试后端也已恢复；最终服务就绪状态见验证 JSON 的 deployment 项。没有新的实机重连验证，不能宣称所有 BDS 受击误判已经消除。未跑全量回归。
