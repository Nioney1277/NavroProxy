# Navro 文件与职责对应表

保留代理根命名空间；已迁移的原版职责使用相同目录、类名。对应关系不代表尚未迁移的原版功能已实现。历史轮次的 SHA 审计保持原始路径，由本表记录当前去向。

## 原版职责归位

| 修改前 | 当前职责所有者 |
| --- | --- |
| `ac.navro.proxy.prediction.live.ClimbableBlockPhysics` | `ac.navro.proxy.prediction.ClimbableBlockPhysics` |
| `ac.navro.proxy.prediction.live.GlidingState` | `ac.navro.proxy.prediction.GlidingState` |
| `ac.navro.proxy.prediction.live.GroundTravelPhysics` | `ac.navro.proxy.prediction.GroundTravelPhysics` |
| `ac.navro.proxy.prediction.live.RiptidePhysics` | `ac.navro.proxy.prediction.RiptidePhysics` |
| `ac.navro.proxy.prediction.live.SneakInputPhysics` | `ac.navro.proxy.prediction.SneakInputPhysics` |
| `ac.navro.proxy.prediction.live.SprintJumpPhysics` | `ac.navro.proxy.prediction.SprintJumpPhysics` |
| `ac.navro.proxy.prediction.live.StickyBlockPhysics` | `ac.navro.proxy.prediction.StickyBlockPhysics` |
| `ac.navro.proxy.prediction.live.PredictionRunner` | `ac.navro.proxy.prediction.PredictionRunner` |
| `ac.navro.proxy.prediction.live.GlidingPredictionEngine` | `ac.navro.proxy.prediction.engine.impl.GlidingPredictionEngine` |
| `ac.navro.proxy.prediction.live.GroundAndAirPredictionEngine` | `ac.navro.proxy.prediction.engine.impl.GroundAndAirPredictionEngine` |
| `ac.navro.proxy.prediction.live.LavaPredictionEngine` | `ac.navro.proxy.prediction.engine.impl.fluid.LavaPredictionEngine` |
| `ac.navro.proxy.prediction.live.WaterPredictionEngine` | `ac.navro.proxy.prediction.engine.impl.fluid.WaterPredictionEngine` |
| `ac.navro.proxy.prediction.live.PredictionEngine` | `ac.navro.proxy.prediction.engine.base.PredictionEngine` |
| `ac.navro.proxy.prediction.live.EntityTicker` | `ac.navro.proxy.prediction.ticker.base.EntityTicker` |
| `ac.navro.proxy.prediction.live.LivingTicker` | `ac.navro.proxy.prediction.ticker.impl.LivingTicker` |
| `ac.navro.proxy.prediction.live.PlayerTicker` | `ac.navro.proxy.prediction.ticker.impl.PlayerTicker` |
| `ac.navro.proxy.prediction.live.ScaffoldingCollision` | `ac.navro.proxy.collision.ScaffoldingCollision` |
| `ac.navro.proxy.prediction.live.Velocity` | `ac.navro.proxy.prediction.engine.data.Vector` |
| `ac.navro.proxy.prediction.live.BlockPosition` | `ac.navro.anticheat.util.math.Mutable` |
| `ac.navro.proxy.prediction.live.PredictionBlock` | `ac.navro.proxy.data.block.NavroBlockState` |
| `ac.navro.proxy.prediction.live.PredictionWorld` | `ac.navro.proxy.compensated.world.CompensatedWorldImpl` |
| `ac.navro.proxy.prediction.fluid.ProtocolLiquidPhysics` | `ac.navro.proxy.prediction.fluid.LiquidPhysics` |
| `ac.navro.proxy.state.tracker.FlagTracker` | `ac.navro.proxy.player.data.tracker.FlagTracker` |
| `ac.navro.proxy.world.ProtocolFluidState` | `ac.navro.proxy.data.FluidState` |
| `ac.navro.proxy.prediction.live.Collider` | `ac.navro.proxy.collision.Collider` |
| `ac.navro.proxy.prediction.live.LiveFluidMovement` | `ac.navro.proxy.prediction.fluid.FluidMovement` |
| `ac.navro.proxy.prediction.live.LivePrediction` | `ac.navro.proxy.packets.input.AuthInputPackets` |
| `ac.navro.proxy.prediction.live.PredictionContext.PredictionData` | `ac.navro.proxy.data.input.PredictionData` |
| `ac.navro.proxy.prediction.live.PredictionContext.FluidState` | `ac.navro.proxy.prediction.fluid.FluidPredictionState` |

输入包编排归 AuthInputPackets，动作处理归 LegacyAuthInputPackets，服务端速度包及其 ACK 候选归 PlayerVelocityPackets。Collider 和 FluidMovement 吸收原 live 桥接重载；PredictionData 与 FluidPredictionState 从候选上下文提取到原版数据职责目录。

## 保留的代理专有文件

| 文件 | 职责及保留理由 |
| --- | --- |
| `ac/navro/anticheat/collision/CollisionShapeRegistry.java` | 离线导出的碰撞表与 runtime-ID 查询，不依赖服务端核心 |
| `ac/navro/anticheat/collision/ProtocolCollisionSolver.java` | 协议提供的形状列表到精确碰撞数学的适配入口 |
| `ac/navro/anticheat/util/math/Direction.java` | 替代运行时核心方向枚举的几何兼容类型 |
| `ac/navro/proxy/NavroProxy.java` | 代理启动、连接管理及检查服务装配 |
| `ac/navro/proxy/PacketRelay.java` | 保留入站字节/引用计数的代理转发 |
| `ac/navro/proxy/ProtocolDefinitions.java` | Cloudburst 编解码器的协议 definition 注册 |
| `ac/navro/proxy/ProxyConfig.java` | 代理 CLI、监听及后端连接配置 |
| `ac/navro/proxy/ProxySession.java` | 客户端/后端双连接、登录加密与代理传输 |
| `ac/navro/proxy/SessionState.java` | 协议观察及包处理模块的会话装配边界 |
| `ac/navro/proxy/SupportedCodecs.java` | 代理可用的 Cloudburst codec 版本目录 |
| `ac/navro/proxy/collision/ProtocolCollisionWorld.java` | 协议补偿块/实体状态到碰撞查询的适配 |
| `ac/navro/proxy/collision/ProtocolEntityTolerance.java` | 协议实体插值及碰撞容差快照 |
| `ac/navro/proxy/command/CommandArguments.java` | 代理命令行文本解析 |
| `ac/navro/proxy/command/NavroCommand.java` | 原版 alert/reload/debug/debug2、帮助、权限与命令声明 |
| `ac/navro/proxy/command/CommandRuntime.java` | 在线目标会话与可重载配置 |
| `ac/navro/proxy/config/ConfigLoader.java` | 代理工作目录的 config.yml 加载 |
| `ac/navro/proxy/check/impl/prediction/DebugOffsetA.java` | 实际预测完成后的原版调试输出 |
| `ac/navro/proxy/compensated/world/BackendBlockSnapshots.java` | 只供纠正使用的后端已发送地形，不参与补偿碰撞 |
| `ac/navro/proxy/data/block/BlockBreakState.java` | 挖块校验器所需的 detached 原生属性视图 |
| `ac/navro/proxy/data/block/BlockStateEncoding.java` | 离线状态属性编码键 |
| `ac/navro/proxy/data/block/NativeBlockProperties.java` | 导出原生方块属性的运行时查询 |
| `ac/navro/proxy/data/block/NativeInteractionProperties.java` | 导出原生交互属性的运行时查询 |
| `ac/navro/proxy/data/block/NativePistonProperties.java` | 导出活塞属性的运行时查询 |
| `ac/navro/proxy/data/block/NativePlacementProperties.java` | 导出放置属性的运行时查询 |
| `ac/navro/proxy/data/block/NativePlantProperties.java` | 导出植物属性的运行时查询 |
| `ac/navro/proxy/data/block/NativeUseItemOnProperties.java` | 导出物品对方块使用属性的运行时查询 |
| `ac/navro/proxy/data/inventory/NativeItemDecoder.java` | 将 wire item 恢复为离线原生物品视图 |
| `ac/navro/proxy/data/inventory/NativeItemPlacementProperties.java` | 导出物品放置属性查询 |
| `ac/navro/proxy/data/inventory/NativePredictionItems.java` | 导出预测所需物品属性查询 |
| `ac/navro/proxy/data/vanilla/GameType.java` | 原生游戏模式的无核心依赖兼容枚举 |
| `ac/navro/proxy/data/vanilla/PlayerActionType.java` | 原生动作的无核心依赖兼容枚举 |
| `ac/navro/proxy/diagnostics/WorldCollisionDiagnostics.java` | 代理独立 JSONL 诊断输出与快照 |
| `ac/navro/proxy/entity/EntityLinkState.java` | 协议实体骑乘链接跟踪 |
| `ac/navro/proxy/inventory/InventoryType.java` | 协议窗口类型兼容枚举 |
| `ac/navro/proxy/permission/ProtocolPermissions.java` | 协议观察到的服务端权限状态 |
| `ac/navro/proxy/permission/ProxyPermissions.java` | 代理本地权限存储及解析 |
| `ac/navro/proxy/prediction/PredictionSample.java` | 检查/候选帧的协议无关视图，不冒充完整 NavroPlayer |
| `ac/navro/proxy/prediction/ProtocolBlockEffects.java` | 协议快照回放中显式传参的方块效果适配 |
| `ac/navro/proxy/prediction/ProtocolGlidingPrediction.java` | 协议快照回放中显式传参的滑翔计算适配 |
| `ac/navro/proxy/prediction/ProtocolGroundAirPhysics.java` | 协议快照回放的标量物理入口 |
| `ac/navro/proxy/prediction/ProtocolGroundAirPrediction.java` | 协议快照回放的地面/空中多阶段编排 |
| `ac/navro/proxy/prediction/ProtocolInputMath.java` | wire 输入向量的显式参数归一化与缩放适配 |
| `ac/navro/proxy/prediction/ProtocolPredictionReplay.java` | 历史协议帧回放编排，与实时 PredictionRunner 分开 |
| `ac/navro/proxy/prediction/ProtocolRiptidePrediction.java` | 协议快照回放的激流计算入口 |
| `ac/navro/proxy/prediction/WireMotionSeed.java` | 后端运动包对应的历史帧种子 |
| `ac/navro/proxy/prediction/fluid/FluidPoseState.java` | wire 物理 AABB/姿态请求与回放兼容状态 |
| `ac/navro/proxy/prediction/fluid/ProtocolFluidPrediction.java` | 协议快照液体运动回放适配 |
| `ac/navro/proxy/prediction/fluid/ProtocolFluidStateTracker.java` | 每会话已观察液体状态与不可变回放快照 |
| `ac/navro/proxy/prediction/live/NativeIds.java` | 离线冻结的原生 ID 常量，避免运行时核心依赖 |
| `ac/navro/proxy/prediction/live/PredictionContext.java` | 会话状态到实时引擎候选帧的适配视图 |
| `ac/navro/proxy/prediction/live/PredictionItemUse.java` | ItemUseTracker/FlagTracker/物品事务的代理上下文实现 |
| `ac/navro/proxy/protocol/BackendProtocolProfile.java` | StartGame 公布的协议行为参数快照 |
| `ac/navro/proxy/protocol/LoginIdentity.java` | 代理登录身份解析与验证边界 |
| `ac/navro/proxy/state/MovementMetadata.java` | wire 元数据及输入动作到移动语义的适配 |
| `ac/navro/proxy/state/WireAcknowledgment.java` | 协议 ACK 观察记录 |
| `ac/navro/proxy/state/WireAnomaly.java` | 协议观察异常记录 |
| `ac/navro/proxy/state/WireEvent.java` | 代理收发事件记录 |
| `ac/navro/proxy/state/WirePlayerState.java` | 原始协议输入、ACK 状态和物理 AABB 的会话适配层 |
| `ac/navro/proxy/state/WireTickSnapshot.java` | 协议输入帧观察快照 |
| `ac/navro/proxy/state/WireVec2.java` | wire 二维向量值 |
| `ac/navro/proxy/state/WireVec3.java` | wire 三维向量值 |
| `ac/navro/proxy/teleport/ProtocolTeleportMotion.java` | 协议传送/速度包回放快照 |
| `ac/navro/proxy/teleport/TeleportSender.java` | 代理新编码纠正包的传输边界 |
| `ac/navro/proxy/util/Hash.java` | 协议状态键哈希辅助 |
| `ac/navro/proxy/util/NbtNumber.java` | Cloudburst NBT 与原生数字类型语义适配 |
| `ac/navro/proxy/util/NbtString.java` | Cloudburst NBT 字符串读取语义适配 |
| `ac/navro/proxy/util/block/BlockActionText.java` | 代理挖块动作诊断格式 |
| `ac/navro/proxy/util/block/BlockCorrectionSender.java` | 代理方块纠正包与真实出站批次 ACK 传输 |
| `ac/navro/proxy/world/WireBlock.java` | 线上的方块 definition/网络状态视图 |

## 与 Navro 同目录同名的已迁移文件

| 代理文件 | Navro 原文件 |
| --- | --- |
| `ac/navro/anticheat/collision/AcceptedPositionPhysics.java` | `ac/navro/anticheat/collision/AcceptedPositionPhysics.java` |
| `ac/navro/anticheat/collision/ClipCollideResult.java` | `ac/navro/anticheat/collision/ClipCollideResult.java` |
| `ac/navro/anticheat/collision/CollisionQuery.java` | `ac/navro/anticheat/collision/CollisionQuery.java` |
| `ac/navro/anticheat/collision/SneakEdgePhysics.java` | `ac/navro/anticheat/collision/SneakEdgePhysics.java` |
| `ac/navro/anticheat/collision/SweptMovement.java` | `ac/navro/anticheat/collision/SweptMovement.java` |
| `ac/navro/anticheat/collision/util/CuboidBlockIterator.java` | `ac/navro/anticheat/collision/util/CuboidBlockIterator.java` |
| `ac/navro/anticheat/data/EntityDimensions.java` | `ac/navro/anticheat/data/EntityDimensions.java` |
| `ac/navro/anticheat/data/MovementAabbSnapshot.java` | `ac/navro/anticheat/data/MovementAabbSnapshot.java` |
| `ac/navro/anticheat/data/input/InputPosition.java` | `ac/navro/anticheat/data/input/InputPosition.java` |
| `ac/navro/anticheat/data/vanilla/AttributeInstance.java` | `ac/navro/anticheat/data/vanilla/AttributeInstance.java` |
| `ac/navro/anticheat/data/vanilla/StatusEffect.java` | `ac/navro/anticheat/data/vanilla/StatusEffect.java` |
| `ac/navro/anticheat/util/BdsTrigMath.java` | `ac/navro/anticheat/util/BdsTrigMath.java` |
| `ac/navro/anticheat/util/ClientFloatMath.java` | `ac/navro/anticheat/util/ClientFloatMath.java` |
| `ac/navro/anticheat/util/MathUtil.java` | `ac/navro/anticheat/util/MathUtil.java` |
| `ac/navro/anticheat/util/NavroTrigMath.java` | `ac/navro/anticheat/util/NavroTrigMath.java` |
| `ac/navro/anticheat/util/Pair.java` | `ac/navro/anticheat/util/Pair.java` |
| `ac/navro/anticheat/util/math/Box.java` | `ac/navro/anticheat/util/math/Box.java` |
| `ac/navro/anticheat/util/math/Mutable.java` | `ac/navro/anticheat/util/math/Mutable.java` |
| `ac/navro/anticheat/util/math/Vec3.java` | `ac/navro/anticheat/util/math/Vec3.java` |
| `ac/navro/proxy/alert/AlertManager.java` | `ac/navro/anticheat/alert/AlertManager.java` |
| `ac/navro/proxy/alert/AlertSubscriptions.java` | `ac/navro/anticheat/alert/AlertSubscriptions.java` |
| `ac/navro/proxy/check/api/Check.java` | `ac/navro/anticheat/check/api/Check.java` |
| `ac/navro/proxy/check/api/annotations/CheckInfo.java` | `ac/navro/anticheat/check/api/annotations/CheckInfo.java` |
| `ac/navro/proxy/check/api/annotations/Experimental.java` | `ac/navro/anticheat/check/api/annotations/Experimental.java` |
| `ac/navro/proxy/check/api/holder/CheckHolder.java` | `ac/navro/anticheat/check/api/holder/CheckHolder.java` |
| `ac/navro/proxy/check/api/impl/OffsetHandlerCheck.java` | `ac/navro/anticheat/check/api/impl/OffsetHandlerCheck.java` |
| `ac/navro/proxy/check/api/impl/PacketCheck.java` | `ac/navro/anticheat/check/api/impl/PacketCheck.java` |
| `ac/navro/proxy/check/api/impl/PingBasedCheck.java` | `ac/navro/anticheat/check/api/impl/PingBasedCheck.java` |
| `ac/navro/proxy/check/impl/badpackets/BadPacketA.java` | `ac/navro/anticheat/check/impl/badpackets/BadPacketA.java` |
| `ac/navro/proxy/check/impl/badpackets/BadPacketB.java` | `ac/navro/anticheat/check/impl/badpackets/BadPacketB.java` |
| `ac/navro/proxy/check/impl/badpackets/BadPacketsC.java` | `ac/navro/anticheat/check/impl/badpackets/BadPacketsC.java` |
| `ac/navro/proxy/check/impl/badpackets/BadPacketsD.java` | `ac/navro/anticheat/check/impl/badpackets/BadPacketsD.java` |
| `ac/navro/proxy/check/impl/prediction/AntiKB.java` | `ac/navro/anticheat/check/impl/prediction/AntiKB.java` |
| `ac/navro/proxy/check/impl/prediction/Collisions.java` | `ac/navro/anticheat/check/impl/prediction/Collisions.java` |
| `ac/navro/proxy/check/impl/prediction/GroundSpoof.java` | `ac/navro/anticheat/check/impl/prediction/GroundSpoof.java` |
| `ac/navro/proxy/check/impl/prediction/Horizonal.java` | `ac/navro/anticheat/check/impl/prediction/Horizonal.java` |
| `ac/navro/proxy/check/impl/prediction/NoSlow.java` | `ac/navro/anticheat/check/impl/prediction/NoSlow.java` |
| `ac/navro/proxy/check/impl/prediction/Phase.java` | `ac/navro/anticheat/check/impl/prediction/Phase.java` |
| `ac/navro/proxy/check/impl/prediction/Prediction.java` | `ac/navro/anticheat/check/impl/prediction/Prediction.java` |
| `ac/navro/proxy/check/impl/prediction/PredictionSubCheck.java` | `ac/navro/anticheat/check/impl/prediction/PredictionSubCheck.java` |
| `ac/navro/proxy/check/impl/prediction/Vertical.java` | `ac/navro/anticheat/check/impl/prediction/Vertical.java` |
| `ac/navro/proxy/check/impl/timer/NegativeTimer.java` | `ac/navro/anticheat/check/impl/timer/NegativeTimer.java` |
| `ac/navro/proxy/check/impl/timer/Timer.java` | `ac/navro/anticheat/check/impl/timer/Timer.java` |
| `ac/navro/proxy/collision/Collider.java` | `ac/navro/anticheat/collision/Collider.java` |
| `ac/navro/proxy/collision/CollisionPhysicsState.java` | `ac/navro/anticheat/collision/CollisionPhysicsState.java` |
| `ac/navro/proxy/collision/CollisionShapeEntry.java` | `ac/navro/anticheat/collision/CollisionShapeEntry.java` |
| `ac/navro/proxy/collision/EntityCollisionPhysics.java` | `ac/navro/anticheat/collision/EntityCollisionPhysics.java` |
| `ac/navro/proxy/collision/MoveTowardsClosestSpace.java` | `ac/navro/anticheat/collision/MoveTowardsClosestSpace.java` |
| `ac/navro/proxy/collision/PlantCollision.java` | `ac/navro/anticheat/collision/PlantCollision.java` |
| `ac/navro/proxy/collision/ScaffoldingCollision.java` | `ac/navro/anticheat/collision/ScaffoldingCollision.java` |
| `ac/navro/proxy/collision/ShulkerPushPhysics.java` | `ac/navro/anticheat/collision/ShulkerPushPhysics.java` |
| `ac/navro/proxy/collision/block/PistonArmBlock.java` | `ac/navro/anticheat/collision/block/PistonArmBlock.java` |
| `ac/navro/proxy/collision/block/PistonBlock.java` | `ac/navro/anticheat/collision/block/PistonBlock.java` |
| `ac/navro/proxy/collision/block/ShulkerCollisionState.java` | `ac/navro/anticheat/collision/block/ShulkerCollisionState.java` |
| `ac/navro/proxy/compensated/CompensatedInventory.java` | `ac/navro/anticheat/compensated/CompensatedInventory.java` |
| `ac/navro/proxy/compensated/cache/container/ContainerCache.java` | `ac/navro/anticheat/compensated/cache/container/ContainerCache.java` |
| `ac/navro/proxy/compensated/cache/container/impl/ArmorContainerCache.java` | `ac/navro/anticheat/compensated/cache/container/impl/ArmorContainerCache.java` |
| `ac/navro/proxy/compensated/cache/container/impl/PlayerContainerCache.java` | `ac/navro/anticheat/compensated/cache/container/impl/PlayerContainerCache.java` |
| `ac/navro/proxy/compensated/cache/container/impl/TradeContainerCache.java` | `ac/navro/anticheat/compensated/cache/container/impl/TradeContainerCache.java` |
| `ac/navro/proxy/compensated/cache/entity/EntityCache.java` | `ac/navro/anticheat/compensated/cache/entity/EntityCache.java` |
| `ac/navro/proxy/compensated/cache/entity/state/CachedEntityState.java` | `ac/navro/anticheat/compensated/cache/entity/state/CachedEntityState.java` |
| `ac/navro/proxy/compensated/world/CompensatedWorldImpl.java` | `ac/navro/anticheat/compensated/world/CompensatedWorldImpl.java` |
| `ac/navro/proxy/compensated/world/base/BucketPredictionTracker.java` | `ac/navro/anticheat/compensated/world/base/BucketPredictionTracker.java` |
| `ac/navro/proxy/compensated/world/base/CompensatedWorld.java` | `ac/navro/anticheat/compensated/world/base/CompensatedWorld.java` |
| `ac/navro/proxy/data/BreakingData.java` | `ac/navro/anticheat/data/BreakingData.java` |
| `ac/navro/proxy/data/FluidState.java` | `ac/navro/anticheat/data/FluidState.java` |
| `ac/navro/proxy/data/ItemUseTracker.java` | `ac/navro/anticheat/data/ItemUseTracker.java` |
| `ac/navro/proxy/data/block/NavroBlockState.java` | `ac/navro/anticheat/data/block/NavroBlockState.java` |
| `ac/navro/proxy/data/block/NetworkBlockState.java` | `ac/navro/anticheat/data/block/NetworkBlockState.java` |
| `ac/navro/proxy/data/block/NetworkBlockStateRegistry.java` | `ac/navro/anticheat/data/block/NetworkBlockStateRegistry.java` |
| `ac/navro/proxy/data/input/PredictionData.java` | `ac/navro/anticheat/data/input/PredictionData.java` |
| `ac/navro/proxy/data/inventory/BundleData.java` | `ac/navro/anticheat/data/inventory/BundleData.java` |
| `ac/navro/proxy/data/inventory/ItemCache.java` | `ac/navro/anticheat/data/inventory/ItemCache.java` |
| `ac/navro/proxy/listener/PacketListener.java` | `ac/navro/anticheat/listener/PacketListener.java` |
| `ac/navro/proxy/packets/input/AuthInputPackets.java` | `ac/navro/anticheat/packets/input/AuthInputPackets.java` |
| `ac/navro/proxy/packets/input/PostAuthInputPackets.java` | `ac/navro/anticheat/packets/input/PostAuthInputPackets.java` |
| `ac/navro/proxy/packets/input/legacy/InputPositionDecoder.java` | `ac/navro/anticheat/packets/input/legacy/InputPositionDecoder.java` |
| `ac/navro/proxy/packets/input/legacy/LegacyAuthInputPackets.java` | `ac/navro/anticheat/packets/input/legacy/LegacyAuthInputPackets.java` |
| `ac/navro/proxy/packets/other/NetworkLatencyPackets.java` | `ac/navro/anticheat/packets/other/NetworkLatencyPackets.java` |
| `ac/navro/proxy/packets/other/VehiclePackets.java` | `ac/navro/anticheat/packets/other/VehiclePackets.java` |
| `ac/navro/proxy/packets/player/PlayerInventoryPackets.java` | `ac/navro/anticheat/packets/player/PlayerInventoryPackets.java` |
| `ac/navro/proxy/packets/player/PlayerEffectPackets.java` | `ac/navro/anticheat/packets/player/PlayerEffectPackets.java` |
| `ac/navro/proxy/packets/player/PlayerVelocityPackets.java` | `ac/navro/anticheat/packets/player/PlayerVelocityPackets.java` |
| `ac/navro/proxy/packets/server/ServerChunkPackets.java` | `ac/navro/anticheat/packets/server/ServerChunkPackets.java` |
| `ac/navro/proxy/packets/server/ServerDataPackets.java` | `ac/navro/anticheat/packets/server/ServerDataPackets.java` |
| `ac/navro/proxy/packets/server/ServerEntityPackets.java` | `ac/navro/anticheat/packets/server/ServerEntityPackets.java` |
| `ac/navro/proxy/player/data/tracker/FlagTracker.java` | `ac/navro/anticheat/player/data/tracker/FlagTracker.java` |
| `ac/navro/proxy/player/data/VehicleData.java` | `ac/navro/anticheat/player/data/VehicleData.java` |
| `ac/navro/proxy/prediction/ClimbableBlockPhysics.java` | `ac/navro/anticheat/prediction/ClimbableBlockPhysics.java` |
| `ac/navro/proxy/prediction/GlidingState.java` | `ac/navro/anticheat/prediction/GlidingState.java` |
| `ac/navro/proxy/prediction/GroundTravelPhysics.java` | `ac/navro/anticheat/prediction/GroundTravelPhysics.java` |
| `ac/navro/proxy/prediction/PredictionRunner.java` | `ac/navro/anticheat/prediction/PredictionRunner.java` |
| `ac/navro/proxy/prediction/RiptidePhysics.java` | `ac/navro/anticheat/prediction/RiptidePhysics.java` |
| `ac/navro/proxy/prediction/SneakInputPhysics.java` | `ac/navro/anticheat/prediction/SneakInputPhysics.java` |
| `ac/navro/proxy/prediction/SprintJumpPhysics.java` | `ac/navro/anticheat/prediction/SprintJumpPhysics.java` |
| `ac/navro/proxy/prediction/StickyBlockPhysics.java` | `ac/navro/anticheat/prediction/StickyBlockPhysics.java` |
| `ac/navro/proxy/prediction/engine/base/PredictionEngine.java` | `ac/navro/anticheat/prediction/engine/base/PredictionEngine.java` |
| `ac/navro/proxy/prediction/engine/data/Vector.java` | `ac/navro/anticheat/prediction/engine/data/Vector.java` |
| `ac/navro/proxy/prediction/engine/data/VectorType.java` | `ac/navro/anticheat/prediction/engine/data/VectorType.java` |
| `ac/navro/proxy/prediction/engine/impl/GlidingPredictionEngine.java` | `ac/navro/anticheat/prediction/engine/impl/GlidingPredictionEngine.java` |
| `ac/navro/proxy/prediction/engine/impl/GroundAndAirPredictionEngine.java` | `ac/navro/anticheat/prediction/engine/impl/GroundAndAirPredictionEngine.java` |
| `ac/navro/proxy/prediction/engine/impl/fluid/LavaPredictionEngine.java` | `ac/navro/anticheat/prediction/engine/impl/fluid/LavaPredictionEngine.java` |
| `ac/navro/proxy/prediction/engine/impl/fluid/WaterPredictionEngine.java` | `ac/navro/anticheat/prediction/engine/impl/fluid/WaterPredictionEngine.java` |
| `ac/navro/proxy/prediction/fluid/FluidMovement.java` | `ac/navro/anticheat/prediction/fluid/FluidMovement.java` |
| `ac/navro/proxy/prediction/fluid/FluidPredictionState.java` | `ac/navro/anticheat/prediction/fluid/FluidPredictionState.java` |
| `ac/navro/proxy/prediction/fluid/LiquidPhysics.java` | `ac/navro/anticheat/prediction/fluid/LiquidPhysics.java` |
| `ac/navro/proxy/prediction/ticker/base/EntityTicker.java` | `ac/navro/anticheat/prediction/ticker/base/EntityTicker.java` |
| `ac/navro/proxy/prediction/ticker/impl/LivingTicker.java` | `ac/navro/anticheat/prediction/ticker/impl/LivingTicker.java` |
| `ac/navro/proxy/prediction/ticker/impl/PlayerTicker.java` | `ac/navro/anticheat/prediction/ticker/impl/PlayerTicker.java` |
| `ac/navro/proxy/teleport/TeleportUtil.java` | `ac/navro/anticheat/teleport/TeleportUtil.java` |
| `ac/navro/proxy/teleport/data/CorrectionResetData.java` | `ac/navro/anticheat/teleport/data/CorrectionResetData.java` |
| `ac/navro/proxy/teleport/data/RewindHistory.java` | `ac/navro/anticheat/teleport/data/RewindHistory.java` |
| `ac/navro/proxy/teleport/data/TeleportData.java` | `ac/navro/anticheat/teleport/data/TeleportData.java` |
| `ac/navro/proxy/util/EntityBatchEncoder.java` | `ac/navro/anticheat/util/EntityBatchEncoder.java` |
| `ac/navro/proxy/util/EntityMetadataUtil.java` | `ac/navro/anticheat/util/EntityMetadataUtil.java` |
| `ac/navro/proxy/util/ItemUtil.java` | `ac/navro/anticheat/util/ItemUtil.java` |
| `ac/navro/proxy/util/LatencyUtil.java` | `ac/navro/anticheat/util/LatencyUtil.java` |
| `ac/navro/proxy/util/StringUtil.java` | `ac/navro/anticheat/util/StringUtil.java` |
| `ac/navro/proxy/util/block/BlockActions.java` | `ac/navro/anticheat/util/block/BlockActions.java` |
| `ac/navro/proxy/util/block/BlockPlacementPhysics.java` | `ac/navro/anticheat/util/block/BlockPlacementPhysics.java` |
| `ac/navro/proxy/util/block/BlockUtil.java` | `ac/navro/anticheat/util/block/BlockUtil.java` |
| `ac/navro/proxy/util/reach/PositionInterpolator.java` | `ac/navro/anticheat/util/reach/PositionInterpolator.java` |
| `ac/navro/proxy/validator/blockbreak/ServerBreakBlockValidator.java` | `ac/navro/anticheat/validator/blockbreak/ServerBreakBlockValidator.java` |
| `ac/navro/proxy/validator/inventory/ItemTransactionValidator.java` | `ac/navro/anticheat/validator/inventory/ItemTransactionValidator.java` |

后续新增：`ac/navro/proxy/data/input/TickData.java` 对应 Navro 的 `data/input/TickData.java`，使用 WireTickSnapshot 承载已有协议状态及独立的物品使用 flags；原生数字标志不作猜测映射。输入收尾及历史语义见第二十五轮审计。

第二十六轮：`state/MovementMetadataAdapter.java` 是代理协议适配器，将 WirePlayerState 的物理姿态与补偿库存绑定到既有 `state/MovementMetadata.java` 原版 metadata 阶段。原版 flags 仍归 `player/data/tracker/FlagTracker.java`；Wire、物品使用与预测共用一个实例。Wire 的姿态字段统一归 `prediction/fluid/FluidPoseState.java`，执行统一归 `FluidMovement.java`，不再维护第二套姿态算法。`WireTickSnapshot` 保存同一 native-bit flags（包括内部物品位）及游泳起始姿态；不改变原版类名或复制服务器核心职责。

第二十八轮：效果包接收、目标过滤、时长归一化及所属 ACK 的提交归 `packets/player/PlayerEffectPackets.java`。`data/vanilla/NativeStatusEffects.java` 只查询从固定原生核心 `Effect.getEffect` 导出的可用 ID，不重建药水机制；`WirePlayerState.addEffect/removeEffect` 对应原版 PlayerData 的状态入口。原版 StatusEffect 的计时与快照仍使用现有同职责类。

第二十九轮：骑乘更新归 packets/other/VehiclePackets，状态归 player/data/VehicleData。ServerEntityPackets 和 SessionState 共同承接原版补偿实体缓存及其 uniqueIdToRuntimeId 索引；预测上下文、碰撞采样和眼高直接读取同一 vehicleData。旧 EntityLinkState 只是协议字段值容器，已不参与骑乘决策。

第三十轮：util/block/BlockReachUtil 对应原版同路径同名职责；data/inventory/NativeItemDecoder.placement 将已有原生工厂身份直接接入 NativeItemPlacementProperties，供原版 handleBlockPlacement 提交阶段使用。399 个额外构造器来源为固定原生核心离线导出，非代理重写的放置机制。

第三十一轮：PlayerInventoryPackets.onTransactionReceive 对应原版库存监听器的事务结果/异常边界，SessionState.receiveClient 消费拒绝结果以决定真实转发。原校验计算仍归 ItemTransactionValidator；PredictionItemUse 只提供已迁入的物品使用上下文，不再保留一个丢弃 boolean 的重复事务入口。
