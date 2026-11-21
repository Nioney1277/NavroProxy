# 协议可见的实体底层状态

本轮只使用代理实际收到的实体注册表、生成、元数据、属性、运动和骑乘链接包。
既有 Boar 传送、ACK 调度、普通传送目标转发、插值和用户碰撞策略继续保留。

## 已接入

- `AvailableEntityIdentifiersPacket` 的 `idlist` 提供当前连接的 identifier → rid 映射。
  现代 AddEntity 只携带字符串标识时，可用该表恢复矿车等类型判断需要的数值类型。
  替换表仅作用于本连接，不从其他连接或核心名称推导。表不提供碰撞尺寸，尺寸缺失时仍不造默认盒。
- 远端实体的 UpdateAttributes、SetEntityMotion、SetEntityData 附带属性走原有真实出站批次
  closing ACK。列表、修饰器及属性在接收时复制；包对象复用不会改写待提交的数据。
  实体 runtime ID 已复用时，旧回调不会修改旧对象或替代对象。
  属性按名称、实体 properties 按各自的索引增量提交；包里没有出现的条目继续保留，
  空更新也不清空已有属性。此边界同时核对 GeyserEntityPropertyManager 的实际增量发包路径。
- AddEntity/AddPlayer 附带的 entityLinks 现在与独立 SetEntityLink 一样，在所属生成包的
  closing ACK 后使用已有链接处理器提交。已有本地骑乘、传送队列失效规则保持原样。
- MoveEntityDelta 只对实际携带的 Y 坐标扣除一次实体底座偏移。没有 Y 的更新直接保留
  上一个已发目标的物理 Y，避免船在仅更新 X/Z 时反复下沉。
- 已明确无 Y 偏移的实体可用 MovePlayer 更新位置，复用现有出站目标、ACK 和插值流程；
  HEAD_ROTATION 不移动碰撞盒。

接收声明和实体注册信息不代表它们已经在客户端应用。注册表用于解释后续包；
远端实体可见属性与运动按各自实际批次确认，沿用项目已有的发送和确认边界。

## 原版边界

对照原 Navro 的 ServerEntityPackets、EntityCache、VehiclePackets 和现有实体测试，
保留位置的发送目标/已确认目标区分及原插值时长。补齐已有标准包中漏接的事实，
并修正缺失 Y 的增量包重复减偏移的问题；不将该修正描述成逐字复制原版行为。

原 Navro 的其他玩家 MovePlayer 归一化会读取 `viewer.getLevel().getEntity(...)` 的
真实脚部 Y，选择包里的 Y 是脚部还是带底座偏移的坐标。包没有给出这一生产路径，
代理无法无歧义执行该选择。本轮没有用“离上一帧更近”或核心名称猜测替代；
有非零偏移的其他玩家 MovePlayer 仍是明确缺口。

其他不由这些标准包提供的事实，例如后端私有 riding 字段、真实服务端 tick、
仅数量库存响应缺少的完整身份/NBT、自定义内容缺少的几何形状，继续使用既有能力门控
或明确保留缺口。它们不会由代理本地计时、客户端声明或通用立方体补造。

## 验证

`EntityPacketLifecycleTest` 使用实际 Bedrock 编解码、出站批次和 NetworkStackLatency
确认队列。新增场景覆盖独立批次、包复用、实体 ID 复用、全部七种增量轴组合及生成链接。
`NetworkEntityRegistryTest` 在所有已列出的支持版本上执行真实注册表包的编码/解码，
并覆盖连接隔离、表替换和冲突条目。既有 VehicleReferenceTest 继续核对原版链接行为。

这些是明确构造的协议场景，不是完整真实客户端移动记录。

本轮最终专项为 23 个测试类、193 项，失败、错误、跳过均为零；包括实体、骑乘、
碰撞、实际 NetherNet 批次、输入预测、历史回放、库存响应和诊断故障回归。
`jar installDist --offline` 成功，五个修改的生产 class 与 JAR 内字节一致，
安装目录的 JAR 与构建 JAR 一致，测试类没有进入生产包。

TeleportUtil、TeleportHandler、PostAuthInputPackets、BadPacketsD 和 Collider 的 SHA-256
与本轮修改前一致。未跑全量回归，未部署，也未上传这一轮改动。

JAR SHA-256：`6e64570f244504f9a59d52ccd9ec9d90f7a5e61a10e98f71ddf6462a21395df9`。
