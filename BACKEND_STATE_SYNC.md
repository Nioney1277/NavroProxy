# 后端缺失事实同步与放置检测能力门控

AirLiquidPlace 的两个入口（独立 InventoryTransaction、AuthInput 内嵌交互）只在
`world.hasNativeServerTick()` 为真且配置没有禁用此项时执行。没有收到有效 tick、
旧连接消息、序号过期或时钟样本失效时，跳过该项；不因为缺少 tick 把放置放进等待队列。
其他放置检查和普通库存校验继续执行。

启用依据是同步协议能力，不是服务器核心名称、serverEngine 字符串、BackendType、
传输方式或客户端版本。所有受支持后端会话均可协商；当前随项目提供的实现位于原有
Nukkit-MOT 后端插件。其他核心实现同一协议后也能提供该能力。

## 只补代理不可见的事实

1. 后端进程真实 `Server.getTick()` 的有序样本。玩家输入 tick、实体包 source tick、
   世界时间、代理本地定时器均不冒充服务端 tick。
2. 事务执行后、原生标准库存包未确认的最终槽位身份。物品、NBT、数量、网络堆栈 ID
   直接从服务端实际 Inventory 读取，使用原生 sendSlot/sendArmorSlot 编码发送。

未重复同步移动、区块、方块、药水、权限等已有标准数据。库存也不使用自定义物品序列化；
普通库存包已经确认的槽位不再补发，不做周期性整背包复制。服务器事务取消/拒绝时同样只读取
最终实际库存，不将 InventoryTransactionEvent 的“未取消”当成执行成功。

## 连接与顺序

代理在 StartGame 后通过保留的 ScriptMessage 通道 `navro:backend_sync_v1` 发送
`hello|nonce`；后端回复 `tick|nonce|sequence|serverTick`。每个连接独立 nonce，
序号及 tick 不倒退，重复包不延长有效期；新 StartGame 清除旧能力。
保留通道只允许从后端进入代理状态，客户端尝试发送时直接丢弃。内部消息不发送给客户端。

插件每个实际服务端 tick 发送一个样本，在方块更新前也确保该 tick 已发送。
一秒内没有新的有效样本则能力失效。这里的“原生 tick”是最新收到的真实样本，
仍受网络延迟影响，不能宣称等于后端此刻的零延迟计数器。

与原版 Navro 一样，成功的 `ItemStackResponse` 在发出时按 Nukkit 原生映射读取真实槽位；
缺少完整身份时，在响应进入发送队列后紧跟原生 `InventorySlotPacket`。不把捆绑袋内索引当作普通槽位。
工作站的组件本地槽位通过原生 UI 容器 `124` 发出，代理在 ACK 后只更新对应的已打开工作站；
共用的合成输出继续走 HUD 槽位，关闭或切换窗口会丢弃旧的待确认数据。
旧式事务事件只跟踪原生 `SlotChangeAction`；转发事务相关包还保留下一原生 tick 的保守兜底，
因为使用物品可能改变请求未列出的槽位。已确认包与最终值一致时不再补发；发送取消或失败则重试。
关闭、复用窗口或切换维度时丢弃旧窗口请求，玩家退出时清理连接状态。

代理收到补发库存包后，复用现有 BackendInventorySnapshots、InventoryResponseSync
和 PlayerInventoryPackets：完整后端快照清除该槽位 pending，客户端可见补偿库存仍等实际
closing ACK 才提交。BDS 没有安装该插件时不生成能力，也不因此阻止放置。

## 构建与验证

后端产物：`backend/nukkit/build/NavroBackendBridge.jar`。插件内部名称保持不变，部署时不要同时保留旧 JAR。
构建：`python backend/nukkit/build.py --nukkit-jar /path/to/Nukkit-MOT.jar`。

后端测试执行真实 Nukkit InventoryTransaction.execute / SlotChangeAction / BaseInventory
与原生库存编码，生成 `native-transaction-slots.json`；代理测试解码这些原生字节并核对 ACK
前后的缓存。另覆盖无握手零流量、真实 tick 跳跃、普通库存包去重、取消发送重试、窗口关闭、
退出、客户端伪造消息、新旧连接与过期能力。

后端构建和集成测试结果见 `backend/nukkit/build/validation.json`。
