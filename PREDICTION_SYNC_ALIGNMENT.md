# 客户端预测声明底层接入

依据当前原 Navro 的 `ClientMovementPredictionSyncPacket`、`PredictionSyncState`、
`PacketListener` 和 `AuthInputPackets` 补齐声明的编解码、独立暂存和输入前消费。

声明按 ActorUniqueID 校验身份；`-1` 无效，其他负值仍为合法有符号身份。
同一时刻保留第一份尚未消费的声明，后续声明不能覆盖它。所有标量和旗标在接收时复制，
客户端包对象的后续修改不影响暂存结果。StartGame 清除旧声明，连接内序号保持递增。

包号 322 的三种布局分别采用协议 776、786、975 的字段边界：
786 起包含 flying，975 起在有符号 ZigZag ActorUniqueID 前包含三个额外 float。
保留完整的任意精度无符号旗标掩码，包括 SDK 尚未命名的位；沿用原 Navro 的 32 字节上限。
这些原始位不能用 SDK 枚举序号代替。旧版本复用包对象时清除旧旗标及不存在的可选字段。

声明在包检查后通过的下一次 AuthInput 中、动作处理前消费。拒绝的输入不消费声明。
诊断记录接收结果、声明内容与消费时的规范状态。声明不会写入规范碰撞箱、旗标、速度、
属性或飞行状态；原 Navro 当前也只在此边界保留证据，尚未实现完整的客户端声明校验器。

现有 Boar 传送队列、closing ACK、普通 MovePlayer 目标转发和 NORMAL 插值规则继续生效。
用户的 BadPacketsD、几何重叠解算、显式 BDS 后端适配与击退归属也继续保留。

## 验证依据

`tools/generate-prediction-sync-reference.py` 直接编译、执行当前原 Navro 的声明解码器和
状态类，生成 512 次跨版本接收、消费、清理与身份切换结果。生成工具只构造输入字节，
不另写状态转移模型。来源哈希保存在 `tools/prediction-sync-reference-sha256.json`。

专项还覆盖独立协议字节、全部支持此包的 codec、未知高位旗标、长整型身份极值、截断包、
包对象复用、声明与物理状态隔离、输入拒绝、输入动作前的消费边界、日志输出故障以及
实际 NetherNet 批次原字节转发。这些测试不代替真实玩家移动实测。

本轮相关专项共 69 项、10 个测试类全部通过，失败、错误、跳过均为零；其中原版参考测试
包含 512 次状态转移，既有连续运动状态测试包含 4096 次转换。`jar --offline` 构建成功。
Boar 传送文档、TeleportUtil、TeleportHandler、PostAuthInputPackets、BadPacketsD 和 Collider
的 SHA-256 与本轮修改前一致。未运行全量回归，未部署或重启服务。

构建 JAR SHA-256：`bb6c82995a5cde84fdf9dd171535ef10b7f827ac6ab66241298bcec2fb96ef50`。
