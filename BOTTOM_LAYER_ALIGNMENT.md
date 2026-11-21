# 底层移植对齐记录

基准源码：`../Navro/src/main/java/ac/navro/anticheat/`。前半部分记录第一轮推动阶段，第二轮基础修正见文末。

## 已补齐

- `collision/MoveTowardsClosestSpace`：保持请求消耗顺序、HAS_COLLISION 与已追踪骑乘状态门控、仅方块的水平扩展查询、相交过滤、方向选择、反向修正、速度限制以及全部浮点计算顺序。
- `collision/ShulkerPushPhysics`：保持输入帧的盒体与开放程度时序、六方向与无效方向回退、自身盒体排除、阻挡遍历修正顺序、潜行边缘处理及原碰撞求解链。
- 潜影盒后置阶段提交实际位置与 AABB、afterCollision、方块推力、水平/垂直碰撞及落地状态，保留先前选定移动的查询与碰撞列表。
- `WirePlayerState` / `WireTickSnapshot`：保存并恢复独立的 afterCollision 向量，在接受传送时清零；物理提交支持明确的已追踪骑乘状态，避免由客户端 predictedVehicle 代替。

## 验证

- 原版源码离线执行生成 3000 组最近空隙计算和 3000 组潜影盒计算参考，逐浮点原始位比较。
- 六个方向的推动后置阶段，通过原版 Collider / SweptMovement 求解结果验证 AABB、实际位移、碰撞标志、落地状态及穿透记录。
- 生命周期回归覆盖元数据确认、请求消耗、骑乘/豁免门控、开合时序、顶部阻挡、移动查询保留、传送重置、历史帧重放与实时潜影盒移除。
- 全量 132 项测试通过，无失败、错误或跳过；installDist 构建通过。
- 计算主体与辅助函数在 BlockFace → Direction 类型适配后与原版文本相同。
- 本轮未修改已有 Java 字符串字面量或生产资源；告警、命令和格式化模块未改动。

修正了两处既有测试问题：当前协议依赖的普通命令权限使用 `CommandPermission.ANY`；碰撞测试的元数据辅助方法等待相应确认后再验证标志。生产 ACK 逻辑未改动。

离线参考生成命令见 README。参考源码哈希见 `tools/navro-block-push-reference-sha256.json`，Nukkit 仅用于离线生成参考，不进入代理生产依赖。

## 尚未完成

本轮补齐的是显式物理阶段，没有将其提前挂到观察输入路径：后置推动必须等待完整预测调度器先选定并执行玩家移动。完整 PredictionRunner、世界/流体及方块效果的历史回放、骑乘生命周期、库存事务和纠正包传输仍待继续对齐。检测分发继续保持停用。


## 第二轮：方块编码、世界写入、植物碰撞和背包 NBT

优先补挖掘、放置和物品事务依赖的底层，不提前启用检测。

- 修复原有代理以 6 位编码原生 legacyFullId 的问题。原版附带 MOT 实际使用 `DATA_BITS=13`、`DATA_MASK=8191`，现在保留连接、转角等高位数据。Geyser 碰撞快照自身仍按原版独立的 6 位键格式查询。
- 原参考生成器也硬编码了 6 位，导致高位错误被测试数据掩盖；现改为读取原版 `Block.DATA_BITS` 并重新生成全部 178150 条反向参考。
- 新增原版 BlockPalette 正向映射，保留延后注册 stateOverload、runtime 覆盖与 hash 保留首次值、掩码重试、data=0、INFO_UPDATE 的原顺序。196761 个原生输入在两种模式下全部相同。
- 补偿世界新增 predictLocalBlock、predictBucketBlock、updateLegacyBlock、id/data 更新接口，并保留原有分层、区块/高度边界和 ACK 修订顺序。修复缓存区块范围较大时未按当前世界高度返回空气的问题。
- 新增原版 BlockPlacementPhysics 与面坐标规则，保留先替换点击方块、否则使用相邻面的规则。replaceability 和放置默认状态由调用方明确提供，不从名称或碰撞形状猜测；尚未挂到不完整的交易入口。
- 竹子和滴水石已经接入实际世界碰撞查询。保留原坐标种子、32/64 位溢出、xoroshiro、随机数消耗顺序、浮点量化以及全部形状分支；8192 个原生元数据输入（含非法属性报错）与 4000 组本地/世界形状逐位对照通过。原录制的四组接触也通过实际世界查询验证。
- 补回已加载区块中的 minY-41 人工碰撞层，保持对应方块仍为空气的语义。
- 统一原生 NBT 数值读取：MOT 的 getShort 实际返回 Number.intValue，不收窄到 16 位；非数值标签保留报错。修复束口袋 ID、附魔字段、潜影盒 facing 与注册字段的读取，并验证物品缓存更新仍等待对应批次 ACK。

验证：全量 146 项测试通过，无失败、错误或跳过；test + installDist 构建通过。原告警、命令、格式化模块以及全部生产资源未修改。

本轮备份和修改前哈希位于开发主目录中 `navro-proxy-foundation-current.txt` 指向的目录。原生参考来源与哈希见 `tools/foundation-reference-sha256.json`，重生成命令见 README。

仍待继续：完整挖掘校验与放置/使用物品事务入口、原生替换性/物品默认状态的完整适配、原版以服务端 tick 计时的放置支撑历史、物品使用状态机、现代背包 count-only 响应的完整身份/NBT 重建及 native enchantment safety policy。没有用 AuthInput tick 代替服务端 tick，也没有把客户端事务请求直接视为成功提交。检测分发继续停用。


## 第三轮：活塞动态碰撞、挖掘动作序列及文件名

- 新增与 Navro 同名的 `collision/block/PistonBlock.java`、`PistonArmBlock.java`，已经接入补偿世界的真实碰撞查询。普通/粘性底座保持原版完整立方体；移动方块占位不产生碰撞。
- 活塞头保留原版方向适配及原生 metadata 回退，按朝向反向查找底座的补偿 NBT。State=0 返回空形状；State=1 读取 LastProgress；State=3 读取 Progress；缺字段、其他状态和非有限进度值保留静态形状。保留原版进度夹取、伸缩杆阈值、部件顺序及全部浮点运算，没有合并重叠盒。
- 方块实体数据继续等待实际出站批次的 ACK，并在队列中冻结 NBT。测试覆盖数据包后续修改、分批确认、区块替换清空旧 NBT，以及活塞臂伸到头方块之外时的查询。
- 输入内的历史查询保留缓存形状；历史中未缓存的新查询仍读取当前 NBT，与原版现有语义相同。没有把它宣称为完整世界回放。
- `ProtocolSneakEdgePhysics.java` 改为原版文件名 `SneakEdgePhysics.java`，直接采用 Navro 原源码，已同步所有调用引用。
- 新增原版文件名的 `util/block/BlockActions.java`。Cloudburst 的动作列表直接保留线序和重复动作；旧 Map 重载保留原版瞬间挖掘的 start/finish 规则、旧操作 finish/abort 顺序及不匹配条目过滤。读写边界保留动作副本，过滤后的实际序列可直接转发。
- `NbtNumber` 补齐原版 getByte/getFloat 语义，State 不被额外截成 8 位，进度读取保留原生数值转换和异常行为。

验证：原版离线参考覆盖 8192 组普通/粘性活塞头 metadata、2600 组方向属性、3360 组几何及异常输入、4 类底座类型；几何与世界偏移逐浮点位一致。挖掘动作参考覆盖 480 组旧 Map 和 240 组有序读/过滤/写回/副本隔离序列。重新编译当前原版 DynamicCollision 与 Box 后仍得到相同参考数据。

全量 154 项测试通过，无失败、错误或跳过；test + installDist 构建通过。新增和改名后的生产类均已打入分发产物；旧 ProtocolSneakEdgePhysics 类和离线 Nukkit/参考生成器不进入新产物。原有显示字符串、颜色格式模块及全部生产资源保持不变。

参考来源哈希见 `tools/piston-reference-sha256.json`，生成命令见 README。修改前源码和哈希备份由主目录的 `navro-proxy-piston-current.txt` 指向。

后续仍需继续移植完整挖掘校验、放置/使用物品事务、物品使用状态机及其依赖。BlockActions 是底层序列接口，尚未挂入不完整的挖掘校验；没有开启挖掘拦截、预测方块销毁或检测分发。

## 第四轮：直接移植 Nukkit 硬度表及原版可破坏性

硬度属于可以直接移植的原生数据，现已从 Navro 附带的 `Nukkit-MOT-SNAPSHOT.jar` 导出，不再作为待调用方提供的依赖。

- 新增 `data/block/NativeBlockProperties.java`。同时保存原生 `Block.hardness[id]` 静态数组和 Navro 实际使用的方块实例 `getHardness()` 结果；没有把两者混用。
- 导出过程逐一执行原版补偿世界使用的 positional `Block.get` 重载，覆盖 2048 个 ID × 8192 个 metadata，共 16777216 组输入。按原值和原始 double 位保留结果；只对连续相同记录做无损区间存储，压缩资源为 52849 字节。
- 保留无效 metadata 回退成 `BlockUnknown` 的语义。例如草方块 metadata=0 硬度为 0.6，metadata=1 则为 10；传送门也有类似的合法状态/回退差异。未注册 ID 的静态数组值为 0，但其实例硬度为 10。2048～9999 的未注册 ID 同样保留原生 Unknown 回退；服务端注册的自定义类不套用 vanilla 数据。
- 同一次原生导出还保存实际方块 ID、`instanceof BlockItemFrame`、`instanceof BlockDoor` 和 `door.isTop()`，为后续挖掘状态机提供精确的类语义，不从 identifier 推测原生类型。
- `BlockUtil.determineCanBreak` 保留原版空气/水/岩浆的无条件排除、double 转 float 和仅 `GameType.CREATIVE` 例外。`GameType.java` 的名称、顺序和 from 下标查询直接复制原生实现。游戏模式仍由实际状态提供，没有由能力标志猜测。
- `CompensatedWorld.getNativeBlockProperties` 已读取实际连接调色板与补偿缓存。验证覆盖 runtime/hash 两种模式、多个协议、ACK 到达前后、较旧 ACK 被局部预测抑制、独立图层以及卸载区块。

验证：独立参考生成器调用当前原版 `BlockUtil.determineCanBreak` 和原生 `Block.get`，对上述全部输入、七种游戏模式以及 null 模式生成每个 ID 的校验和。代理逐项计算并比较，全部一致；另覆盖 30 组未注册 ID 和 10 组硬度浮点边界，保留转换到 float 后的 -1 判断及 NaN 行为。

全量 160 项测试通过，无失败、错误或跳过；test + installDist 构建通过。原有生产字符串仍完整保留，已有生产资源未修改；新增原生属性资源已进入分发，离线生成器和 Nukkit 不进入生产依赖。

可读硬度表：`tools/native-block-hardness.tsv`。参考来源及产物哈希：`tools/hardness-reference-sha256.json`。重生成命令见 README。修改前备份由主目录 `navro-proxy-hardness-current.txt` 指向。

本轮完成硬度与原生类型数据层及可破坏性规则，完整 ServerBreakBlockValidator、放置/使用物品事务、服务端 tick 支撑历史和权威纠正发包仍需继续接入。挖掘拦截与检测分发保持原先状态。

## 第五轮：完整挖掘状态机、销毁预测与放置支撑历史

- 新增与原版同名的 `validator/blockbreak/ServerBreakBlockValidator.java` 和 `data/BreakingData.java`，保留完整状态机主体；原生方块属性与硬度直接使用第四轮数据层。位置、实际游戏模式和权威纠正回调通过明确的 Context 适配。
- 保留取消先于面、距离和方块检查；仅取消匹配操作，不恢复已销毁方块。保留原整数坐标取 floor、long 距离溢出行为、144 阈值、物品展示框旁路、原始面检查与合法动作白名单。
- 重复完成仍依赖当包已接受位置或当前空气对应的销毁预测身份，不增加任意 tick 窗口。新 START 只废弃自身位置的旧完成身份；切换目标恢复旧目标；世界 epoch 改变即清空操作，即使输入没有方块动作标志。
- 门保持原生 BlockDoor 上半部信息优先、identifier 后缀回退和原版同门判定；另一半仅在对应完成动作实际保留并转发后预测为空气。旧 Map 过滤掉较早的同类型动作时，原版恢复主方块和另一半的分支仍保留。
- 原版当前将 `breakingProcess` 直接设为 1F，本轮照搬，没有另造硬度/工具耗时。硬度只用于原版可破坏性判断。
- 补偿世界补齐 `predictBlockBreak`、`isPredictedBlockBreak`、`beginBlockBreak`。销毁使用现有修订追踪器与真实缓存；当前 ACK 确认/拒绝，旧 ACK 和旧整区块快照保留更新的预测。替换、其他局部操作、区块卸载/保留、世界重置和维度切换按原版清理身份。
- 放置支撑历史已移植：销毁预测和 layer=0 的有效 ACK 更新前记录旧实体方块，排除空气/液体和 cave_air/void_air；保持原来的“两服务端 tick 内”、每 tick 清理及有符号 int 减法溢出。构造器明确接受原生服务端时钟。默认无上下文的世界对应原版 player=null 分支，不记录历史；没有使用 AuthInput tick 或墙钟代替。
- `BlockActionText` 和原生同名 `PlayerActionType` 保留全部原调试字符串、枚举别名、BlockVector3/Vector3f 及 Lombok BreakingData 的原文本格式。动作读入保留原校验器需要处理的 malformed null，写入仍拒绝 null，且失败前不修改包。

验证：原版离线执行 384 个连续会话、3072 个输入包，逐包比较动作与标志、活动操作、状态查询顺序、begin/predict/restore 顺序、调试文本、修订/epoch、缓存及预测身份。覆盖有序包、过滤后回退 Map 的包和纯 Map 包。另有 2560 步原生服务端 tick / 支撑历史参考，覆盖两种协议和 runtime/hash 模式、历史原值和清理 tick、溢出、ACK、图层、缓存和维度生命周期。两份参考已使用重新编译的当前原版补偿世界与追踪器生成/复核；同时覆盖原生未设置坐标与旋转的默认包。

此外通过真实连接调色板与默认 Context 验证原生硬度、仅创造模式例外、门两半、连续目标、取消先行、负坐标 floor；通过实际延迟确认队列验证销毁 pending/confirmed、整区块确认/纠正及新操作失效。

完整状态机属于已移植的底层阶段，尚未挂入实时观察入口：权威纠正包传输、实际游戏模式同步、服务端时钟来源和完整预测调度仍待接入。放置与使用物品事务的整条入口也仍待继续对齐；本轮没有启用实时挖掘拦截或检测分发。

全量 172 项测试通过，无失败、错误或跳过；test + installDist 构建通过。现有生产资源和原有字符串保持不变，状态机与辅助类已进入分发；Nukkit 和离线参考生成器不进入代理生产依赖。

参考来源哈希见 `tools/digging-reference-sha256.json`，重生成命令见 README。修改前源码与哈希由主目录 `navro-proxy-digging-current.txt` 指向。

## 第六轮：代理自行构造纠正包并经真实会话发送

代理能自行构造并发送客户端数据包；现有握手、文本告警和 ACK 标记本来已在这样发送。本轮已补齐方块纠正的构造、真实会话队列与补偿 ACK 通道。

- `ProxySession.restoreCorrectBlock(position, layer0NetworkId, layer1NetworkId)` 发送原版 layer=0、layer=1 两个更新；单网络 ID 重载保持原版显式状态重载只纠正 layer=0 的行为。网络 ID 由调用方明确提供，可保留负数 hash ID，不从局部预测中推导，也不把缺失后端状态填为空气。
- `BlockUtil.restoreCorrectBlock` 保留空调用方/坐标/状态门控、每层独立新包和顺序。发现 Navro/MOT 的 `FLAG_ALL_PRIORITY` 为 11，而当前 SDK 的同名常量为 19；现按原版位值 11 编码，使用 SDK bit=3 的 UNUSED 槽位，不把 SDK bit=4 的 PRIORITY 当作等价替代。
- 新增 `BlockCorrectionSender`，在客户端 event loop 上经过 `BedrockServerSession.sendPacket` 的真实 FIFO。新包创建新 wrapper，经协商好的 codec、压缩和加密通道发送，不进入原始包字节直通路径。`ProtocolDefinitions.blockDefinition` 保留连接内规范 token 与原网络 ID。
- 代理构造的更新在发包前绑定到实际包身份，再由现有批次编码器挂到对应 closing ACK；发送准备时不提前更新补偿世界。较早原生 ACK 不提交后面的纠正，后续局部预测仍按原版修订顺序抑制旧确认，压缩失败不发布确认或更新世界。
- 缺少 ACK 传输时不走立即执行的队列回退；会话不可用时不排包；准备或排队抛出异常时移除该包的暂存任务。代理纠正以 PROXY 来源记录，不计作新的后端地形包。

验证：离线调用当前原版 BlockUtil 及 Navro 附带 MOT 的 UpdateBlockPacket 编码，生成 4096 组完整字节参考，覆盖 16 种支持/别名协议、两种重载、空值门控、完整 int 范围坐标及 runtime/hash ID。代理编码结果逐字节一致。真实会话 FIFO 与编解码管线测试验证前序原始字节保持、新包独立编码、两层顺序、对应批次 ACK、后续预测、不可用会话、缺失传输、观察回调异常和压缩失败。

本轮已完成纠正发包能力，调用方可以提供正确后端 ID 并直接使用。获取原生服务器当前方块状态、实际游戏模式、服务端时钟与完整预测调度仍有独立接线工作；不会把后端已发送快照宣称为原生 Level.getBlock 的完全等价来源。没有因此提前启用挖掘拦截或检测分发。测试使用内存中的真实协议管线，没有连接实机客户端进行联调。

全量 178 项测试通过，无失败、错误或跳过；test + installDist 构建通过。已有生产资源、显示文本与颜色排版保持不变；新发包通道已进入分发，Nukkit 和离线生成器不进入生产依赖。

参考来源与产物哈希见 `tools/correction-reference-sha256.json`，生成命令见 README。修改前源码与哈希由主目录 `navro-proxy-corrections-current.txt` 指向。

## 第七轮：原生放置可替换性与游戏模式包同步

- 新增 `NativePlacementProperties`，直接执行 Navro 附带 Nukkit 的 positional Block.get 和 canBeReplaced，导出 2048 个 ID × 8192 个 metadata 的原值。保留雪层、双层植物的 metadata 分支及无效状态回退；不是按名称或碰撞形状判断。6145 个无损区间压缩后为 9072 字节，原硬度表不变。
- `BlockPlacementPhysics.target/predict` 新增接受 NetworkBlockState 的重载，直接查询原生可替换性，再按原版决定覆盖点击方块或使用点击面的邻居。原版 432 组放置参考已改为同时验证自动查询和补偿世界写入，覆盖 runtime/hash、边界坐标与 ACK 修订。物品产生的 placedLegacyFullId 仍需由完整原生物品适配提供。
- 补入 WirePlayerState 的 gameType 缓存（原 PlayerData 默认 DEFAULT），并在现有 ServerDataPackets 路径接入 StartGame、UpdatePlayerGameType、SetPlayerGameType 的 ACK 更新。保留 Update 的 runtime entity ID 与 null 门控，Set 的枚举转换在 ACK 回调中执行，非法下标保留原失败行为。
- 保留原回调在 ACK 时读取包字段的语义；没有改为发送时的模式快照。SDK 未设置的 StartGame 枚举对应原生 primitive int 默认值 0。模式不由飞行能力或客户端请求推导，也不作为随历史移动快照回滚的字段。
- 原有 gameTypeUpdateQueued / gameTypeUpdateAcknowledged 事件名和参数保持原样，通过明确的 GameTypeEventSink 接口保留。完整 NearbyBlockSampler 的 JSON 采集与原生 session 初始化/主动模式刷新仍属于后续适配，不借此伪造服务端私有状态。

验证：16777216 组原生可替换性逐 ID 校验和对照一致，另验证 30 组未注册 ID；原版 432 组目标选择与实际局部写入参考通过。游戏模式参考直接执行当前原版 ServerDataPackets 和 PostSendAcknowledgments，覆盖 128 个连续会话、2304 个包事件，比较任务数量、各回调后的模式状态、目标/null 过滤、取消、包字段变更及非法枚举。真实 codec / 批次 ACK 管线覆盖 StartGame、后续切换、实体过滤、创造模式硬度例外和模式缓存独立于历史移动回放。

后续仍待接齐完整放置/使用物品事务入口、物品默认放置状态的全部原生适配、权威方块来源、原生服务端 tick 来源和完整预测调度；本轮没有开启未完成的检测或挖掘拦截。

全量 184 项测试通过，无失败、错误或跳过；test + installDist 构建通过。原有生产资源和字符串保持不变，新属性表与代码已进入分发；Nukkit 和离线工具不进入生产依赖。

可读表见 `tools/native-block-replaceability.tsv`；来源及产物哈希见 `tools/placement-state-reference-sha256.json`，生成命令见 README。修改前源码和哈希由主目录 `navro-proxy-placement-current.txt` 指向。

## 第八轮：原生物品放置状态与放置提交阶段

- 新增 `NativeItemPlacementProperties`，执行 Navro 附带 Nukkit 的原生物品构造、canBePlaced、getBlockUnsafe/getBlockId 和 Block.get(mappedId, 0)，保存精确的可放置性、映射存在性、映射方块 ID 和默认状态 ID/fullId。
- 数字 Item.get 构造与 Item.fromString(identifier) 后 setDamage 的构造视图分开。覆盖 1135 种已注册数字构造、1815 种命名构造，各自全部 65536 个 metadata 输入，共 193331200 组；2952 个无损区间压缩后为 24366 字节。所有既有硬度和可替换性资源保持原值。
- 保留种子、门、告示牌等原生物品类映射，以及数字 351:3 与命名 brown_dye 的差异；不会按物品名称或携带的 BlockDefinition 猜测映射。查表明确覆盖构造/设置参数 0..65535，未导出的注册实现和范围外值显式报错；完整协议/NBT 物品构造选择与自定义工厂仍需后续接入。
- `ItemTransactionValidator.handleBlockPlacement` 已移植原版提交阶段：映射非空且非空气时，使用原版 Block.get(id, 0) 的默认状态，再执行已有放置计算和补偿写入、旋转放置回调，最后处理库存扣减。没有改为直接使用物品当前方块 metadata。
- 只豁免准确的 CREATIVE 模式；保留其他模式与 null 的原比较、空映射/空气情况下的原扣减分支、未加载/越界预测未写入时仍扣减、计数 int 溢出、耗尽时换为 ItemCache.AIR、预测或回调异常时的部分提交顺序。回调仍收到原事务对象、原目标坐标和原默认方块 ID。

验证：全部原生构造输入按工厂校验和对照；另直接调用当前原版私有 handleBlockPlacement，生成 1680 组库存、世界缓存、修订、缓存别名/AIR 身份、回调顺序和异常对照。通过真实 runtime/hash 调色板验证默认 metadata=0、仅当前手持槽扣减、完整物品 NBT/metadata/定义保留以及世界 ACK 不隐式回滚库存扣减。

本轮完成的是完整 click-block 分发选中放置后调用的提交阶段。完整协议物品构造桥、handleUse/handleClickBlock 的全部前置分支、桶与激活交互、ItemUseTracker/release，以及权威方块/时钟和预测调度仍待继续；未提前挂到不完整的实时事务或检测入口。

全量 190 项测试通过，无失败、错误或跳过；test + installDist 构建通过。所有既有生产资源、原有字符串、颜色与排版保持不变；新属性表及阶段代码已进入分发，Nukkit 和离线工具不进入生产依赖。

可读表见 `tools/native-item-placement.tsv`；来源和产物哈希见 `tools/item-placement-reference-sha256.json`，重生成命令见 README。修改前源码和哈希由主目录 `navro-proxy-item-placement-current.txt` 指向。

## 第九轮：使用物品事务入口与旧式护甲交换

- `ItemTransactionValidator.handle(packet, TransactionContext)` 已补原版事务类型分发；NORMAL 使用已有丢弃预测，ITEM_USE 进入本轮完整入口校验，ITEM_RELEASE 调用必需的原释放阶段接口，其余原版分支返回值保持不变。SDK 未设置的类型对应原生 primitive 默认 NORMAL。
- `handleUse` 保留原版顺序：UseItemData 类型门控、0..8 热键槽检查、实际当前手持槽读取、逐个 action 的槽位与 oldItem 定义校验、空手例外、无 action 时的手持校验及原 REJECT_HELD_ITEM_MISMATCH 事件、坐标校验、距离与动作分发。action 的来源类型、新物品及计数/metadata/NBT 不被添加为额外校验条件。
- 距离使用明确提供的原已接受玩家 Vec3、整数坐标直接转 float 和原平方距离运算；不读事务中的 playerPosition，不加方块中心偏移。完整保留 `x + y + z == 0` 的豁免及 int 溢出、浮点精度边界和 NaN 比较行为，CLICK_AIR 同样经过此校验。
- 明确区分原生 Item.isNull 与 SDK ItemData.isNull：前者也判断原生 AIR ID。新入口及经该入口分发的普通丢弃使用调用方提供的原生物品 ID，禁止把 wire runtime ID 当作 native item ID；定义比较继续要求真实协议原生定义视图。已有无 Context 的普通事务兼容入口保持原样。
- `handleLegacyArmorEquip` 已移植完整原方法。仅在 CLICK_AIR 子阶段返回 true 后运行，检查原 raw packet 的 action 与 legacySlots，使用 payload 的热键槽，只读首条 legacy 数据和首个 unsigned byte，保留 container=6、原 armorContainer 的 36 槽范围和热键/护甲 ItemCache 引用交换。不会克隆替代缓存或丢失 bundle 关联；读取子阶段返回后的当前槽位，保留异常和部分修改顺序。
- SDK 将 UseItemData 合并入包；Context 支持独立 payload 视图及 null，保留原生缺失/错误类型数据的入口返回值，raw actions/legacySlots 不随 payload 视图替换。点击方块、点击空气及释放处理器均为必需接口，没有用默认成功代替未完成阶段。调试事件保留原 token 与原 payload 对象；完整调试格式器仍待接入。

验证：离线重新编译当前原版 ItemTransactionValidator，直接执行原私有 handleUse、handleLegacyArmorEquip 和公共事务入口，生成 6144 组参考；逐组比较接受/拒绝/异常和全部 9 个热键槽、36 个护甲槽的缓存引用身份。覆盖缺失 payload、null action/legacy 数据、越界/unsigned 槽位、正数量 AIR、负数/零数量、错配定义、距离/溢出/浮点边界以及实际交换。子阶段输出单独执行原点击方法导出，只作为入口边界输入，不把该参考算作完整点击处理已移植。生命周期测试另验证来源忽略、原调试 token、原包和 payload 分离、回调返回值、回调内修改/异常、bundle/NBT 保留及普通丢弃预测。

完整协议物品构造桥、handleClickBlock 内桶/激活等全部分支、handleClickAir 的烟花与 ItemUseTracker、release 本体以及权威方块/时钟与预测调度仍待继续；本轮入口没有挂到不完整的实时过滤链。

全量 199 项测试通过，无失败、错误或跳过；test + installDist 构建通过。所有既有生产资源、原有显示文本、颜色与排版保持不变；新增代码进入分发，Nukkit 和离线工具不进入生产依赖。

来源及参考产物哈希见 `tools/item-use-entry-reference-sha256.json`，重生成命令见 README。修改前源码和哈希由主目录 `navro-proxy-use-entry-current.txt` 指向。

## 第十轮：桶事务、门类激活和脚手架提前返回

- 新增 `NativeInteractionProperties`，直接执行 Navro 附带 MOT 的 positional Block.get、isWater、Block.isLava、instanceof 和原 canUseWithoutItem 的精确类名分支。覆盖 2048 个 ID × 8192 个 metadata，共 16777216 组；2048 个无损区间压缩为 219 字节。额外逐状态确认所有实际门、活板门和栅栏门的 getDamage 保留输入 metadata，之后才使用原版位运算。未从名称、碰撞形状或 SDK block state 猜测原生类型；未注册自定义原生工厂仍明确拒绝。
- `ItemTransactionValidator.handleBucketUse` 已移植完整原方法：只有原生 BUCKET ID 325 进入；水/岩浆/细雪桶分别预测相邻 target 的第 0 层，再替换当前手持为原生新空桶。空桶依次检查点击第 0 层水、第 1 层水、岩浆、细雪，取走点击位置的对应图层，使用原版空气 fullId=0；不把第 1 层水判断移到岩浆之后。
- 不增加创造模式豁免、数量递减、液体源头检查或可替换性判断。空桶未匹配液体仍返回 true 而不改库存；其他桶 metadata 返回 false。未加载/越界而未写入世界时仍执行原库存替换，原异常保留预测在先、替换在后的部分提交顺序。
- 原生物品 ID、原生 damage 与 `Item.get(BUCKET, damage, 1)` 通过必需 BucketContext 提供；不把 wire ID/damage 或物品名称当作原生构造桥。新桶使用原工厂的完整物品数据，经过已有 ItemCache.build 替换；保留当前槽位在预测之后、构造之前读取的求值顺序，不复制旧桶的 NBT 或堆叠数量。
- `predictActivatedBlock` / `predictDoorToggle` 已移植完整原方法：活板门 XOR 8，门和栅栏门 XOR 4。点击上半门时读取下方，只在下方也是同 ID 的门时更新下半；不新增上半状态或可交互材质限制。使用原 updateLegacyBlock 直接更新，不改成桶预测，不新增 revision 或额外 ACK 屏障。
- 活板门保留 beforePrediction / afterPrediction 名称、原 before/after fullId、先后顺序和前一回调返回的 trace。完整 NearbyBlockSampler 仍是独立依赖，通过必需 ActivationObserver 接口传递；不伪造采样结果或改变原显示文本。只有实际活板门会调用该接口。
- `canUseWithoutItem` 保留原炉类精确类名及 anvil/barrel/beacon/bed/brewing/button/chest/lever/door/trapdoor/fence gate 的原 instanceof 集合。`isScaffoldingPlacement` 保留原生 Item.getBlockId==420 门控、点击方块优先、空 target 门控和目标查询顺序；不是按 wire 物品 ID 判断。

验证：全部 16777216 组原生属性逐 ID 校验和一致，另有 25 组越界原生 ID 对照。离线重新编译当前原版 ItemTransactionValidator，直接调用私有桶、激活及脚手架方法，生成 8192 组返回值、异常、世界查询/写入顺序、6 个位置/图层读数、预测修订、库存结果、缓存引用和原别名计数参考。参考保存原生工厂实际 fullId，未以输入 metadata 代替原生初始化结果；真实原补偿世界/库存代码参与执行，仅网络调色板为 identity 映射，采样器在该参考中关闭。

真实 runtime/hash 调色板生命周期测试覆盖第 1 层水优先、取水/倒桶位置、不同模式和数量、未加载/越界、完整 NBT 保留或原工厂替换、旧 ACK 与局部预测、门上/下半与不同木种、激活不增加修订、非零采样 trace、回调/构造失败后的部分写入、槽位求值顺序以及脚手架提前返回。

本轮完成的是原点击方块流程中的这些底层方法。完整 handleClickBlock 分发、handleUseItemOn 的特殊交互、钟、AirLiquidPlace/RotationPlace 调度、原生物品协议构造桥、ItemUseTracker/烟花/release，以及权威方块和时钟来源仍待继续对齐；没有因此启用不完整的实时事务过滤。

全量 210 项测试通过，无失败、错误或跳过；test + installDist 构建通过。所有既有生产资源、原有显示文本、颜色与排版保持不变；新属性表与阶段代码进入分发，Nukkit 和离线参考工具不进入代理生产依赖。

可读表见 `tools/native-block-interactions.tsv`，来源及参考产物哈希见 `tools/bucket-activation-reference-sha256.json`，重生成命令见 README。修改前源码与哈希由主目录 `navro-proxy-bucket-activation-current.txt` 指向。

## 第十一轮：特殊方块交互、讲台/Vault NBT 和钟命中判断

- 新增 `NativeUseItemOnProperties`，执行 Navro 附带 MOT 的 positional Block.get 和原生类/属性 getter，导出全部 2048 × 8192 个状态。保留炼药锅、蜡烛与点燃状态、讲台、音符盒、重生锚及 charge<4、TNT、完整蛋糕、甜浆果 damage!=3、钟朝向轴与 attachment，以及 Vault 原生反射结果。告示牌标志直接调用当前原版私有 isSign，保留其 instanceof 和类名 contains("Sign") 分支。47088 个无损区间，原有所有属性表不变。
- `ItemTransactionValidator.handleUseItemOn` 已移植完整原方法，保留空值门控和原顺序：炼药锅与三种桶、蛋糕的蜡烛标签/完整状态、空手熄灭蜡烛、空手且无书的讲台、UP 面音符盒头部乐器标签、南瓜/红石矿、原生映射为荧石且未满的重生锚、告示牌、骨粉甜浆果、打火石/火焰弹 TNT、最后 Vault。保留 FLINT_AND_STEEL 与 FLINT_STEEL 的原重复别名值。
- 原生物品 ID、damage、getBlockId 和 isNull 通过必需 UseItemOnContext 提供；标签使用真实原生协议定义视图。没有按 wire runtime ID、wire metadata 或名称猜测原生构造结果。保留正数量 AIR、命名物品和数字构造的不同结果，以及原生 getter/标签查询的短路顺序；该阶段只判定返回值，不增加世界写入、物品消耗或模式例外。
- `lecternHasBook` 保留原 getBoolean("hasBook") 数值 intValue 语义和 containsCompound("book")，不收窄成 byte，也不把任意存在的 book 都算作书。错误 hasBook 类型在 book 回退之前抛出 ClassCastException，未改为 false 或解析字符串。
- `isVaultActive` 保留原原生反射优先及非空结果直接返回，然后查补偿 NBT 的 vault_state、state；首个非空字符串决定结果，不 trim、不跳过数值字符串、不在首值 inactive 时再采用 fallback active。缺失 key 返回空串，数值标签使用原 String.valueOf，其他错误类型保留强制转换异常。新增 NbtString 对应原 CompoundTag.getString；null 方块按原反射异常分支继续读取 NBT。
- `isProperHit` 保留原钟的轴/attachment 分支、严格 `clickY > 0.8124F` 比较、UP/DOWN 提前返回及 NaN/Infinity 行为。输入面是已解析的原生 BlockFace index 或 null；非法未绑定值明确报错，不把 SDK 的任意整数当作已解析面。

验证：全部 16777216 组属性逐 ID 校验和一致，另对照 25 组范围外原生 ID。离线重新编译当前原版 ItemTransactionValidator，直接执行原 handleUseItemOn、lecternHasBook、isVaultActive、isProperHit，生成 16384 组返回值、异常类型、原生物品 getter/namespace 与 NBT 查询顺序参考。物品包装器仅记录并转发真实 Item.get / Item.fromString 的 getter，不替代原分支；包含固定的全部成功分支及 37 种正确/错误 NBT 组合、null、浮点阈值、NaN 和 Infinity。异常类型一致；JVM 对不同绑定类型生成的 ClassCastException/NPE 说明不作为 Navro 固定显示文本重写。

生命周期验证覆盖 runtime/hash 调色板、全部 GameType、零数量与正数量 AIR、标签与原生 ID/映射的区别、讲台数值转换与错误类型的短路、Vault 首值优先、钟朝向和 attachment、库存/NBT/世界不被判定阶段修改。另通过真实 codec/批次 ACK 管线验证 NBT 只在其对应 ACK 后影响判定，后续未确认标签不提前可见，维度切换清除旧标签。

完整 handleClickBlock 分发、AirLiquidPlace/RotationPlace 与采样调度、原生物品协议构造桥、ItemUseTracker/烟花/release，以及权威方块和时钟来源仍待继续；本轮没有提前启用不完整的实时事务过滤。导出的是附带 MOT 的原生实现，不宣称覆盖未导出的自定义工厂或运行时替换的原生类。

全量 221 项测试通过，无失败、错误或跳过；test + installDist 构建通过。原有生产资源、显示文本、颜色与排版保持不变；新属性表、辅助类和阶段代码进入分发，Nukkit 和离线工具不进入代理生产依赖。

可读表见 `tools/native-block-use-item-on.tsv`，来源及参考产物哈希见 `tools/special-interaction-reference-sha256.json`，重生成命令见 README。修改前源码和哈希由主目录 `navro-proxy-special-interactions-current.txt` 指向。

## 第十二轮：完整点击方块分发与延后查询检测器

- `ItemTransactionValidator.handleClickBlock` 已移植原私有方法的完整分发顺序：坐标 null、实际手持定义、原客户端预测结果、已解析原生面、点击状态、物品展示框提前返回、存在手持时的潜行门控、特殊交互、钟、无物品激活、AirLiquidPlace、脚手架、空气纠正、桶、放置和扣减。点击方块仍由第九轮完整 handleUse 入口调用，生命周期测试已验证两个阶段相接。
- `ClickBlockContext` 明确提供原生物品 getter、可放置性和默认放置视图、原生定义比较、已解析 BlockFace、输入状态、CheckHolder 边界、采样器、补偿世界及权威纠正。没有默认成功的检测器；null holder / null entry 对应原版缺失或停用检测器，存在的 AirLiquidPlace 必须调用其 reject 返回值。完整检测器算法和权限来源仍属于独立适配。
- 保留原默认 primitive 预测值 0：SDK 未设置的预测枚举对应 FAILURE；原生非零值由 Context 原样提供。面必须是原生解析结果，不能把 SDK 未设置的 int=0 当作原生非 null 面，也不在入口猜测原生面绑定。
- 只有实际存在且非 native isNull 的手持才读取潜行状态；空手潜行仍按原交互分支运行。展示框在手持/潜行和检测器之前返回；钟、特殊交互和原 canUseWithoutItem 激活在 AirLiquidPlace 之前返回。未把包的 hotbarSlot 当作实际手持槽，也未把上游距离/槽位判断重复添加进原私有点击方法。
- 新增 `PlacementChecks` 与 `AirLiquidPlaceCheck` 边界，保留传入事务、原 held 快照和点击状态。AirLiquidPlace 拒绝或抛异常后，不继续执行脚手架、空气纠正、桶、预测或扣减；该检测器可自行持有纠正职责，不新增第二次纠正。
- 重构放置提交阶段为预测后执行的 lookup，保留已有显式 PlacementObserver API。完整点击路径在世界预测返回后，重新读取当前 holder 并查询 RotationPlace；没有提前捕获旧 holder 或旧检测器。保留回调内切换实际手持槽、修改模式、抛异常后的原扣减位置和部分提交行为。
- 空气判定保留 minecraft:air / cave_air / void_air 的精确标识和原生 block ID=0。按原版先纠正相邻位置，再纠正点击位置；两次调用完成后才设置 `WirePlayerState.tickSinceBlockResync=5`。任一调用抛异常时不提前写该字段；权威会话不可用而纠正无发送时，仍保留原调用完成后的字段赋值。未用局部预测状态充当权威纠正来源。
- 该计数字段保留原默认值 0，仅移植本阶段赋值；原服务端 tick 推进和完整预测调度仍待接齐，没有按代理收到的包数擅自推进。点击空气、烟花和 release 的实现也仍待继续。

验证：离线重新编译当前原版 ItemTransactionValidator，直接执行完整私有 handleClickBlock，生成 8192 组参考，逐组比较所有查询/检测器 lookup/预测/直接更新/旋转回调/纠正包顺序、返回或异常、9 槽库存、缓存引用/原别名计数、实际手持槽、6 个位置/图层读数、世界修订和 resync 字段。参考包含 1066 次预测、462 次直接更新、402 次旋转回调、38 次回调切槽和 7541 个原生纠正包。

参考使用实际原方法、原生物品构造器及原补偿世界/库存；仅网络调色板为 identity 映射。存在的原生 AirLiquidPlace 在该参考中通过真实 exempt 分支返回 false；取消、检测器异常与回调内状态修改另由明确边界生命周期测试覆盖，不把参考当成完整 AirLiquidPlace 算法已移植。采样器在参考中关闭。

原 ItemCache.AIR 的 AIR_ITEM 哨兵和 SDK ItemData.AIR 的 wire 定义并不等价，故新 Context 的 validate 是必需接口。参考对该哨兵核对 AIR 身份、native ID、metadata、count 和 NBT；不把 SDK canonical wire AIR 的名称/runtime ID 宣称为原生哨兵定义。普通物品及原生新桶的真实协议定义也逐项比较。完整协议物品构造与原生定义桥仍待接入。

生命周期验证覆盖 runtime/hash 调色板、展示框和钟的提前返回、空手潜行、检测器拒绝/异常、预测后替换 holder、回调内切槽与模式变化、部分提交、纠正顺序及 handleUse 上游门控。另将本分发接入真实 BlockCorrectionSender / codec / 批次 ACK 测试，验证相邻两层先于点击两层、ACK 前不提交世界、后续局部预测保护和库存不随纠正 ACK 变化。

全量 231 项测试通过，无失败、错误或跳过；test + installDist 构建通过。全部既有生产资源、显示文本、颜色与排版保持不变；新代码已进入分发，Nukkit 和离线参考工具不进入代理生产依赖。实时过滤仍等待完整原生定义/物品桥、检测器/采样接线及权威数据来源，不提前启用。

来源及参考产物哈希见 `tools/click-block-reference-sha256.json`，重生成命令见 README。修改前源码及哈希由主目录 `navro-proxy-click-block-current.txt` 指向。

## 第十三轮：完整 ItemUseTracker、点击空气、烟花和释放分发

- 新增同名 `ac.navro.proxy.data.ItemUseTracker<I>`，迁入原版全部状态与方法：请求、DirtyUsing、使用按键锁存/待释放、原生旗标、元数据 sent/ACK、当前输入冻结减速、手持切换、pre/post tick、快照及恢复。使用泛型原生物品身份和必需 Context，不将 SDK wire AIR、普通空物品、null 和原 AIR_ITEM 哨兵合并。Context 提供真实原生 getter、定义比较、组件判定、Food 查询和 clone；不从物品名称或 wire ID 猜测能力。
- 保留原 canBeUse 的组件优先、canRelease / getUseDuration、原 ID 集合和 ominous_bottle 例外；skip=true 仍先求值 canBeUse，再豁免拒绝，不把 skip 当作跳过所有读取。保留 Food.getByRelative 调用及原空分支，没有新增饥饿度或时长算法。
- 完整保留同一玩家/AuthInput tick 内已确认 ACTION=false 的重启等待；仅在途 false 不阻止本地开始。pending false 包数、最后 true sent、最后 false ACK 和最后已确认 using 保持 live 状态，不随移动快照恢复。没有把服务端 tick、墙钟或 RakNet ACK 当作这里的玩家输入 tick/NetworkStackLatency 确认。
- 快照 clone 非空物品并将 null / native isNull 归一为原生 AIR 哨兵，恢复再次 clone；保留 snapshot 与 live 使用对象的身份区别。冻结的 predictionUsing/action/inputTick/slowdown 属于该输入帧，release 和元数据变化不回写当前帧减速值；仅新快照更新。保留 0.122499995F 原位值、6 种 spear 的 Locale.ROOT 识别、独立历史 ACTION 标志、int 计数溢出和 long 边界。
- `handleClickAir` 已迁入完整原分支。手持定义门控后，实际 ItemFirework 先读取服务端滑翔与胸甲类、补偿滑翔和补偿胸甲 ID/isNull；仅补偿条件满足才预测加速，原生火箭预期由服务端两条件决定。非烟花才检查 USING_ITEM、尝试 resumeLatchedUse，然后 use(skip=false)；重复点击不重启跟踪，烟花永不进入跟踪器。
- `WirePlayerState.beginNukkitGlideBoost` 保留原 getFlight 结果后的 Math.max、`10 * (flight + 1) + 11` int 运算、剩余时间取 max，以及仅 expectsNativeRocket=true 时置 pending 的逻辑。compensated-only 点击不新设 pending，也不擅自清除原已为 true 的值。flight getter、物品类和服务端状态均为明确的原生边界。
- `handleRelease` 已迁入完整分发：缺失/错误原 payload 返回，非 RELEASE 动作不操作；读取跟踪器与补偿手持后查询服务端手持，先捕获 server-held、used-item、compensated-held 三个候选，再按顺序检查激流，首个命中即调用原 dirty-riptide 边界并停止，最后 release 和 setDirtyUsing(NONE)。保留 null 候选、回调前的 charge、异常前后的部分状态和请求清理顺序；未用补偿手持冒充服务端手持。
- `ClickAirContext` / `ReleaseContext` 明确提供原生物品身份、类型/flight、服务端滑翔/胸甲/手持、flags、原生定义校验、riptide level 及原 PlayerData.setDirtyRiptide 副作用。原生服务器私有状态和激流准备/预测政策仍是独立接线，接口没有默认成功实现。泛型 tracker 的 getPlayer 返回此原生状态上下文，不引入 Nukkit 生产依赖。
- 原 ItemUseTracker 的全部字符串及 28 个 stage/event 调用名称、参数和先后源码保持一致；完整 ItemUseDebug 文件/JSON 采集器仍待绑定原接口，未另造显示文本或改变颜色排版。

验证：重新编译当前原版 ItemUseTracker，实际执行 64 个连续会话、16384 步 API 操作，逐步核对全部私有/公开状态、原生物品/空气/null/clone 身份、保存快照、冻结 float 原位值、flags、Food/组件、live ACK 计数及异常后的状态。输入含原生数字/命名构造、实际 spear、28 种能力视图，以及真实原生 CustomItemDefinition 上 7 种组件 NBT 组合（4 种命中原组件规则）、计数变化、null setter、foreign snapshot、NaN/Infinity、int/long 边界；不是用代理实现生成预期值。原诊断器在该参考中关闭，字符串和全部诊断调用另作逐项源码审计。

另直接执行当前原版 handleClickAir、handleRelease、beginNukkitGlideBoost，共 6144 组（各 2048 组），使用真实原跟踪器、服务端 inventory getter、ItemFirework/ItemElytra 与相同 ID 的非同类物品、实际原生激流附魔，核对读取顺序、完整跟踪器状态、加速/pending、激流结果、库存及异常。包含 188 组原生 NBT 类型异常、56 组空服务端库存异常及 1126 次加速时长变化。riptide setter 属于原生副作用边界，参考执行真实原方法；测试绑定按原 10 tick/level/clone 规则提供该边界，不据此宣称整套激流预测/附魔政策已接入。

生命周期测试验证旧 ACK 与历史快照分离、释放不改当前帧减速、slot-transition/button-up 区别、空物品与哨兵身份、Food 查询先后及 trident 溢出、组件短路、skip 求值、spear/历史 ACTION、原诊断参数、重复 CLICK_AIR 与旧护甲交换衔接、释放候选捕获/优先级、服务端读取失败不提前清理、烟花的两套胸甲条件与 tracker 隔离。

全量 244 项测试通过，无失败、错误或跳过；test + installDist 构建通过。全部既有生产资源和原有文本保持不变，新状态机及事务阶段进入分发，Nukkit 和离线工具不进入生产依赖。完整物品/定义/能力与服务端权威桥、元数据/ACK/输入调度、检测器/调试采集器及烟花实体确认/激流预测接线仍待继续；没有挂入不完整的实时过滤链。

来源及参考产物哈希见 `tools/item-use-reference-sha256.json`，重生成命令见 README。修改前源码和哈希由主目录 `navro-proxy-item-use-current.txt` 指向。

## 第十四轮：FlagTracker 使用握手与原生元数据字

上一轮完整状态机/点击空气/释放阶段的全量 244 项测试和 installDist 已在中断后复核，分发、源文件和备份已完成核对，本轮在此基础继续。

- 新增同名 `state.tracker.FlagTracker<I>`，保留原 flags 集合、flying/wasFlying、两种 set、单旗标设置、cloneFlags、clear 和 restoreFlags。原生 item-use tracker 与 AIR_ITEM 身份通过必需 Context 提供，不新增空数量、isNull 或 javaItemId 判断来替代原 usedItem != AIR_ITEM 比较。
- 元数据 set 先记住原潜行/游泳/USING 状态，再 clear/add；server=true 时只恢复原潜行位 1 和游泳位 57。随后按原物品身份决定 USING_ITEM=-1 及 DirtyUsing，最后无条件调用 onMetadataUseStateChanged。保留 dirty setter 在 metadata callback 之前的顺序，后者可以因服务器仍在使用而重新设为 INVENTORY_TRANSACTION / awaiting。
- 保留 null usedItem、普通空物品和 AIR_ITEM 哨兵的原区别：前两者与哨兵身份不同，不能擅自当作“没有请求”。元数据通知不代替 NetworkStackLatency ACK；不会修改 tracker 的 live ACK 记录。restoreFlags 只恢复集合，不启动新握手，也不清理请求、ACK 等待或 flying 历史。
- 现有 `EntityMetadataUtil.getFloat(EntityDataMap, EntityDataType)` 原 SDK 适配保持原函数体与签名，追加原生 Metadata 视图重载和原 copyFlags / hasFlag / mergeFlags。保留有限 float 检查、缺失字与存在但全零字的区别、DATA_FLAGS=0 和 DATA_FLAGS_EXTENDED=92 两段独立 64 位替换、内部负旗标/现有范围外旗标，以及忽略更新范围外值的原规则。
- Metadata 视图要求实际原生 exists/getFloat/getLong 语义；错误类型保持原 getter 的 ClassCastException / null 解包异常，不默认转成 Number、零或 false。原 128 位范围和 long bit 63/127 行为不改为 SDK EntityFlag 枚举序号。null set/player/restore 的失败点与失败前 clear/add 副作用保持原样。

验证：重新编译当前原 FlagTracker（使用原 Lombok getter 生成器）、ItemUseTracker 和 EntityMetadataUtil。48 个连续会话、9216 步旗标/使用/ACK/快照/飞行操作逐步对照，核对完整 tracker 状态、物品/哨兵/clone 身份、旗标和 flying 历史，包含 130 个原异常案例。另用真实 Nukkit typed EntityMetadata getter 生成 4096 组参考，逐项比较 finite float、旗标复制/查询、部分字合并、getter 调用顺序及异常类型，覆盖 null、错误类型、NaN/Infinity、负零、Long.MIN/MAX、63/64/127/128 边界、内部负旗标和范围外值。

5 项生命周期测试另验证 server/client 物理旗标区别、身份判定、dirty/callback 顺序、快照恢复不握手、clone 隔离、空输入的部分提交、两段字与内部位，以及元数据通知和已确认 ACK 的分离。参考中的组合驱动只调用原 mergeFlags 与 FlagTracker；不据此宣称完整 NavroPlayer.applyClientVisibleMovementMetadata 的姿态/尺寸/冲刺分支已迁入。

全量 251 项测试通过，无失败、错误或跳过；test + installDist 构建通过。既有生产资源、显示文本、颜色与排版保持不变，新类和工具重载已进入分发；Nukkit、Lombok 和离线参考工具不进入生产依赖。

完整原生物品/定义/服务端权威桥、SDK 原始旗标字的可靠绑定、元数据 sent/ACK 与输入/姿态调度、检测器及诊断器接线仍待继续，未提前将新状态机挂入不完整的实时过滤链。

来源及参考产物哈希见 `tools/flag-tracker-reference-sha256.json`，重生成命令见 README。修改前源文件和哈希由主目录 `navro-proxy-flag-tracker-current.txt` 指向。

## 第十五轮：完整移动元数据应用与姿态请求归属

- 新增 `state.MovementMetadata`，迁入原 `NavroPlayer.applyClientVisibleMovementMetadata` 完整方法及宽度/高度/缩放、眼高、补偿鞘翅门控。先记录原阶段和物理 AABB/爬行请求，再按两段原生字合并 flags、映射 ACTION 到内部 USING_ITEM、调用完整 FlagTracker 握手、恢复 CAN_CLIMB；仅基础字确认更新 serverSprinting / clientSwimming / 潜行 / 鞘翅门控，然后更新姿态，最后按原顺序处理 width、height、scale 和请求确认。没有把服务端冲刺确认写入当前 AuthInput 的冲刺快照或速度属性。
- 新增同名 `prediction.fluid.FluidMovement` 的完整姿态相关方法：beginInput、finishInput、applySwimmingAction、两种 updatePose、updatePoseForMetadata、hasCrawlingSizeRequest、queueMetadataPose、finishMetadataPose、acknowledgeDimensions、acknowledgeWidth、withWidth 和两种 horizontalPose。原从 pending 到 ready 的输入归属、readyBeforeMovement、爬行变化重算、同帧提交与 finishInput 提交顺序均保留；没有用“flags 一变就立即缩放”的近似替换。
- `FluidPoseState` 明确只承载上述方法依赖的原 FluidPredictionState 姿态/输入字段和 captureInputState；液体采样、游泳决策、travel 和完整 FluidPredictionState 快照不在此类中伪造。Context 要求原生数字旗标、实际物理 AABB/resize 行为、live-player 与 preview 区别及两种采样器回调；液体模块及完整预测调度仍需继续迁入和绑定。
- 实际玩家的 swimming/gliding/spin 保留原物理高度，只把逻辑水平姿态眼高设为 0.42；爬行请求、原 standing 高度恢复、已确认高度的权威性和 width-only ACK 的物理/缓存双更新保持原分支。保留 0.58、0.625、1.49 等原值和眼高阈值，不归一成近似尺寸；缩放仍保留原 hardScaled 的眼高/固定属性规则。
- 鞘翅读取补偿胸甲槽 1，按原 null / native isNull / native ID=444 判断，不增加必须 ItemElytra 类的条件。requestedGliding=false 不读取胸甲；仅扩展字更新不重新执行基础字的滑翔门控。setServerSprinting、setClientSwimming、物理 AABB、debug stage/trace 和采样器都是明确的必需边界，没有成功占位实现。
- 原阶段/诊断文本共 66 个字符串逐项审计，内容、参数键和源码先后保持一致；物品使用状态机的原诊断调用未改。采样器仍按 movementSampler 再 trapdoorSampler 调用，异常时不继续后面的回调；原先已发生的 flags、尺寸或输入归属变更不回滚成原版没有的原子操作。

验证：重新编译当前原版 NavroPlayer、PlayerData、FluidMovement、FluidPredictionState、ItemUseTracker、FlagTracker 和 EntityMetadataUtil，直接执行实际方法。32 个连续会话、8192 步操作中含 1362 次完整元数据应用、357 次仅基础字确认、353 次仅扩展字确认和 130 次 null flags；覆盖 25 类操作及 59 个原生异常。逐步比较尺寸/眼高/固定属性的 float 原位值、物理位置和 AABB 六边界、resize revision、冲刺/游泳字段、全部姿态/输入缓存、原 input AABB、物品使用状态/快照/live ACK、旗标及返回/异常。参考值由原版实际执行产生，未调用代理实现生成。

8 项生命周期测试另验证基础/扩展字差异、完整使用握手、CAN_CLIMB 保留、冲刺分离、爬行请求跨输入传递与停止重算、0.58 宽度 ACK、standing 元数据请求归属、1.49/0.625 高度及缩放、同 ID 非同类鞘翅、胸甲读取短路/异常、采样器次序和失败前部分提交、NaN/Infinity/负零及原诊断参数。参考的诊断/采样输出关闭，其调用文本作静态审计，回调顺序和失败行为由生命周期测试覆盖；几何上下文使用现有代理 WirePlayerState 的物理 AABB 实现，未替换生产碰撞公式。

完整原生物品/定义/服务器权威桥、SDK 原始元数据字和存在性、sent/closing ACK/输入调度、液体与完整快照、采样器/诊断器接线仍待继续。新阶段通过必需 Context 提供，尚未替换实时 WirePlayerState 的旧 metadata 入口，也未启用不完整的实时事务过滤。

全量 260 项测试通过，无失败、错误或跳过；test + installDist 构建通过。全部既有生产源码和资源保持原样，原有显示文本、颜色与排版未改。新增类已进入分发，Nukkit、Lombok 和离线参考工具未进入生产依赖。

来源及参考产物哈希见 `tools/movement-metadata-reference-sha256.json`，重生成命令见 README。修改前源码及哈希由主目录 `navro-proxy-movement-metadata-current.txt` 指向。

## 第十六轮：恢复 Timer 检测入口、ACK 调度与本地超平坦测试

本轮按用户要求启用已注册的检测器并启动本地测试服。继续补齐的是 Timer 所依赖的收包/延迟/生命周期调度；第十五轮的完整移动元数据阶段仍保持明确 Context，尚未冒用 SDK 枚举序号接入原生旗标字。

- `ProxyConfig` 默认切换为 DETECT，增加 `--mode detect` / `--detect`；`--mode inspect` / `--inspect` 保留观察模式。ProxySession 在构造时设置会话检测模式，真实客户端收包调用同一个 `SessionState.receiveClient` 入口；检测拒绝后立即返回，不进行输入观测、库存预测或向后端转发。
- 检测顺序保留已有原版分发：BadPacketsC 先验证候选 tick，随后增加原加载宽限计数、运行 Timer、NegativeTimer 和已注册的其余 PacketCheck，全部通过后才提交 tick。检测后的观察不再次增加 sinceLoadingScreen，也不覆写 BadPacketsC 提交的到包时间和 gap；观察模式仍按原观察路径更新。拒绝包由原拒绝日志入口计数一次，不生成已接受输入快照。
- 恢复 `LatencyUtil.onLatencyAccepted` 向注册的 PingBasedCheck 逐项分发，明确由会话检测模式控制。ACK 的原任务先运行，再通知检测器，最后更新 prevAcceptedTime / prevAcceptedLatency；未知/重复 ACK 不消费任务。inspect 仍应用 ACK 状态任务，不运行检测回调。
- 补回原 PacketListener 的 NegativeTimer.onDeath / onRespawn：死亡/复活包和健康状态先绑定真实出站 ACK，确认后才改变 acknowledgedDead 并重置负计时器；客户端复活请求的原分支保留。没有改 Timer / NegativeTimer 的算法、阈值、宽限、lag credit、计时域、告警文字和颜色排版。
- 6 项真实入口生命周期测试覆盖默认/显式模式、正常 20TPS 输入与单次宽限计数、加速输入在观测前拒绝、重复 tick、原 Timer 告警全文与原回拉请求、sent-NS 确认对两个计时器的作用、任务先于检测回调、未知 ACK、inspect 隔离及死亡/复活的确认边界。保留 NegativeTimer 原重置后的首输入等待，未为测试缩短这段逻辑。

全量 266 项测试通过，无失败、错误或跳过，test + installDist 构建通过。原 Timer、NegativeTimer、Check 和 AlertManager 源文件及全部 13 个既有生产资源保持原样。原版完整预测、经过验证的位置来源和 teleport/setback 传输仍未接齐；Timer 保留原回拉请求并记录 transport pending，不把“上一个收到的位置”近似成原版 lastKnowValid。实时 Timer 告警与拒包已启用，其余尚未接线的检测器/事务验证没有伪装成已运行。

本地测试使用已有隔离后端目录 `~/navro-proxy-backend-test`，绑定 `127.0.0.1:19133`，插件目录保持为空。按用户后续要求新建并选择 `navro-proxy-flat` 超平坦主世界，保留原 `navro-proxy-test` 世界；关闭该测试后端 load-all-worlds，避免保存过旧世界的玩家继续进入虚空图。实际 LevelDB level.dat 的 generatorName=flat 已用原生 NBTIO 验证。后端标准输入使用控制 FIFO，避免 /dev/null EOF 导致控制台忙循环；运行信息和日志路径记录在 `timer-test-backend.json`。

代理使用本轮 installDist 启动，监听 `0.0.0.0:19132`，mode=DETECT；控制台告警订阅保留原设置，日志为项目 `logs/timer-live.log`，PID/参数/分发 SHA 记录在上述隔离目录的 `timer-test-proxy.json`。实际 RakNet 连接通过代理到后端，协议 2193 收到正确 NetworkSettingsPacket；这项探测验证网络设置握手，完整游戏操作由用户客户端测试。

修改前源码、配置、哈希、最终验证与运行信息由主目录 `navro-proxy-timer-live-current.txt` 指向。

## 第十七轮：纯代理告警授权、命令与 Timer 普通拉回

用户要求继续对齐告警权限和拉回，并明确运行时保持纯代理、避免依赖 MOT/PMMP 核心 API。本轮运行代码仅使用标准 Bedrock 协议、已验证登录 UUID、代理自己的文件和状态；不加载后端核心 JAR、不安装后端插件、不按 serverEngine 名称切换权限或拉回算法。原核心源码/编码器只用于离线对照。

- 新增 `permission.ProxyPermissions` 和 `ProtocolPermissions`：代理独立管理 permissions.json 的 UUID -> 权限节点 -> boolean。明确节点值优先于原 default: op；标准能力包只提供 operator 默认角色，未知后端插件权限不作猜测。匹配 UpdateAbilities 的唯一实体 ID，支持与 runtime ID 不同；OP 使用标准 playerPermission=2 或 commandPermission 原始值 1，不因 SDK 的 ADMIN 枚举名而错误授权。角色和有效锚点在网络线程之间发布，不改变单线程原算法语义。
- 新增 `command.NavroAlertCommand`，迁入原 `/navro alert` 完整分支的权限检查、订阅反转、持久化失败和成功消息；原字面值、前缀、颜色与排版保持不变。原订阅器和告警发送器未改，保留撤销开关权限后已有订阅仍接收提示的原规则。其他子命令不冒充已迁入。命令参数分词完整保留原引号和 JSON 花括号规则；AvailableCommands 补入 alert，保留已有后端命令，实际重编码修改后的命令表。
- 新增同名 `teleport.TeleportUtil` 的普通 teleport/queue/drain 路径和原 `TeleportData`：保留 lastKnowValid 的引用语义、构造目标 clone、未完成传送门控、原发送字段和两种确认计算。Timer / NegativeTimer 本身不改，原 WirePlayerState 请求现接入实际 TeleportSender，仍累计原请求数。
- TeleportSender 在真实客户端 event loop / FIFO 上生成独立纠正包，经现有压缩、加密及关闭批次 ACK 发送；目标仍是保存的 lastKnowValid。发送阶段登记实际包的 ACK 任务，同步发送异常撤销其任务与队列项；不可用会话不制造待确认条目。普通拉回 TELEPORT、BEHAVIOR=4、onGround=false、rotation 和其余 primitive 默认值与原版一致。
- 本地玩家 NORMAL 出站包按原版改成 TELEPORT，pitch-only 不进队列；补齐 NORMAL 原本未携带的 teleportCause 原生默认 0。保留同一 SDK 包对象进入新编码 wrapper，确保真实出站包和按 identity 暂存的 ACK 任务一致，不能因为 clone 丢失任务。RESET 原生模式 1 对应 SDK RESPAWN；不按枚举英文名猜模式。
- ACK 仅标记接受，下一次输入按原顺序移除并处理所有已接受普通条目，遇到第一个未接受条目停止。包括不匹配的已接受条目，不永久挂在队头。TELEPORT 需 HANDLE_TELEPORT 和 float 距离 <=1e-3，RESET 需原 double 平方距离 <=0.1；匹配 RESET 使用客户端近点，其余使用原目标。原目标减 offset / 重置 / 再加 offset 的 float 边界保留。不匹配且队列清空才重新发送，后续未接受条目会阻止重复发送。
- 启动、维度确认和已处理的普通传送维护锚点，提供显式完成预测帧的有效位置提交接口；没有把收到或被包检查接受的客户端位置直接当作原生 lastKnowValid。完整 PredictionRunner 尚未接入，所以普通行走观测不会擅自推进这个锚点。

验证：重新编译原 TeleportUtil、TeleportData、TeleportHandler，实际执行 4096 组普通构包、队列门控、原构造 clone、模式确认和已接受队列 drain；按协议 924/2193/2225 逐字节比较原原生 MovePlayer 编码。另由真实 MOT UpdateAbilities 和真实 PMMP/NetherGames PHP 协议编码器生成 20+24 组能力包，经同一代理 SDK 解码和权限绑定验证，不用代理生成预期值。

10 项新增测试覆盖原参考、真实 session FIFO 与关闭 ACK、ACK 前输入、ACK 后客户端确认、重复门控、不匹配后重发、NORMAL 重编码、RESET 无 HANDLE 的近点确认、无效会话、普通输入不能替换有效锚点、权限默认与显式拒绝、权限移除后的原订阅行为、重新载入订阅、保存失败原提示、命令发现与编码。

边界仍明确：本轮完成普通 Timer 拉回的包、队列、确认和现有物理重置适配；完整原生激流重置、流体/物品使用重放状态、经过完整预测持续推进的有效位置、RewindData/CorrectionResetData/smooth rewind 属于后续阶段，没有用上一客户端坐标或近似重放替代。跨核心验证目前覆盖协议编码/解析，不据此声称所有 PMMP 游戏物理已完全兼容。

全量 276 项测试通过，无失败、错误或跳过；test + installDist 构建通过。全部 13 项既有生产资源、Timer/NegativeTimer 算法、告警发送器与订阅器源文件保持不变。分发已通过无后端依赖审计，并复制到隔离的版本目录再重启代理，后续构建不会覆盖正在运行的 JAR；原超平坦后端继续运行。更新后协议 2193 的真实 RakNet 握手再次通过。

来源及参考产物哈希见 `tools/alert-teleport-reference-sha256.json`。修改前源码、配置和哈希由主目录 `navro-proxy-alert-teleport-current.txt` 指向；重生成命令见 README。

## 第十八轮：现代登录身份绑定与运行状态核实

排查用户反馈的停顿时，后端控制台仍有响应：在线时查询为 20 TPS、超平坦世界约 1ms/tick；后续空闲查询仍为 20 TPS、负载约 0.48%。线程快照未显示 Java 死锁。代理同时存在大量 Timer 拒包和后端连接超时；未据此把所有拒包定为误判，也没有关闭检测或改阈值。是否开启变速及正常速度下的客户端表现仍需实测。

发现并修复独立的真实问题：旧登录处理只读 rawIdentityClaims.extraData，而现代认证令牌原始字段为 xname/xid/cpk；因此昵称停留 unknown、UUID 未注册，导致代理权限和告警订阅无法正常工作。新增 `protocol.LoginIdentity`，统一读取当前 SDK 的已验证 identityClaims().extraData，兼容旧链和现代令牌；保留 SDK 提供的身份 UUID，不调用后端 API、不按核心名称猜身份。完成客户端签名校验后才登记昵称、UUID 和告警接收器。代理控制台记录已验证昵称/UUID，便于配置代理自己的权限节点，不记录令牌或密钥。

3 项新测试覆盖旧链身份保持、没有原始 extraData 的现代令牌，以及未签名/缺少昵称的身份拒绝。全量 279 项测试和 test + installDist 通过，原 Timer / NegativeTimer 算法、告警文本和全部既有生产资源未改。新分发以独立版本目录更新正在运行的代理，超平坦后端继续运行。

按用户明确请求，已通过本地测试后端控制台执行 `op OakenApple11053`，确认原控制台返回成功并写入 ops.txt；没有自动开启告警订阅，仍需 `/navro alert` 手动切换。

修改前源码、运行诊断、最终校验和更新信息由主目录 `navro-proxy-runtime-identity-current.txt` 指向。

## 第十九轮：实时预测、预测检测与 Timer 预测锚点

`prediction/live` 从当前 Navro 源码迁入 PredictionRunner、PlayerTicker/LivingTicker/EntityTicker、四种 travel engine，以及攀爬、脚手架、潜行缩放、黏性方块、激流冲刺等阶段。候选试算只比较原始位置残差和原碰撞声明的 tie-break；实际选择后重新执行完整阶段。顺序保留输入缩放、跳跃、travel、边缘退让和碰撞、重力/阻力、方块内部效果及潜影盒推动，不能直接将旧 ProtocolPredictionReplay 的“碰撞前 finalize”组合接入实时输入。

新增协议上下文绑定已补偿的世界、背包、元数据、尺寸、药水、属性、外部速度 ACK 和输入状态。CAN_CLIMB 初始化/元数据保护、原生 SPIN_ATTACK=56 到 SDK DAMAGE_NEARBY_MOBS 的名字映射、游戏手柄和脚手架动作别名均明确适配。客户端申报位置仍单独保存，不能作为碰撞或物理计算的输入速度。飞行/骑乘/睡眠/加载等分支按原有边界处理；不实现原版本身尚未实现的骑乘预测。

物品使用调用已有完整 ItemUseTracker、FlagTracker 握手及 click-air/release 分发，冻结原版的 0.122499995F 减速值；火箭不进入持续使用状态机，释放仍按权威库存观测、正在使用物品、补偿手持的顺序选择激流物品。运行时不加载核心 JAR，不按 MOT/PMMP 名称切换代码。`prediction-items.bin.gz` 离线执行原版物品解码构造选择，涵盖 17 个协议入口、1,258 个 factory、全部 82,444,288 个 metadata 输入；保留数值/命名构造区别、固定 metadata、原生空物品、canRelease、useDuration、Food 查询及原生异常。为限制运行堆占用，属性行以原始字节和区间索引保存，查询缓存有界。

Prediction、Phase、GroundSpoof、NoSlow、AntiKB、Collisions、Horizonal、Vertical 保留原检测分流、阈值、短路、verbose 文本和 fail 格式。AuthInput 在转发前执行预测；经过改写的碰撞位和 delta 必须重新编码，不能转发旧 wrapper 字节。外部 motion、属性、药水、MovementEffect 等使用实际出站批次的确认；普通传送会使之前未确认的速度失效，纠正恢复则保留更新的外部速度。

每个完成的预测保存原尺寸/AABB、wire offset、ground、before/after/tickEnd 和 tick（原默认历史长度 20）；lastKnowValid 由这份 canonical 物理帧推进。Timer 算法、阈值和普通 TELEPORT 路径不改，只改锚点来源。预测偏移使用原默认的 hard correction：原 MovePlayer TELEPORT/BEHAVIOR 包加纠正速度，并分别处理普通 ACK 和严格客户端目标确认；ACK 本身不能确认 hard reset。普通 Timer 传送仍沿用原有 ACK + HANDLE_TELEPORT 处理。

边界：本轮运行默认 hard correction，不提供 smooth rewind/历史 AuthInput 重放开关。旧 `mv_origin_*` 降级物品的实时预测恢复视图在第二十轮接入；未导出的自定义 native item/block 实现仍须专门适配，不拿普通属性或猜测碰撞替代。协议/原版对照测试不等于对所有后端自定义物理、插件或真实客户端场景的兼容认证。

验证包括 4096 组实际原版执行结果的逐浮点位比较，以及连续静止、地面移动、跳跃、液体引力、Timer 锚点推进、伪造坐标、硬纠正确认、速度 ACK、旧速度失效、使用物品握手、原检测分流和真实出站编码的回归。最终全量结果、源码/产物哈希和备份位置见 `tools/prediction-reference-sha256.json` 及主目录 `navro-proxy-prediction-continuation-current.txt` 指向的审计目录。

最终串行重编译验证：308 项测试，0 failures / 0 errors / 0 skipped；`test installDist --rerun-tasks` 成功。已部署到独立版本目录并恢复本地 `0.0.0.0:19132 -> 127.0.0.1:19133`；代理和后端均通过协议 2193 的真实 RakNet/NetworkSettings 握手。后端超平坦世界、OP 文件和既有告警配置保留。真实账号登录后的移动/Timer 表现仍由客户端实测确认，不将握手探测等同于完整进服测试。


## 第二十轮：物品原生解码视图接入实时预测

补齐旧版降级占位物品的 `mv_origin_id/meta/namespace/nbt` 恢复。新增离线构造器目录与运行时 `NativeItemDecoder`，按原版实际入口区分 inventory、transaction、slot、descriptor：旧 getSlot 仅在数值 INFO_UPDATE 上恢复，descriptor 对带齐标记的物品恢复；入口版本门槛直接来自固定参考 JAR。原版先处理外层 Damage、__DamageConflict__ 与旧格式附魔长度限制，再执行恢复；恢复提前返回时舍弃外层 NBT 和 CanPlace/CanDestroy，只保留内层 NBT 或构造器默认 NBT。负 stack-net-id 的旧格式保留、descriptor 正值条件也分别绑定。

构造器保留数字/命名选择，支持映射中无注册类的数字别名（如 519 归一为类型化铜锭），重新计算原生 namespace/networkId；不能把占位 info_update 或原始网络 definition 当成恢复后身份。原生 getDamage 对 65535 返回 0 的哨兵、固定 metadata、NBT 覆盖后的 metadata 均保留。火箭、烟火之星等默认 NBT 由原构造器按全部 metadata 导出，不能统一置空。新目录链接既有 prediction-items.bin.gz 的 SHA-256，既有属性资源不改。

实时 ItemUseTracker/FlagTracker 及完整 click-air/release 调用使用独立解码视图：弓减速握手、火箭 Flight、权威库存/正在使用/补偿库存的激流选择均读取原生物品。Swift Sneak、Soul Speed、Depth Strider 的护甲 NBT 也使用这个视图。原始库存包、事务包、ACK 时序及 wire cache 保留原数据；深复制避免内层数组互相污染。

离线完整构造器域为 3,089 个、每个 65,536 输入，共 202,440,704 metadata；额外属性按压缩行和有界缓存查询，稀疏 metadata 例外逐值验证后保存。独立对照工具运行固定 JAR 内真正的 BinaryStream 解码方法，记录 17 协议、4 入口的 4,488 组实际字节和解码结果/异常。测试逐项比较 ID、metadata、count、null、stack-net-id、namespace/networkId、NBT、使用属性及异常；可用 SDK 的 inventory 入口还用相同字节通过 SDK 解码后比较。新增实际生命周期回归覆盖占位弓、ACK 前权威激流三叉戟释放、ACK 后恢复护甲附魔及原 wire 数据保持。

边界：本轮接入范围是实时预测和其物品使用；放置/束口袋分发的全部解码视图仍需单独审计。未导出的自定义构造器、超出 16 位导出域的 metadata 不猜测。地图缺少内层原始 NBT 时，原版构造器依赖进程全局 mapCount，静态目录不能还原该运行状态，因此明确不支持该缺失分支；已有地图 NBT 原样保留。运行时不加入参考核心 JAR，不按核心名称选择行为。Timer 算法、检测阈值、告警文字/颜色不在修改范围内。

修改前源码、资源哈希与部署回退信息由主目录 navro-proxy-item-decoder-current.txt 指向。来源、产物和验证信息记录在 tools/item-decoder-reference-sha256.json。

命名恢复只接受导出的 namespace 形式（含原版大小写、空格规范化）；fromString 的任意数字字符串、附加 meta 字符串及未导出别名不猜测替代。

最终验证：313 项测试，0 failures / 0 errors / 0 skipped；串行 `test installDist --rerun-tasks` 成功。另一次从零开始执行全部 202,440,704 原生构造输入，产物 SHA-256 与当前目录完全相同：dde135143cb2c8ef16626fbf9f07df7365ac56ef0d3a7229353a4452c48b23a9。14 份既有生产资源哈希全部一致。已更新独立版本测试代理，代理/后端均通过协议 2193 的真实 RakNet/NetworkSettings 握手；权限、OP 与告警订阅配置哈希不变。握手验证不代替真实账号进服后的移动验证。


## 第二十一轮：束口袋缓存、旧式丢物及热栏切换的原生物品边界

检测模式的 CompensatedInventory 绑定 NativeItemDecoder，ItemCache 保留原始 wire data，并增加按所属库存协议读取的 native data 视图；clone 保留所属库存、深复制原物品并共享原 BundleData。isEmpty 使用原生空物品规则，不能仅凭 SDK count 判断正数量的原生 AIR。

ItemCache.build 的束口袋判定和 bundle_id、动态容器 125 的 storageItem 查询均读取解码后的 NBT；BundleData.add 比较原生 namespace/networkId，保留原有容量、克隆、合并及计数行为。后端 InventoryContent/Slot 仍绑定真实出站批次的关闭 ACK，读取快照不受后续包/数组复用影响，原始库存和转发包不替换为内部定义。

旧式 NORMAL 丢物按事务入口解码 claimedDrop、按库存入口解码预测槽位，保留 WORLD/CONTAINER 任意顺序、原数量检查和深复制剩余堆叠。尤其协议 975 的库存已使用 descriptor，但事务仍使用旧 slot，不能将两者统一恢复所有标记。检测之外的 inspect 仍只观察协议状态。

客户端 MobEquipment 在原实体/窗口/槽位/未变化校验之后，通知已迁移的 onHeldItemChanged。相同原生身份（含占位物品恢复后的同物品）不释放；切换物品按原状态机清除使用标记，保留物理按键锁存，并不修改当前帧已冻结的减速。服务器 PlayerHotbar 修正保持原 ACK 行为，不新增客户端切换回调。

对照发现原版 runtimeId2Name 中有合法名称未出现在 getItemPaletteEntries（包括普通束口袋），所以仅导出旧 palette 会漏掉真实接收入口。新增小型 item-runtime-bindings.bin.gz，覆盖 17 协议共 4,551 个补充绑定，严格执行原版字符串注册/数值映射/固定 metadata 选择，链接既有构造器目录 SHA-256，不重写 15 份既有生产资源。其中 260 个额外命名构造器再按全部 17,039,360 metadata 输入直接执行原生 getter 导出，不用近似的数值物品/同名方块替代。所有补充绑定均由实际原生 BinaryStream 字节解码结果逐项对照。

另保留原生 ItemBundle.setNamedTag 的 bundle_id 数字类型验证：仅恢复分支或 CanPlace/CanDestroy 导致调用 setter 的位置触发。普通 setCompoundTag(byte[]) 不额外调用该 setter。对照工具验证固定注册表中束口袋 tag 集与 ItemBundle 构造器一致；不构造原核心私有 NEXT_BUNDLE_ID 状态，Navro 缓存的这些读取路径只消费显式 NBT。

独立参考工具执行原 ItemCache、BundleData、ItemTransactionValidator，经过原生 writer/decoder 后输出 1,728 组束口袋/丢物/错误类型结果（各 576），比较持有者、重复 ID、clone 共享关系、计数、原生空物品、堆叠身份及异常。另有真实编码批次 ACK、内层数组隔离、热栏切换和协议 975 的针对性回归。来源、产物、验证和部署审计写入 tools/decoded-inventory-reference-sha256.json；修改前完整备份由主目录 navro-proxy-inventory-decoding-current.txt 指向。

剩余边界：完整实时放置上下文与相应检查、事务结果到收包取消的完整接线、ItemStackResponse 同步仍待接入。本轮不启用未迁移的 BundleClickProcessor 请求预测；按当前原版 handle(ItemStackRequestPacket) 只设置原有 inventory action 标记，避免增加原版当前调用链未执行的逻辑。未导出的构造器/字符串形式、超域 metadata、缺失 NBT 的进程计数型地图等既有边界不猜测替代。Timer、检测阈值、告警排版/文字/颜色不变。

最终串行验证：320 项测试，0 failures / 0 errors / 0 skipped；`test installDist --rerun-tasks` 成功。已更新独立版本测试代理，代理/后端均通过协议 2193 的 RakNet/NetworkSettings 握手。15 份既有生产资源哈希不变，权限、OP 和告警订阅配置保持一致；运行时没有新增原生核心依赖。真实账号进服后的完整交互仍须客户端实测，握手探测不代表完整登录测试。


## 第二十二轮：分片地形 ACK 接入与只读碰撞诊断

按用户补充，本次 Phase 出现在挖掉脚下或身边方块后；旧控制台可找到 (73,4,124–126) 附近七条 entered solid block，offset 约 0.0784。旧日志没有该帧的方块更新/ACK/AABB，不能据此确认根因。用户随后明确要求暂不对齐 ignoreDesyncedBlock，先完成分片区块和日志，部署后只读日志；本轮遵守此范围，不添加脚边空气更新提前生效、不调整 Phase 算法/阈值/告警文字。

SubChunk 按 SDK/native 实际布局处理 center+有符号 offset，完整复制 section cell 数据后等待对应出站批次关闭 NSL，再更新一个垂直分片；不覆盖相邻分片或更新的本地预测。SUCCESS_ALL_AIR 显式填充协议 air ID；失败状态保持原缓存，无法解码的成功数据保持未知并记录原因。LevelChunk 的 subchunk stream 公告建立部分列，分片 known 状态与列存在分开，世界适配器只在 canonical AABB 和紧邻支撑区间的数据已知时报告可用；既有完整列的可用性和 Phase 数学判定不改。

缓存地形只使用已 ACK 的 inline/blob payload。ClientCacheMissResponse 的 blob 先冻结，在自身 ACK 后才可填补等待分片；等待期间较晚 ACK 的单方块更新在 blob 基础上重放，较新本地预测仍按 revision 保留。整列替换和维度 epoch 清除旧等待项。blob 缓存 16 MiB、等待分片和更新覆盖各 4096 项；缺失、索引冲突、超界、解析失败及驱逐都有日志，不猜测无法观察到的客户端已有缓存。

--debug 下新增独立 JSONL：logs/world-collision.jsonl，8 MiB 轮转并保留两份备份，后台线程有界队列写入，不在网络/预测线程做文件 I/O。记录世界包排队、真实 ACK ID、前后 runtimeId、分片位置/已知状态、解析失败、客户端挖块动作、碰撞输入/解算位移、原/客户端/预测 AABB、pending ACK 和命中形状/网络状态。Phase 命中另带附近格子、48 条上下文；日志故障不影响原告警，溢出计数可见并优先保留 Phase。

回归包含真实出站批次、ByteBuf 释放/复用、分片替换、本地 revision、缓存延迟与更新覆盖、维度回切、未知区域，以及 Phase 告警在有/无/失败日志输出下保持一致。56 组固定原生 SubChunkPacket.encode 字节经对应 SDK codec 解码后再验证 ACK，覆盖七协议、8/9 格式、缓存开关及成功/全空气。来源及校验见 tools/world-trace-reference-sha256.json；备份由主目录 navro-proxy-world-trace-current.txt 指向。

验证完成：全量 `test installDist --rerun-tasks` 333 项通过（0 失败/错误/跳过）；2193 协议分别探测 19132 代理与 19133 后端，均收到 NetworkSettingsPacket。已更新本地测试代理。后续仅分析日志，不自动修改挖方块处理或 Phase 判定。

## 第二十三轮：实时挖块预测调用链

本轮定位到的直接差异是：原版 NavroPlayer 构造 ServerBreakBlockValidator，AuthInputPackets 在输入处理/碰撞前调用；代理已有移植校验器，但仅在单测中创建。日志中的两次首次 Phase 均紧随 BLOCK_PREDICT_DESTROY、早于后端空气更新到达，不能只依靠出站空气更新后的重同步解决。本轮将既有校验器接到已通过包检查、非豁免的 detect 会话输入路径，在 player.apply/LivePrediction 之前执行；有效预测破坏在当帧变为空气，原有 revision/ACK 确认规则保持。无挖块动作、检查拒绝、inspect 和豁免路径不引入挖块预测；过滤结果沿既有 AuthInput 重编码转发。Phase 源码和挖块校验器主体均不改。

纠正分支新增独立 BackendBlockSnapshots，仅保存后端已经发送的地形，用于 BlockCorrectionSender 恢复两个数据层。补偿碰撞绝不读取该快照，本地预测/代理纠正也不写入此来源；分片未知或切换代次未 ACK 时不伪造纠正状态。同维度重置和跨维度切换使用 generation 隔离，原生服务端未发送的世界状态无法从代理取得。本轮不以客户端坐标替代合法移动位置，不修改 ignoreDesyncedBlock/Phase 阈值或重同步策略，其他未接入原生上下文仍保留原范围。

新增 block_break_validated/block_break_correction 日志，能把输入 tick、过滤后动作、预测 revision、纠正来源和两个 layer ID 与原有 world_ack/collision/phase 记录串联。实时回归覆盖脚下破坏后下落、未挖实体方块的 Phase 仍触发、无 START 完成被拒、旧区块 ACK 不覆盖较新预测、后端拒绝在 ACK 后恢复、纠正来源与碰撞世界分离、坏输入/豁免/inspect、未知分片、操作 epoch 与同维度 reset generation。来源及校验见 tools/live-dig-reference-sha256.json。

本轮全量 `test installDist --rerun-tasks`：345 项全部通过（0 失败/错误/跳过）。已更新本地测试代理，后续使用新增挖块预测日志核验实际复现；不把离线回归通过当作线上复现已验证。

## 第二十四轮：源码文件名与职责归位

按用户要求，仅整理现有文件名、目录和职责：121 个已迁移文件与 Navro 同目录同名，66 个代理专有文件逐项说明职责并保留名称。核心命名空间仍区分代理与原版，不伪造不存在的原生上下文或未迁移检查。完整旧→新类型映射、代理保留理由和原文件列表见 NAVRO_STRUCTURE.md；历史轮次 SHA 清单保持原路径，不改写旧审计。

prediction/live 中的 PredictionRunner、物理规则、引擎、ticker、脚手架规则及数据视图分别归入原版 prediction、engine、ticker、collision、data、compensated/world 目录。Velocity→Vector、PredictionBlock→NavroBlockState、PredictionWorld→CompensatedWorldImpl、BlockPosition→Mutable，ProtocolLiquidPhysics→LiquidPhysics、ProtocolFluidState→FluidState，FlagTracker 回到 player/data/tracker。原 live Collider/LiveFluidMovement 桥接方法合并到 Collider/FluidMovement，候选上下文中的 PredictionData/FluidState 提取为 data/input/PredictionData 和 prediction/fluid/FluidPredictionState。

LivePrediction 的输入编排归 AuthInputPackets，动作转换归 LegacyAuthInputPackets，后端 SetEntityMotion 及 ACK 候选归 PlayerVelocityPackets。SessionState 负责装配、原始观察和纠正传输边界；挖块依然在输入与碰撞前执行，服务端地形/速度依然按原批次 ACK 提交。测试文件随职责归位，所有活跃引用已更新；旧类不保留空壳/别名，产物检查确认无旧 class 遗留。

23 个直接迁移类在归一化类型名/import/comment 后主体完全一致，22 个检查相关文件主体完全一致，16 份生产资源哈希不变。完整 345 项测试通过，最终 test/installDist 成功。此对应关系仅表示已实现部分的文件与职责归位，不表示 Navro 全部逻辑已经移植；原版尚未实现的检查、上下文和既有暂缓项目维持原范围。来源及审计见 tools/structure-reference-sha256.json。

## 第二十五轮：输入收尾、转发边界及重同步倒计时

补齐原版 PostAuthInputPackets 的可观察输入收尾流程：实体姿态结束、出生前输入取消、历史冻结、每帧激流/库存动作/DepthStrider 标记清理、已解算坐标加原 packet-space offset 回写、非移动豁免时的碰撞位/delta 回写。先处理真实传送确认再执行硬传送转发门，包含本帧预测检查刚触发纠正的情况，防止异常原始坐标在纠正后继续进入后端。原本已被 PacketListener 拒绝的 AuthInput 也执行最高优先级收尾，但不会把其原始移动应用到状态；豁免和 inspect 分支保留各自边界。

方块重同步计数在普通输入收尾时按原版递减，传送期间暂停；不改变任何设置计数的规则，不启用先前暂缓的 ignoreDesyncedBlock 提前空气更新。原版默认 player-rewind-history-size-ticks=20；新增 TickData/AuthInput 历史按排序键清理，传送分支按原版暂不 poll。快照在坐标回写和每帧清理前捕获，组合既有 WireTickSnapshot 的原始观察、actor flags、属性、姿态/AABB、流体/碰撞数据与物品使用/激流状态，不伪造原生数字 flag ID；输入包仅复制原版 TickData.snapshotPacket 的移动字段，不保留动作载荷。维度与传送确认沿各自历史清除边界清理新表；独立 Respawn 握手保留历史（见 2026-11-15 服务端移动包对齐）。

288 组冻结对照直接编译执行当前原版 PostAuthInputPackets、TickData 源码，以及原样提取的 correctInputData；事件/数据包与 Vec3 使用固定原生 JAR/原版编译类，session、sampler 和快照提供者为显式测试桩。覆盖普通/软/硬传送、出生/豁免、已取消输入、三种 offset、碰撞/delta、每帧复位与快照先于改写；该夹具不独立模拟流体/碰撞物理。另有实际代理输入生命周期、五 tick 倒计时恢复检测、同帧纠正拦截、确认恢复、已拒绝输入、排序历史/传送暂停等回归。

本轮仍不猜测不可见的 Nukkit Player.riding 真值，不接入尚未实现的完整软纠正 replay/history restore；实时放置完整上下文及事务取消接线继续保留原范围。检查实现、阈值、告警和 16 份生产资源未改。完整来源、测试及部署见 tools/post-input-reference-sha256.json；修改前备份由主目录 navro-proxy-post-input-current.txt 指向。

本轮最终验证：357 项全部通过（0 失败/错误/跳过）；test/installDist 成功。旧 Timer 集成夹具补齐真实已出生地形与 packet-space 输入，原有计时断言和算法未改。

## 第二十六轮：metadata 与移动状态同步

线上 ACK 应用接入既有 MovementMetadata/FlagTracker 原版移植，物品使用、输入动作、姿态与预测共用 WirePlayerState 的 native-bit flags。Cloudburst 将 FLAGS 与 FLAGS_2 映射到同一个 EnumMap，因此以当前协议 flag 身份对应的键范围判断哪些 word 确实存在；不能根据两个 metadata key 或 SDK enum ordinal 猜测。只替换收到的 0–63 / 64–127 范围，保留另一组状态、CAN_CLIMB 的原版所有权、ACTION→内部 USING_ITEM 握手和补偿胸甲滑翔门。metadata 排队时复制 mutable flags，历史快照与恢复同样隔离引用。

疾跑按通过前向验证的 START/STOP 边沿更新 client/server/flag 与原属性修饰，不再被每包 SPRINTING 连续位覆盖。metadata ACK 仅更新 serverSprinting，不倒写 clientSprinting 或本帧冻结的 predictionSprinting/predictionSwimming。游泳动作、流体解析与 metadata 同步 clientSwimming 和 bit 57；prediction setter 只更新冻结字段。潜行、爬行、滑翔从同一 flags 读取；开始滑翔与基础 metadata ACK 均使用应用时已补偿的胸甲槽 1。Wire 的独立姿态实现删除，输入尺寸请求、消费与收尾共用原版 FluidMovement；原版连续对照发现并消除了动作后重复 pose update 多留 pending request 的问题。flag6 改读真实数字 bit 6。

新增 4096 次连续原版状态转换，对照直接编译运行当前 LegacyAuthInputPackets、NavroPlayer、PlayerData、FlagTracker、FluidMovement 源码及固定原生 JAR。逐步比较输入过滤、flags、client/server/prediction 分域、飞行、属性 float bits、尺寸、AABB 与姿态请求所有权。夹具刻意隔离服务端 tick 和流体/碰撞 travel，不声称独立验证这些物理；已有 8192 次 metadata/pose/item-use 对照继续执行。另测全部已支持协议 codec 的两组 flags 编解码、真实编码批次的 closing ACK、快速动作/旧 metadata 回声、装备门、干地游泳拒绝、快照/待 ACK 包的引用隔离。

日志增加 movement_metadata_queued / movement_metadata_applied，记录 revision、实际 dispatch ACK ID、word presence、收到的 flags 与应用前后运动分域；碰撞/Phase 记录同步带 movementState。原生 sampler 仅保留诊断边界，不伪造原生服务器状态。检查算法、阈值、告警及 16 份生产资源保持原样；ignoreDesyncedBlock 的提前空气更新与完整软纠正回放仍维持此前范围。来源审计见 tools/movement-state-reference-sha256.json；修改前备份由主目录 navro-proxy-state-sync-current.txt 指向。

本轮最终验证：全量 `test installDist --rerun-tasks` 374 项全部通过（0 失败/错误/跳过），含新增 4096 次原版连续状态转换对照。

本地测试代理已切换到本轮构建（PID 4302，JAR SHA-256 `ea3fefd627fd60689c25d42f2842646b533179e199e5be068fa32d1c278de1fc`）。2168 协议探测代理 19132 与 PMMP 19133 均收到 NetworkSettingsPacket；该探测只确认握手，不替代玩家移动实测。PMMP 进程与后端启动配置未变。
## 第二十七轮：疾跑属性 ACK 与快速启停

用户复现反复 START/STOP_SPRINTING 后的预测误判。本轮继续核对发现的明确原版差异：Navro 的 PlayerData.setAttribute 在收到所属批次 ACK 后，先 clearModifiers，再 setBaseValue(defaultValue)，最后 setValue(value)。代理原先漏掉清理步骤；残留的本地疾跑修饰会让后续 STOP 删除修饰时重算基值，覆盖刚确认的服务端当前速度。现按原版顺序清理，后端属性仍等待原批次 ACK，START/STOP 接受条件、metadata 域及当前帧冻结规则保持原逻辑。

UpdateAttributesPacket 的浅 clone 共享可变列表，改为复制 runtime ID、tick 和列表；所消费的 AttributeData 名称、基值及当前值字段本身不可变。原包列表重用不会修改排队快照。其他实体的属性不会清除本玩家修饰，inspect 路径仍直接观察属性。未引入后端私有属性读取或核心运行时依赖。

日志新增 sprint_action、movement_attributes_queued、movement_attributes_applied。movementState 包含 movementAttribute 的 base/value/modifiers；动作包含前后状态及规范化输入，输入观察补充 motion/rawMoveVector/rotation。读取数值时使用属性 clone，避免诊断清除原状态的 dirty 位；修饰快照不可变，写入故障不改变动作或属性结果。

新增回归覆盖疾跑属性 ACK 后取消、旧停止属性 ACK 与再次启动交错、包列表/实体 ID/tick 复用、其他实体、只读诊断和故障输出。真实 SDK 编码、批次压缩及 NetworkStackLatency 回复验证原生中途 marker 不提前提交，关闭 marker 才清理修饰，下一批停止属性继续等自己的 ACK。离线工具重新编译原版 PlayerData、AttributeInstance 和 FlagTracker，执行 4096 次动作、属性排队、metadata 疾跑域、FIFO ACK 与预测冻结转换，逐项比较速度浮点位、基值、修饰数量及各疾跑状态。该对照直接验证原版状态方法和属性算术；FIFO 为显式测试调度，真实运输边界由编码批次回归另行验证。

旧实测日志缺少逐包属性值，因此不能仅凭约 0.0294 的偏差证明所有误报都来自这一处差异。此次修复后的真实客户端快速启停效果仍须新日志确认；离线和握手验证不替代实际进服验证。源码备份由主目录 navro-proxy-sprint-chain-current.txt 指向，来源和结果审计见 tools/sprint-attribute-reference-sha256.json。


最终验证：全量 test installDist --rerun-tasks 成功，382 项测试全部通过（0 失败/错误/跳过），4096 次新增原版状态对照一致，22 个检查相关文件及 16 份生产资源哈希不变。已部署修复版测试代理 /data/data/com.termux/files/home/navro-proxy-backend-test/proxy-dist-sprint-chain-20261114-045950；原有 PMMP 1.26.40 后端进程原先已退出，沿原配置恢复。19132 代理与 19133 后端均通过协议 2168 的真实 RakNet/NetworkSettings 握手。当前尚无修复后的真实客户端快速疾跑启停样本。

## 第二十八轮：药水效果原生注册与 ACK 生命周期

继续对照当前 Navro 的 PlayerEffectPackets、PlayerData.addEffect/removeEffect、StatusEffect.tick 和 NavroPlayer.tick。此前 detect 分支直接向 map 保存任意 wire ID，且在 ACK 应用时再次检查当时的 runtime ID；原版则先在收包阶段过滤实体，再冻结 event/effect/amplifier/duration，ACK 时只调用 activeEffects 的状态入口。现将完整分支归回同名 PlayerEffectPackets，保留原过滤与提交时点、ADD/MODIFY/REMOVE/NONE 区别以及抓取原始字段的顺序。

PlayerData.addEffect 原先调用 Effect.getEffect，返回 null 时不创建状态。新增离线导出的原生注册 ID 表，直接遍历固定核心实际 registry 并调用 getEffect 生成；没有按连续区间猜测有效性。当前核心共 37 个注册 ID（包括 0 和 36），未知 ID 不写入预测效果状态；部署不引入 Nukkit 类或依赖。该表固定于本项目已经使用的原生核心版本，不声称自动适配任意后端私有注册效果。

原有负 duration / Integer.MAX_VALUE 转为 Integer.MAX_VALUE、其他 duration 加一的逻辑继续保留。StatusEffect.tick 仍按原版在 duration > 0 时减一、减到 0 后移除；没有把 MAX 时长改为永不递减。效果 tick 位于通过输入入口、已出生并且非豁免的生命周期中；修正此前豁免玩家仍推进效果倒计时的差异。Speed/Slowness 效果包只更改 activeEffects，不计算速度、不添加或删除冲刺修饰；移动速度仍由自身 UpdateAttributesPacket 的 ACK 提交。inspect 继续保存原始观察值。

离线工具重新编译当前原版 PlayerEffectPackets、NavroPlayer、PlayerData、StatusEffect、ItemUseTracker、EntityBatchEncoder、LatencyUtil，使用原版真实效果处理、编码批次、ACK 队列和 NavroPlayer.tick 生成 1536 次连续状态。世界为空、物品未使用、viewer 由实际 manager 的测试条目提供；不模拟移动物理或完整登录。代理对照在 SessionState 的真实后端入口提交效果，ACK 调度由夹具显式提供，tick 对照只覆盖效果生命周期。另以真实输入入口验证出生/豁免/拒绝/到期的时点，并用真实 SDK 编码批次和 NSL 回复验证 closing marker 归属。

不以本轮状态对照声称真实客户端药水移动无误判。完整软纠正 replay/history restore、放置实时完整上下文与事务取消、实体 MovePlayer 的原生服务器坐标判别等此前明确缺口未在本轮伪造实现。修改前源码和资源备份由主目录 navro-proxy-player-effects-current.txt 指向。

本轮最终验证：全量 `test installDist --offline` 成功，392 项测试全部通过（0 失败/错误/跳过），含 1536 次当前原版连续效果状态对照及 2168/2193 编码批次 ACK 验证。22 个既有检查文件与 16 份既有生产资源哈希保持不变；新增原生效果注册表已进入安装包。无人在线时已切换测试代理至 `/data/data/com.termux/files/home/navro-proxy-backend-test/proxy-dist-player-effects-20261114-060511`，PID 16599，JAR SHA-256 `d9fb3ef0ca1159792a51cabd0d8cd69a3b0781d4264d53c517087684ed61b00b`。19132 代理和 19133 后端均通过协议 2168 / 1.26.40 的真实 RakNet/NetworkSettings 握手；PMMP 进程和此前认证运行配置保持原样。来源与验证记录见 `tools/player-effects-reference-sha256.json`。

## 第二十九轮：骑乘链接、实体 ID 索引与所属批次 ACK

原版 VehiclePackets 与 ServerEntityPackets 的骑乘相关路径继续归位。此前代理收到 SetEntityLink 就立即改 from→to 的关系表；现通过同名 VehiclePackets 绑定所属真实出站批次，ACK 后以 vehicleUniqueId / riderUniqueId 解析原生唯一 ID 索引，更新同名 VehicleData 或其他缓存实体的 inVehicle。主会话、输入/碰撞预测、眼高偏移和输入收尾共用 WirePlayerState.vehicleData；移除独立的双向关系状态机，vehicleByRider 只保留本玩家状态的兼容视图。

原版通过骑手值直接比较本玩家 runtimeEntityId，其他骑手才查 unique→runtime；本玩家实际 unique ID 与 runtime ID 不同时仍保留此原分支。其他骑手在车辆缺失时也可更新 inVehicle；本玩家必须在 ACK 应用时查到车辆缓存，才清理 queuedTeleports 并处理移除/新建状态。重复挂载仍按原版创建新 VehicleData，控制字段保持原默认值。客户端 Interact 的 VEHICLE_EXIT 仅在 target 等于本玩家 runtime ID 时立即清理 vehicleData，位于普通收包检查之前；该路径保留传送队列。

新增与原版相同的 uniqueIdToRuntimeId 索引，由实体生成入口直接写入、RemoveEntity ACK 根据索引移除，不扫描当前实体的 uniqueId 作替代。保留相同 unique ID 的最后绑定和相同 runtime ID 重用后的旧别名行为。RemoveEntity 先按原版直接用 packet.eid 比较 vehicleRuntimeId，再执行 unique ID 缓存移除；这两种 ID 值不相同时的原结果也保留。Respawn 握手保留实体缓存和此索引（见 2026-11-15 服务端移动包对齐）。

VehiclePackets 的回调按原版在 ACK 时读取其持有的包字段、解析当时的索引；没有把这一分支改成提前冻结字段或提前解析实体。挂载状态也按原版参与本玩家 metadata 的收包过滤：挂载时不登记移动 metadata 或物品使用 metadata 回调；挂载前已经排队的 metadata 仍在其 ACK 提交。其他实体的 metadata 更新照常处理。

离线工具直接编译当前原版 VehiclePackets、ServerEntityPackets、CompensatedWorld/Impl、NavroPlayer、VehicleData、EntityBatchEncoder 与 LatencyUtil。真实原生实体生成/映射入口、链接/退出监听器、实体移除与原生编码批次 ACK 生成 1024 次连续状态参考；另从固定核心编码生成 32 组链接字节，覆盖四个协议和 signed unique ID 边界。夹具只准备实体包、viewer 和待处理传送，不实现第二套骑乘决策或车辆物理。代理参考走 SessionState 生产入口，ACK 调度作为显式外部条件；SDK 编码批次与 NetworkStackLatency 回复由生命周期回归另行验证。

已有 PostAuthInput 原版对照夹具的链接输入调整为协议实际的 vehicle→rider 次序，保持原预期位置、offset、标志与计数断言。状态/字节对照只证明这里声明的路径，不代表完整客户端上下船实测或车辆移动预测。原版尚未实现的车辆 travel、读取 Nukkit session.riding 的提前下车转换、远端 MovePlayer 的 live server 坐标判别，以及完整软纠正回放继续保持明确缺口。SDK 未暴露的未知链接类型也不在此次已验证的类型范围内。

修改前备份由主目录 navro-proxy-vehicle-links-current.txt 指向。来源及验证记录见 tools/vehicle-links-reference-sha256.json。

本轮最终验证：全量 `test installDist --offline` 成功，404 项测试全部通过（0 失败/错误/跳过），含 1024 次当前原版连续状态对照、32 组原生链接字节和真实 SDK 编码批次 closing ACK 验证。22 个既有检查文件与 17 份生产资源哈希保持一致。无人在线时已部署 `/data/data/com.termux/files/home/navro-proxy-backend-test/proxy-dist-vehicle-links-20261114-070401`，PID 32239，JAR SHA-256 `d712d53ec181f0b2f900f778e674f8be55f875dcf72e50e1d54b520b8b73a018`。代理 19132、PMMP 19133 均通过协议 2168 / 1.26.40 的真实 RakNet/NetworkSettings 握手，后端进程和认证配置保持原样；尚无新版真实客户端上下船记录。

## 第三十轮：放置解码桥与原版距离前置规则

本轮沿实际放置调用链核对，确认代理实时 PredictionItemUse 只处理 CLICK_AIR 与 RELEASE，CLICK_BLOCK 尚未绑定 ClickBlockContext；源码已有完整 handleClickBlock 及放置提交方法并不表示线上已经执行。Navro 原版 RotationPlace 会在库存预测前保存世界/射线信息、MONITOR 时暂扣成功事务、等待 N+1 交互射线后调用服务器继续处理。此次没有绕开该完整阶段，仅接入预测/扣减后宣称完整放置对齐；实时暂扣/转发与权威槽位回写仍需继续迁入。

先补齐明确的底层缺口：固定 NativeItemDecoder 目录共 3349 个实际构造器，已有放置表没有覆盖其中 399 个（数值回退构造器及字符串物品）。离线工具逐个执行固定原生核心 Item.get 或 Item.fromString/setDamage、canBePlaced、getBlockUnsafe/getBlockId、Block.get(mappedId,0)，遍历 399 × 65536 = 26148864 组原生输入，生成无损额外表 item-placement-decoder-extra.bin.gz；既有表不改，不根据名称、wire runtime ID 或碰撞形状填默认值。未导出的自定义构造器继续明确失败，不伪装为不可放置或普通方块。

NativeItemDecoder.placement 直接从已解码视图中保留的 factory 和 rawDamage 查询构造器放置属性。不能使用最终 getId/getDamage 重新推断 Item.get 与 fromString/setDamage，也不能遗失 fixedDamage / Damage NBT / mv_origin 恢复造成的原始构造信息。原始 wire、其 NBT 与定义不修改，深拷贝保留构造器身份。显式 format 重载区分 INVENTORY/TRANSACTION/SLOT 解码。ItemTransactionValidator.handleBlockPlacement 新增接受实际 ItemData 的入口，经该桥后调用同一原版提交体；仍保留先默认状态预测、后观察回调、再按实际当前槽及游戏模式扣减的顺序。

同名 util/block/BlockReachUtil 迁入原版距离规则：两种属性名的存在性优先级、有限正属性门控、13/7 默认距离、三组可能眼高及 1.0E-4F 比较、单位方块最近点、double 算式先后、严格大于判断、脚手架/空物品排除以及原诊断格式均保留。距离规则当前是放置检查依赖，未注册半套 FarPlace/AirLiquidPlace/RotationPlace 或伪造权威库存重发，因此不声称已执行完整线上拒绝与纠正。

原生参考生成器直接使用固定核心 BinaryStream 的真实物品解码入口与当前原版 BlockReachUtil。70329 组解码/放置状态覆盖全部构造器、metadata 边界、三个协议和三种物品格式；普通 runtime 物品参考另覆盖全部已导出的 17 个协议；4096 组原版距离输出比较 double bits、判断和完整文本。实际放置提交入口另在两种调色板与生存/创造模式执行，检查预测状态、回调先于扣减、元数据/定义/NBT 保留。旧放置和点击分发原版参考继续运行；未使用伪造正常客户端轨迹作为依据。

缺口继续明确：完整实时 ClickBlockContext、相应放置检查及其服务端 tick/权威库存边界、RotationPlace 的 N+1 暂扣/恢复、嵌入输入事务到取消/转发的完整接线仍未完成。此轮不更改现有检测器算法、告警、阈值、颜色、排版或移动生产逻辑。修改前备份由主目录 navro-proxy-placement-bridge-current.txt 指向。

最终验证：全量 `test installDist --offline` 成功，409 项测试全部通过（0 失败/错误/跳过）。70329 组全部构造器解码对照、1989 组普通 wire 对照、4096 组原版距离输出、192 个实际提交场景，以及 399 个新增工厂全部 16 位 metadata 的原生 SHA 摘要均通过；旧 2950 工厂完整对照和既有放置/点击分发原版参考继续通过。22 个既有检查文件、17 份既有生产资源哈希不变。新增桥接及原生补充表已进入安装包，JAR SHA-256 `08d4ecf68b51b60913e56f13ce3eeca0244af47166a59e022edb4465dc85cefd`。本轮未启用尚未完整迁入的实时放置过滤，也未替换当前运行的骑乘对齐版代理；安装分发位于 build/install/navro-proxy。来源与验证审计见 tools/placement-bridge-reference-sha256.json。

## 第三十一轮：库存事务结果到真实取消/转发边界

继续核对库存事务发现此前两个明确接线差异：NORMAL 调用 handleNormal 后只记 anomaly，始终返回可转发；CLICK_AIR / RELEASE 调用已移植校验器但丢弃其 boolean。现通过 PlayerInventoryPackets.onTransactionReceive 在同一 SessionState 生产入口消费真实结果：已完整绑定的 NORMAL、非 CLICK_BLOCK 的 ITEM_USE、ITEM_RELEASE 及原版默认事务分支调用同一 ItemTransactionValidator.handle，REJECTED 设置真实返回 false，ACCEPTED 只提交一次。原来尾部的 handleNormal 只保留 inspect 观察路径，避免 detect 下重复扣减。原 SDK unset type 也沿既有 handle 的 native NORMAL 默认分支。

新增 UNHANDLED/ACCEPTED/REJECTED/EXCEPTION 明确区分完整入口的结果。CLICK_BLOCK 因上一轮记录的 ClickBlockContext / RotationPlace N+1 暂扣链路未完成，明确返回 UNHANDLED，不声称已验证/已预测；不以伪成功检查器接通放置。其它已有原生校验分支继续执行原实现，未改校验条件、物品定义规则、正常丢弃 source 识别、热栏范围、动作数组顺序兼容、空手判断、坐标距离与 sum-zero 分支、旧护甲交换或释放的候选优先级。

原版 PlayerInventoryPackets 使用 try/catch(Exception)，异常时不设置新的取消状态、不回滚已发生的物品使用/库存副作用。代理同一分支捕获 Exception 并记录 errorClass/error，维持进入库存处理时允许转发的状态；不是把异常当作验证通过，也不新增拒绝或回滚。诊断写入故障不改变转发结果。此入口不把玩家豁免当作跳过事务校验的条件，与原版库存监听器相同。独立普通包检查当前仍沿此前 SessionState 调度，不据此次库存边界对照声称 InventoryA/InteractA 或全部原生插件事件顺序已迁入。

离线重新编译当前原版 PlayerInventoryPackets、ItemTransactionValidator、CompensatedInventory、ItemUseTracker 和 ItemCache，直接执行原库存收包监听器，使用原生 wire writer/decoder 的真实物品，生成六个协议共 72 组事件取消、计数、AIR 哨兵、使用 ID/dirty/flags 对照。夹具的空 CheckHolder 显式排除未迁入的独立检查器，server-held 提供 AIR 作为释放条件，不模拟其决策。既有 1728 组原生 cache/bundle/drop 参考中的 576 组丢弃对照改为走 SessionState.receiveClient，比较生产转发 boolean、库存和缓存身份，预期仍来自既有原版实际执行产物。

现代请求/响应继续按已知原版边界处理：handle(ItemStackRequestPacket) 只在 openContainer 存在时设置 doingInventoryAction，不私自执行当前原调用链未调用的 BundleClickProcessor。InventoryResponseSync.capture 在发送时读取服务端完整物品/NBT，ACK 后按 epoch 和窗口身份提交；独立代理目前无法从 count-only ItemStackResponse 重建同一份数据，因此该同步尚未完成。不能把请求直接提交成成功库存，也不能仅凭 count 覆盖未知物品定义。完整现代请求重建、InventoryA/InteractA、放置完整事务、嵌入输入事务及其取消接线仍需继续。

修改前源码和资源备份由主目录 navro-proxy-inventory-transactions-current.txt 指向。检查阈值、告警原文、颜色和排版不在此次修改范围。

本轮最终验证：全量 `test installDist --offline` 成功，416 项测试全部通过（0 失败/错误/跳过）。35 项库存专项通过，包含 72 组当前原版监听器取消/状态参考，以及改走实际收包转发入口的 576 组原版丢弃参考。22 个既有检查文件及 18 份生产资源哈希不变。无人在线时已部署 `/data/data/com.termux/files/home/navro-proxy-backend-test/proxy-dist-inventory-transactions-20261114-075748`，PID 26860，JAR SHA-256 `0ec363d64acd49289e7e1bca2c5ea2b5b9b13eb570080b6c91517fa496ae7766`。此前放置桥也包含在本次安装包中，但未启用未完成的放置过滤。19132 与 19133 均通过协议 2168 / 1.26.40 的真实 RakNet/NetworkSettings 握手，后端进程与认证运行配置保持原样；新库存行为仍需真实客户端实测。来源和验证审计见 tools/inventory-transactions-reference-sha256.json。


## 上一会话改动重写（2026-11-14）

本节替代上一会话在第三十二至第三十四轮追加的实现说明与验证结论。范围依据会话 `01a1d948-ebf8-7c31-b8db-85f44d7a1260` 的文件写入操作确定；更早完成的 RotationPlace 算法、检测阈值、告警文本及移动预测不在重写范围。测试遵循 `/storage/emulated/0/skill/SKILL.md` 的 meaningful-testing 规则。

### 生产实现

- `SessionState.onClientPacketForwarded` 统一识别独立库存事务、现代请求及带事务的输入，转发后使已知后端槽位失效。独立事务还显式标记有效手持槽；只刷新手持槽不能恢复整份库存纠正资格。
- `BackendInventorySnapshots` 使用容器 ID/槽号组成的类型键管理失效状态，按槽号构造整包快照，保留独立于预测库存的后端物品与 NBT。新世界清空数据；重新打开动态窗口会丢弃该窗口旧快照和失效标记。成功响应的 count=0 且 stack ID=0 可以作为已知空槽。
- `InventoryResponseSync` 将请求登记、响应读取、身份待确认、空槽提交与窗口/世界失效分别实现。HOTBAR/INVENTORY 等别名按同一物理槽清理及覆盖；同包后来的非空未知结果撤销较早空槽提交。补偿库存仍等该响应批次 ACK；未知非空身份和矛盾字段不生成物品或 NBT。
- `NativeSelectionProperties` 与 `Box.fromNativeBounds` 保留原始六边界及 double→float 语义。反向边界仍为无效框，不能自动交换后送入放置捕获。
- `PlacementPipeline.block` 统一返回静态、门及连接方块的选择框，并将门邻居读取、四向邻居读取、未知数据处理分开。门保留固定 MOT 的 getFullDamage/门轴/上下读取行为，包括原版没有保留 open bit 的掩码；不擅自修正它。
- `NativeConnectedSelection` 明确普通栅栏、下界砖栅栏和薄板三种连接规则。读取顺序仍为北、南、西、东，短路顺序与固定核心一致；几何与 sturdy 使用独立原生事实。移除不可触发的本地 LevelException 占位异常；代理未知数据继续上抛为 MissingFact。

### 独立依据与实际修复

门、连接方块导出器只提供世界块条件，再执行固定 Nukkit 和原版 Navro getter。重新编译导出器并重放全部生产/参考资源，五份资源的解压内容均与既有文件一致，因此无需替换参考值。

发现并修复连接导出器的阶段条件残留：完整生成几何后 mask 留在 15，使后续逐邻居样本错误地保留另外三侧连接；rules-only 则从默认 mask=0 开始。现进入逐邻居阶段前显式设置 mask=0、mixed=false。完整生成和 rules-only 产物已相互比较且均匹配现有参考文件。属性导出仅将已知的 MissingWorld/LevelException 视为缺少世界条件，其他异常直接失败，避免隐藏工具错误。

固定原生依据覆盖 21 种门的 72828 行、连接方块的 2688 组几何及 1032192 行逐邻居样本；这些证明固定核心对齐范围，不证明真实客户端无误判。

### 测试契约

- 选择器组件测试执行完整生产选择器，预期来自原生核心输出；名称明确限定组件范围，不将连接资格决策命名为 utility。静态事实表解码/平移属于 utility，六边界预期从参考文件单独读取。
- 放置流程使用真实 `SessionState.receiveClient`：N+1 暂扣/转发、命中与拒绝、只预测扣减一次、未知邻居等待。补充未知 section 场景的无转发、无扣减、无告警断言。
- 库存流程使用真实 `SessionState.observeBackend` 与 ACK：整包旧槽位不能被重发、空槽只能在 ACK 后提交、容器别名的同包双向覆盖、矛盾字段不能确认空槽。未构造替代业务状态机。
- 隔离编译四种仍可调用的错误实现：整块选择框、仅失效手持槽、空槽提前提交、忽略非零 stack ID。正常实现均通过，错误实现均在预期业务结果断言失败。

### 启动工具

重写主目录 `start_navro_connected_selection.py`，使用验证摘要与实际 JAR 哈希关联安装包，移除固定 436 的计数要求。保留既有配置，识别记录的后端，超时只清理本次启动的子进程，原子写入运行记录；`--prepare` 可只准备安装分发并验证加密。

新增 `tools/LoginCryptoPreflight.java`，执行 discovery/OpenID 初始化、真实 EC 密钥生成并验证 ES384 握手 JWT 签名；`--start-proxy` 在同一 JVM 随后进入代理 main，确保加密初始化完成后才监听。已验证独立准备路径及同 JVM `--help` 路径，未对运行中的代理执行切换，也未宣称完整客户端登录已通过。

### 最终结果与限制

全量 `test installDist --offline` 配合既有外部 init（1024m、forkEvery=10）通过：438 项、98 类，0 失败/错误/跳过。默认累计测试 JVM 曾异常退出，该次不算通过。安装 JAR SHA-256：`21feab7221df0de44148320f16859854232494f9918493e44f75f9f678718ecc`。原生导出器的格式整理与启动工具不进入生产 JAR；核对生产选择器 class 与资源均来自本轮构建。

本轮重写未部署。墙、藤蔓及其他未迁入选择算法、后端未暴露的非空物品身份/NBT、真实客户端登录与连续放置/库存实测仍是缺口。历史运行 PID 不视为当前健康证据。源码、工具、参考资源审计摘要见 `tools/session-rewrite-sha256.json`；测试结果见 `build/session-rewrite-validation.json`。完整导出重放、变异失败证据与改动 diff 位于主目录 `navro-session-rewrite-20261114`。

## 墙与藤蔓：Navro 选择框与 sturdy 对齐（2026-11-14）

本轮沿 Navro `RotationPlace.capture` / `NavroBlockState.isFaceSturdy` 的实际调用链对齐：选择框来自固定核心 `Block.getBoundingBox`，sturdy 来自原版 Navro 方法；没有改用移动碰撞框，也没有按现代客户端外观重定义规则。新增 NativeWallVineSelection 与 PlacementPipeline 邻居适配，支持固定核心 19 个墙 ID 及普通藤蔓（106）；其余藤蔓类型原有静态表路径保持不变。

墙保留北/南/西/东读取顺序、原生 GLASS 优先分支、薄板/染色玻璃/墙类型判断、栅栏门轴向、楼梯相反朝向、打开的活板门朝向及 solid/transparent 短路。两端直线连接将横向厚度从 0.5 收窄到 0.375；高度仍为 1.5。邻居类别、朝向和开合逐 metadata 直接取原生 getter，不由名称推断。sturdy 覆盖全部 metadata × 16 连接组合。

普通藤蔓保留固定核心原方法的 metadata 分支：只处理原有 0x02/0x08/0x01，0x04 单独存在时仍产生无效边界，不“修正”为另一方向。只有归一化 damage=0 才读上方 solid；上方实心时取顶面薄框，否则保持原始无效边界。反向边界不归一化，交给原版 RotationPlace 的 invalid-outline 跳过分支。metadata 非零时不读取无关的上方未知 section。

离线工具只提供世界块条件并执行固定 Nukkit 和当前原版 Navro 实现，生产包不包含核心类。8603648 行独立参考覆盖：19 种墙全部 8192 metadata × 16 连接组合，逐四方向全部 2048 邻居 ID × 13 metadata 边界及自身三组 metadata；普通藤蔓全部 metadata × 两种上方条件，以及全部邻居 ID 和 metadata 样本。比较原生 ID/归一化 metadata、六边界 float 位和 Navro sturdy。生产事实另遍历全部 2048 × 8192 邻居原生类型与朝向，不在测试中复写连接决策。

真实 SessionState.receiveClient 回归验证墙的 N+1 暂扣、命中/拒绝、一次预测扣减、缺失邻居 section 无转发/无扣减/无告警，及吊顶藤蔓的底面命中后转发；PlacementPipeline 世界读取回归验证零 metadata 藤蔓等待上方 section、非零 attachment 不等待。输入是明确构造的合成业务场景，不冒充真实客户端录制。此前“未知墙”夹具改为真实未知目标 section，不将已支持墙继续当未知。

失效敏感性检查：隔离编译的整块墙框、忽略藤蔓上方、未知墙邻居当空气三个错误实现均仍可调用，但真实业务断言失败；正常实现通过。证据在 `~/navro-proxy-wall-vine-20261114/mutations`。测试条件曾修正两处：墙连接到本方块边缘；吊顶藤蔓事务 face=DOWN 且射线指向原生底面。未修改检测规则迁就测试。

最终 `test installDist --offline` 配合既有 1024m/forkEvery=10 外部 init 全量通过：444 项、100 类，0 失败/错误/跳过。既有 21 份生产资源哈希不变；原检查阈值、告警文本、颜色、库存及移动预测算法不变。安装包 SHA-256 `1bb0aa6ae682504c86c5901c82f6ea435173c3bd2ad761ca75d27f153dcfdff5`，新增选择器及生产事实已核对入包，37MB 测试参考未入包。

本轮未部署。自定义/未注册原生定义及未知邻居仍显式待确认，真实客户端连续放置、登录和误判验证仍待实测；这不是整体 Navro 移植完成。源码/原生来源/产物摘要见 tools/wall-vine-reference-sha256.json，验证结果 build/wall-vine-validation.json，修改前快照及 diff 在主目录 navro-proxy-wall-vine-20261114。

## 嵌入 AuthInput 事务进入 Navro 完整库存/放置分发（2026-11-14）

确认独立 `InventoryTransactionPacket` 会在真实 `SessionState.receiveClient` 入口进入 `ItemTransactionValidator.handle`，而带 `PERFORM_ITEM_INTERACTION` 的 `PlayerAuthInputPacket` 仅被 `RotationPlace` 捕获交互几何；输入预测与转发流程没有执行同一事务校验/点击方块分发。这使嵌入路径可能绕过服务端 held-item 身份检查、点击方块分支和同一次预测/扣减约束。

现于真实收包流水线中，在 `PlacementPipeline.begin` 已捕获原始输入与 RotationPlace 交互快照后、输入状态观察和运动预测前，把该 `ItemUseTransaction` 适配为同一输入拥有的 `InventoryTransactionPacket` 并调用 `PlacementPipeline.handleTransaction`。因此它复用当前原版 Navro `ItemTransactionValidator.handle`、完整 `handleClickBlock` 与已接入的 placement/N+1 分支；校验失败时输入包在观察/预测前被拒绝，既有旋转捕获由其 finish/取消边界清理。有效事务的原始 embedded payload 保留在 `PlayerAuthInputPacket`，按原输入发送；不再复制事务、再次调用独立 InventoryTransaction validator，或重复预测/扣减。

同期发现快照失效边界：`SessionState.onClientPacketForwarded` 已对嵌入输入使所有已知后端库存槽位待确认，却只为独立 `InventoryTransactionPacket` 标记选中热栏槽。完整槽位快照缺失时，嵌入事务的实际 hotbar slot 会漏掉待确认状态。现统一从 standalone transaction 或 embedded ItemUseTransaction 提取 hotbar slot；槽位有效时走相同待确认逻辑，防止纠正时使用过期身份。

实际 `SessionState.receiveClient` 回归覆盖两个可区分契约：物品身份不匹配的嵌入块交互，在运动 tick/快照与库存修改前被交易 validator 拒绝；有效的嵌入放置则执行原版预测一次、扣减一次、保留原输入事务/marker 以供转发，并在真实 `onClientPacketForwarded` 边界标记 hotbar correction pending。隔离编译的错误版本若跳过嵌入事务 validator，拒绝用例失败；若省略嵌入 hotbar 待确认，成功放置用例失败。预期来自当前 Navro `handleUse`/`handleClickBlock` 前置身份契约和已存在的独立事务/N+1 生产路径；输入/包夹具只准备条件，不实现校验或放置决定。

最终全量 `test installDist --offline` 使用既有 1024m/forkEvery=10 外部 init 成功：446 项、100 个测试类，0 失败/错误/跳过；placement 与库存事务专项另行通过。安装 JAR SHA-256 `a6e3dffac0e583414213ed4d69a025832dfe7b5d2666d260d5b05e2e509c1587`，结果见 `build/embedded-input-transaction-validation.json`。本轮未部署，未更改既有检测阈值、告警文本、颜色、排版或生产资源。当前 PMMP 后端记录为 stopped；BDS/代理进程保持各自已有试验状态。

修改文件与安装包 SHA-256 见 `tools/embedded-input-transaction-sha256.json`；更改前源码副本、diff 和两个业务入口变异证据位于主目录 `navro-embedded-transaction-evidence`。


## 服务端 MovePlayer / Respawn 与 BadPacketsD 放行（2026-11-15）

本轮以原版 Navro 的 `packets/input/AuthInputPackets.onPacketSend`、`packets/input/teleport/TeleportHandler.processTeleport`、`teleport/data/TeleportData.matchesConfirmation` 及 `listener/PacketListener.onPacketSent` 为依据。用户所述重生场景同时核对两种协议对象：Cloudburst `MovePlayerPacket.Mode.RESPAWN` 对应 Nukkit `MODE_RESET`（数值 1），与独立的 `RespawnPacket` 握手包不能混用。

本地非 HEAD_ROTATION 的 MovePlayer 继续沿原版队列处理：NORMAL 重编码为 TELEPORT，RESET/RESPAWN 与 TELEPORT 保留模式、坐标和 onGround。修复包到达即修改预测 onGround 的提前提交；落地状态由关闭批次 ACK 及后续 AuthInput 确认流程提交。RESET 保留原版近落点确认，不要求 HANDLE_TELEPORT；普通 TELEPORT 仍需明确确认。仅头部旋转和其他实体的 MovePlayer 不再覆盖本地服务端位置观测，亦不影响本地落地状态或传送队列。

删除 SessionState 与 WirePlayerState 中把 RespawnPacket 当位置纠正使用的旧处理。该握手不能立即移动模型、清空速度/预测历史、删除待确认传送或清空实体及 unique-ID 索引。原版 PacketListener 的死亡/复活状态继续等待各自批次 ACK；位置重置由 MovePlayer 确认、跨维度等各自的实际生产路径负责。

按用户明确要求，BadPacketsD 在尚未确认死亡时仍对 RespawnPacket / PlayerAction(RESPAWN) 计入违规并告警，但不再取消这两种请求。保留用户随后亲自编辑的 `fail()` 调用，告警不附加包类型/状态明细。此处是用户要求相对于原版 Navro 取消策略的显式调整；已确认死亡的请求继续不告警。

验证边界：新增 ServerMovementLifecycleTest 驱动真实 SessionState 后端观察、PacketListener 发送钩子、EntityBatchEncoder、Cloudburst 编解码、NetworkLatencyPackets ACK 和输入观察；没有运行完整 ProxySession 网络登录。覆盖三种本地移动模式的确认前/后状态、错误实体/仅头部移动、先 RESET 后重生握手的交错顺序及实体缓存保留。BadPacketsD 用例改从真实 SessionState.receiveClient 检查告警、可转发结果和后续观察。预期来自以上独立原版调用链与用户放行要求；合成包不作为真实客户端录制。碰撞测试的落地夹具改为直接准备落地条件，不再借用未经确认的 MovePlayer 副作用。

失效敏感性：生产修改前，真实旧实现分别在提前 onGround、重生清空 RESET、头部包修改本地观测、BadPacketsD 拒绝转发断言处失败。头部场景初版夹具错误地假设 StartGame 初始化 serverPosition 非空，已先修正夹具，再确认其在真实业务断言处失败；该空指针不算有效故障证据。原始失败结果与修正后的头部失败结果保存在本轮备份目录。

最终验证：修改后的移动/重生流程全量 `test installDist --offline`（既有 1024m、forkEvery=10 外部 init）通过：449 项、101 个类，0 失败/错误/跳过。全量测试期间用户亲自编辑 BadPacketsD，故该全量结果对应编辑前的已编译 class；随后完整保留用户 fail() 版本，调整测试不再要求包类型明细，并重新编译、运行 34 项相关专项（8 类，0 失败/错误/跳过）与 installDist 成功。最终安装 JAR SHA-256：`961a81f9fb36a84b8b92a611a385e29608490cc42b11ded34dbe8c1abd6dc15b`，三处修改后的生产 class 与最新编译输出逐字节一致，测试类未入包，BadPacketsD 源文件与用户版本逐字节一致。验证摘要见 `build/server-movement-validation.json`，源码与独立 Navro 来源摘要见 `tools/server-movement-reference-sha256.json`；本轮修改前快照、diff、失败与通过结果位于 `/data/data/com.termux/files/home/navro-server-movement-20261115-002830`。本轮已构建安装分发，未重启代理；真实客户端死亡/重生、入服和连续移动尚未实测。远端 MovePlayer 的服务器坐标判别仍是既有缺口。


## 重生首帧 Phase：区分握手就绪与实际生命值恢复（2026-11-15）

真实会话 `0efdbb39-3edb-49d6-94d3-5ef4c4c4dd19` 在 00:52:30 的 tick 2449 复现：重生首帧 wire Y=4.00001，普通移动解码减去 1.62001 后脚 Y=2.38，旧物理脚 Y=4。Phase 因进入 y=2..3 的泥土产生 offset=1.6249369，随后错误发起 rewind。该帧先于后端 MovePlayer/正生命值更新到达；前一已确认 minecraft:health=0，SERVER_READY 握手回复早于实际复活。

Nukkit RespawnProcessor 仅在死亡玩家 CLIENT_READY 请求后回复当前脚部坐标与 SERVER_READY，没有恢复生命值或执行实际重生；后续 PlayerAction(RESPAWN) 才进入 Player.respawn，发送传送和生命值恢复。此前 PacketListener 为兼容 Navro，在 SERVER_READY ACK 时清除 acknowledgedDead；用这一握手标志恢复移动会把尚未完成复活的脚部回显送进正常碰撞预测。

新增独立 awaitingRespawnHealth：确认死亡/零生命值时暂停移动；SERVER_READY 继续保持原有 BadPacketsD/NegativeTimer 握手语义，但不能解除移动暂停。只有本地 SetHealth 或 minecraft:health 的正值更新经自身 ACK 确认才解除；后端移动包继续沿既有 ACK/输入确认路径处理。等待期间不运行 travel/Phase，不写入 AuthInput rewind 历史，也不把原始重生回显重写成旧坐标后转发。包检查、客户端 tick、ACK 仍正常推进。Phase 的几何算法、阈值和告警未修改，用户编辑的 BadPacketsD 保持原样。诊断 movementState 增加握手死亡状态与等待生命值状态，便于实际复测区分时序。

回归 RespawnMovementLifecycleTest 从真实记录提取 tick 2416/2449 的输入事实与附近地形，执行真实 SessionState.receiveClient、预测器、Phase、PacketListener 和 ACK 回调。此测试重建生命周期包条件，不声称完整原始网络录制或连续行走重放。修复前精确复现同一 Phase、offset=1.6249369 与同一泥土 AABB；修复后该回显无预测/告警/纠正、不进入历史且不改写转发。随后构造的传送与正生命值 ACK 场景验证恢复预测；复活后故意降低 wire Y 的非法输入仍触发真实 Phase，防止以永久停用检查使测试通过。夹具的模拟 ACK 使用与 Timer 相同的外部时钟；初版混用系统时间的恢复场景失败不作为生产缺陷证据。

最终全量 `test installDist --offline` 使用既有外部 init 通过：450 项、102 类，0 失败/错误/跳过。安装 JAR SHA-256 `049aa0510a605dc1fc8fc13147d3f02f047626f010eefbb75668fa27436721ab`，五处修改后的生产 class 与编译输出一致，测试类/录制事实不入生产包。用户 BadPacketsD SHA-256 保持 `6c96291ddbd6eb4ee0b2b60db72a37c846f0b51f64de5b09cc289b421a58ebe3`。已重启代理 PID 24854，Nukkit PID 17740 保持运行；代理同 JVM 登录加密初始化通过，19132/19133 的协议 2168 NetworkSettingsPacket 握手均通过。实际客户端修复后重生待重新连接实测。验证摘要 `build/respawn-phase-validation.json`，来源与文件哈希 `tools/respawn-phase-reference-sha256.json`；原始误报证据、前后 diff、失败/通过报告和部署记录在 `/data/data/com.termux/files/home/navro-respawn-phase-20261115-005731`。


## 游泳姿态与液体采样进入真实预测阶段（2026-11-15）

本轮核对当前原版 Navro 的 LegacyAuthInputPackets、AuthInputPackets、PredictionRunner、FluidMovement、FluidPredictionState、NavroPlayer 移动元数据、LiquidPhysics 及水/岩浆引擎。既有 movement-metadata 与 prediction 参考清单中的原版源码哈希均与当前源码一致，保留其独立输出作参考。

确认代理此前在 WirePlayerState.apply 每次收输入时直接采样液体、解算游泳并推进 swimAmount，而原版在实际 PredictionRunner.run 中，碰撞/closest-space 位置准备完成后才调用 FluidMovement.prepare。此前加载免预测、等待传送确认、重生等待生命值等输入仍推进液体运动历史；riptideWasInWater 也提前读到了本帧重新采样后的值。

DETECT 的 SessionState 现只在输入阶段捕获原姿态/AABB、处理动作并冻结动作后的预测旗标；FluidMovement.prepare 在原预测阶段调用 WirePlayerState.prepareFluidInput，使用本输入动作前尺寸/AABB、输入前游泳/爬行旗标，以及已经完成位置准备的脚部坐标，推进一次 blend、采样身体与呼吸点、解算游泳、更新客户端/预测游泳标志及姿态，再载入预测上下文。INSPECT 和独立 WirePlayerState 观察入口保留原采样接口。泳姿元数据的 closing ACK、显式高度/宽度/缩放、pending/ready 尺寸请求、物理高度与逻辑眼高分离继续沿原版流程。

修复预测上下文的 updatePose 桥仅 refreshPoseBoundingBox 的缺口，改为执行真实 FluidMovement 姿态决策；激流设置 SPIN_ATTACK 后，水平姿态和 0.42 眼高由该路径同步。激流 sampleTravelLiquids 同步更新持久流体采样及 waterTravel，再加载当前预测；该入口不重新解算游泳或推进 swimAmount。头部源水物理水面、液体流向/推力、水与岩浆选择优先级和各引擎公式保持原版。

新增 SwimmingPosePipelineTest 从 SessionState.observeClient 的真实检查后输入边界运行全部姿态/预测/碰撞流程，包与地形仅提供前提，不复制状态机。场景为显式构造的协议条件，不声称真实客户端连续游泳轨迹。旧实现已在加载输入仍采水、待确认传送仍推进 blend 两处业务断言失败；新实现检查恢复预测时才推进一次、传送确认清运动历史、游泳高度 ACK 后 STOP 使用短输入前 AABB 采水并恢复站立、头部源水物理水面及水/岩浆共存优先级。充能三叉戟释放走真实库存事务及预测入口，检查激流水平姿态与逻辑眼高，保留原版 live player 在权威尺寸到达前的物理高度策略。阈值、容忍和用户修改的 BadPacketsD 均未调整。

最终验证：83 项相关专项通过；全量 `test installDist --offline`（既有 1024m、forkEvery=10 外部 init）通过：455 项、103 类，0 失败/错误/跳过。全量完成后仅增强激流测试的“已有游泳”前提，生产源码哈希保持一致，再跑 5 项游泳场景全部通过，既有游泳 blend 在激流重采样后仍只为 0.1。JAR SHA-256 `22dbd4e3af1c024314fb0d93955fc8ed7e09a7d04f20aaa1dda43456b4b3bedc`；四处修改的生产 class 与编译输出一致，测试未入包。用户 BadPacketsD 与之前重生修复保留。已部署并重启代理 PID 5028，后端 Nukkit PID 17740 保持运行；同 JVM 登录加密初始化和两端协议 2168 NetworkSettingsPacket 握手均通过。真实客户端连续游泳、入水/出水与姿态变化仍待复测，未宣称所有液体移动无误判。摘要 `build/swimming-pose-validation.json`，来源与哈希 `tools/swimming-pose-reference-sha256.json`；前后 diff、失败/通过结果及部署记录在 `/data/data/com.termux/files/home/navro-swimming-pose-20261115-012159`。


## 鞘翅逐帧推进与 BedrockCollision 修正层（2026-11-15）

### 蜂蜜块及原版碰撞覆盖

此前代理主要走 Geyser 离线底表，仅独立接入竹子/滴水石、活塞、潜影盒及人工底面等分支，原 BedrockCollision.getCollisionBox 覆盖层并未完整进入实际查询。已确认蜂蜜块底表为 (1/16,0,1/16)..(15/16,15/16,15/16)，而当前原版 Navro HONEY_SHAPE 为 (1/16,0,1/16)..(15/16,1,15/16)。差异会让蜂蜜块顶面碰撞低 1/16。

新增 proxy/collision/BedrockCollision。tools/NavroBedrockCollisionExporter 直接重新编译并执行当前原版 BedrockCollision，遍历固定核心 844/944/975/1001/2168/2193 六份调色板，每份记录 2574 个原版覆盖/上下文分支，生成六份 bedrock-collisions-*.bin.gz。形状按精确网络状态身份绑定，支持 runtime/hash 与复用相同状态的显式调色板，不因自定义 ID 猜形状。已独立重放导出，六份压缩文件逐字节一致。生产包不包含 Nukkit 核心或原版插件依赖。

静态修正涵盖原函数返回的蜂蜜、床、仙人掌、讲台、海灵核心、灵魂沙、附魔台、酿造台、耕地/路径、阳光传感器、蛋糕、海龟蛋、龙蛋、锁链/末地烛/避雷针/紫水晶朝向、幽匿方块、饰纹陶罐、大型垂滴叶、钟、门框、梯子、活板门、箱子、炼药锅、栅栏门、灯笼、睡莲、压力板及原版穿透类别等。门保留实时上下半状态读取和原 0.1825 厚度；脚手架顶部仅在玩家已位于顶部且未下穿时出现；细雪按当前位置、下降输入、fallDistance 和补偿皮靴决定空/0.9/整块形状。竹子/滴水石继续走已对齐坐标偏移，活塞/潜影盒保持既有动态 block-entity 路径。普通碰撞、movement 查询和支撑 cell fragments 都在底表之前应用覆盖。

通过真实世界查询与 Collider.collideAndUpdateMove 验证蜂蜜顶面：从 65.5 下落 1 格在 65 停止，支持片段也为该高度。另核对六种调色板与 2225 别名的静态样本。tools/NavroDoorCollisionFixture 只提供原始调色板和邻居状态，直接运行原版门算法生成 21504 组参考，逐组通过实际 CompensatedWorld cell 查询比较六边界；细雪和脚手架另跑实际玩家/装备条件。旧版已在蜂蜜顶面业务断言失败。

范围边界：此轮补齐的是 BedrockCollision 覆盖层。原 DynamicCollision 中栅栏/玻璃板/楼梯/墙/紫颂植物的邻居重写仍未全部移植；那些底表条目不能据本轮宣称已完整动态对齐。未知调色板/自定义状态仍保留未知，不以整块替代。

### 鞘翅输入历史、物理推进与检查

当前滑翔引擎逐运算与原版一致，未替换重力、升力、俯冲/转向、烟花混合或阻力公式。确认推进差异位于上下文：PredictionContext.begin 以“上次预测”的 yaw/pitch 作为 prevYaw/prevPitch；等待传送时 begin 不执行，恢复后丢失期间输入的旋转。原 LegacyAuthInputPackets 在每条实际观察输入更新旋转后才选择是否执行预测。现 WirePlayerState 持有当前/上一输入旋转，StartGame 初始化两者，WireTickSnapshot 保存恢复它，预测从该输入历史读取，避免等待/确认帧被跳过。

tools/NavroGlideSequenceFixture 直接执行重新编译的原版 GlidingPredictionEngine，生成 48 个连续构造输入（含 5 个传送等待/确认帧）、烟花开始/结束及跨角度绕回。生产回归走真实 SessionState.observeClient、预测器、旅行 ticker、碰撞、MovementEffect ACK 和传送确认，比对原版输出速度/位移、上一输入旋转和 boost post-tick 计时。没有把代理自身输出当预期，也不把人工无遮挡序列称为真实客户端录制。修复前 tick 29 预期上一 pitch=-15、实际=1，在业务断言处失败；修复后逐帧通过。

迁入原 ElytraA 的合并输入归属：基于已 ACK 胸甲槽识别鞘翅；非法 START_GLIDING 单独告警、改为 STOP_GLIDING、清滑翔标志并同步姿态；预测完成时无论 offset 为零或很大都执行既有 rewind，阻止同一输入被 Phase/通用偏移检查重复标记或接受为基线。原输入对象拥有本次纠正；之后输入不继承该归属。库存其他槽和未确认的服务端装备不能授权滑翔，加载/未出生/旁观/豁免分支保留。客户端装备重同步只重发已知且未失效的完整后端 armor 快照；纯代理没有原插件的任意服务端库存读取，不伪造未知装备。保留原告警文本和 Elytra-A 禁用键在当前预测检查配置中的含义。

已确认的后端边界：原插件通过 EntityElytraFirework 的跟随玩家对象与同步调用栈，过滤 Nukkit 烟花 setMotion，并用 GLIDE_BOOST 生命周期替代。纯代理只能看到 AddEntity/SetEntityMotion，现有 Nukkit 包没有提供可靠的调用来源/跟随玩家标识；本轮没有根据数值、邻近位置或时间猜测后吞掉普通击退。这部分完整转换仍需服务端来源桥接。普通协议 MovementEffect 的 ACK、推进和滑翔公式已在上述连续场景核验，但不据此宣称 Nukkit 原生烟花实测问题全部解决。

最终验证：66 项状态/姿态等相关专项通过；补充运行全部碰撞及鞘翅共 59 项通过。首次全量发现未初始化协议且空靴子入口错误触发 NativePredictionItems 解码，已在空槽/未知协议时短路并优先使用实际玩家或世界协议；该次已中止，不计作通过。最终全量 `test installDist --offline`（既有 1024m、forkEvery=10 外部 init）通过：462 项、106 类，0 失败/错误/跳过。JAR SHA-256 `7fb0f0fd5a40a50e0147788d515350daea06694b4983ed84a90fca395cd937b3`，九处生产 class 与编译输出、六份碰撞修正资源与源资源均逐字节一致；测试类、门参考和滑翔序列均未入包。用户 BadPacketsD SHA-256 保持 `6c96291ddbd6eb4ee0b2b60db72a37c846f0b51f64de5b09cc289b421a58ebe3`。已部署并重启代理 PID 11014，Nukkit PID 17740 保持运行，同 JVM 加密初始化与两端协议 2168 NetworkSettingsPacket 握手通过。实际客户端滑翔、烟花和蜂蜜碰撞待重新连接实测。验证摘要 `build/elytra-collision-validation.json`、源码/原版来源哈希 `tools/elytra-collision-reference-sha256.json`；原始失败、独立原版输出、重放、完整通过报告、diff 和部署记录在 `/data/data/com.termux/files/home/navro-elytra-collision-20261115-020543`。


## Nukkit 原生烟花插件与独立 NetherNet 线路（2026-11-15）

用户授权配套后端插件后，新增 backend/nukkit/NavroFireworkBridge，针对当前 Nukkit-MOT（JAR SHA-256 7f3ff6c07f7cc5027998532a913ae056e3bbdba9d554390e67aca0f4f05dec50）。接受的本地 EntityElytraFirework AddEntity 在 afterSend 后启动标准 GLIDE_BOOST(1000000)；RemoveEntity 后仅在最后一个本地已见烟花移除时发停止效果。重复生成、其他玩家烟花、取消生成、无关移除不会改变本地加速。

只有已启动效果且同步调用栈中 Player.setMotion 的直接调用者恰为 EntityElytraFirework.onUpdate 时，才取消该本地运动包；不按运动值/时间猜测，不按栈中任意祖先判断。后者会误吞烟花爆炸过程中的真实击退，本实现保留该嵌套伤害路径。未知来源和普通击退照常转发。插件不修改玩家速度、库存或客户端输入。标准效果走既有代理编码与 closing ACK，代理核心 JAR 本轮未改。

backend/nukkit/build.py 用实际后端 JAR 编译并运行真实 EntityElytraFirework.onUpdate 与 Player.setMotion 联动，夹具仅提供外部世界/网络与伤害触发条件。验证原生推进过滤、普通与爆炸击退保留、重叠/重复/取消/远端烟花、spawn/remove 与效果顺序。两个仍能编译运行的错误版本（遗漏推进过滤、遗漏效果启动）分别在实际业务断言失败，证据 ~/navro-firework-mutations。插件已部署为 /data/data/com.termux/files/home/navro-proxy-backend-test/plugins/NavroFireworkBridge.jar，SHA-256 aa71c3dd1f44d1b0fa82028356528528fa760960efe7977db25d913f99242c54，Nukkit PID 12147 启动日志 FIREWORK_BRIDGE_READY。旧控制台因此前 NONBLOCK 产生 EAGAIN 而退出，使用已核对进程的 SIGTERM 触发 Nukkit 保存/关闭钩子，重新启动改为阻塞 RDWR FIFO。真实客户端烟花修复后表现尚待实测。

新增 nethernet/relay.py，提供独立 HTTP 信令和 UDP 媒体转发：TCP 19136、UDP 19140–19203 -> 同机官方 BDS NetherNet 19134；与现有 RakNet UDP 19132 -> Nukkit UDP 19133 两路完全独立，不接 RakNet、不复用其 socket、不做两种协议转换。保留 DTLS 端到端加密、SDP 身份/指纹/ICE 凭据；每个客户端独立媒体端口与后端 socket，空闲回收。只支持明确配置的同机后端与验证属本机的 ICE 候选。因媒体不解密，这条 NetherNet 线路暂不执行 Navro 包检查。

实际运行官方 BDS 1.26.63.1，协议 2225。Helix NetherNet/Pion 探针固定提交 3c60f0396e41d85e582ca3da2386384482467f1d；Go 1.27 Android 的 anet 内部符号需 -checklinkname=0，网卡 netlink 枚举可能无期限阻塞，因此探针使用由实际目标路由确定的本机接口和真实 OS socket。NetherNet batch 不含 RakNet 0xfe 前缀；首版探针前缀错误造成无响应，修正后真实响应通过。未修改上游库代码。

最终三条真实探针均完成 WebRTC/DTLS/SCTP 并返回 NetworkSettingsPacket：BDS 直接 127.0.0.1:19134、独立入口 127.0.0.1:19136、局域网入口 192.168.0.104:19136。HTTP/UDP 边界测试另验证双向不透明字节及密码字段完全保留，明确不冒充 WebRTC 实现。当前独立中继 PID 12773，BDS PID 13790。BDS 的 GET /v1/join 返回 HTTP 200 空 JSON 正文，中继原样保留；探针绕过服务器卡片查询，真实客户端列表发现和完整认证入服尚未验证。公网/NAT 与信令、媒体出口 IP 不同的场景亦不在已验证范围。

运行与验证：~/navro-proxy-backend-test/firework-bridge-deployment.json、~/navro-nethernet-independent/verification.json；源码与边界说明 backend/nukkit/README.md、nethernet/README.md。Nukkit 插件单独构建验证，不进入 NavroProxy classpath；NetherNet 探针依赖单独模块，不进入代理生产 JAR。用户 BadPacketsD 修改保持原样。现有两端 RakNet 协议握手在插件加载后重验通过；NetherNet 尚不宣称完成 Navro 的检测移植。


## NetherNet 原生批次观察入口与独立测试后端（2026-11-15）

真实客户端已在 BDS 日志完成 DriftyJet720138 的连接和出生，证明既有端到端加密转发 19136 可进入游戏。继续接入观察时新增独立终止两侧 WebRTC 的 19137 入口，由单独 Go transport 通过仅回环 TCP 19138 把原生 NetherNet batch 交给 Java NetherNetBatchSession。19136/RakNet 路由保留，未把 NetherNet 转成 RakNet。

Java 使用现有 Cloudburst 协议 codec、ProtocolDefinitions 和 SessionState，按原生无 0xfe 前缀批次解析，处理压缩协商、raw-deflate 与 255 原始包。经前端 DTLS 证明的公钥必须匹配有效微软登录与客户端签名；日志只记录包类型/状态，不记录 JWT。共享元数据、库存与世界状态的提交绑定本通道关闭批次 ACK；代理 marker 可以与游戏包混批，只消费 marker，保留其他包及顺序。未知/畸形压缩、包长/子客户端组合仍明确拒绝。此入口是 INSPECT，不启用检测或纠正，不能据此宣称 BDS 物理已经对齐。

用户测试 19137 后，前端日志显示 LoginPacket authenticated=true，随后 BDS 返回 DisconnectPacket，客户端提示必须通过 Microsoft 服务认证。当时原后端19134仍 online-mode=true。按用户补充，新增仅回环 BDS 19139 / 新世界 navro-nethernet-inspect，online-mode=false，19137 改接该实例；前端仍验证微软身份，原19136及其后端认证配置不变。原用户私钥不能被代理代签两侧 DTLS，BDS 是否还要求特定传输/登录密钥绑定，需以本次配置更改后的完整登录实测为准，未提前声称已解决全部登录限制。已补日志记录 BDS Disconnect reason。新入口的 TLS/DTLS/ReliableDataChannel 与真实 NetworkSettingsPacket 响应通过，真实客户端登陆待复测。

专项 8 项通过：实际 BDS 握手字节、真实元数据经 native closing ACK 提交、混批 marker 去除后其余 packet 保持、raw-deflate 编解码与原字节保留、缺少 transport identity 的登录拒绝，以及既有普通传送路径。此前增加 Login 拒绝夹具误用 UNKNOWN AuthType 造成序列化失败，改成可编码的明确 guest payload，再验证真实登录拒绝原因；该夹具失败不算生产缺陷证据。独立 Go transport 已编译，实例运行于19137；本轮没有把原RakNet代理替换成观察模式。

记录 tools/nethernet-inspect-validation.json；原失败与专项目标结果、改动前源码快照位于主目录 navro-nethernet-checks-current.txt 指向目录。运行日志 ~/navro-nethernet-independent/inspect-java.log、inspect-transport.log；测试后端 ~/navro-bds-nethernet-inspect/launch.json。当前完整客户端检查入口登陆状态为待复测，原19136已确认能登录。


## NetherNet / BDS 出生坐标同步（2026-11-15）

用户确认在独立 NetherNet DETECT 入口正常移动，却发生 GroundSpoof/Vertical 等告警。原始会话 `792b33c3-a106-483e-92f5-b3f642f1696e` 首个预测帧 tick 86：客户端 wire `(0.5,-58.37999,0.5)`、delta 0，脚部为 Y=-60；代理仍使用 StartGame 的 Y=32769.62，首帧偏差 32829.62。后续回写把这个错误预测坐标送往 BDS，继续污染纠正与检测。

独立 Gophertunnel NetherNet 客户端直接连接私有 BDS 1.26.63.1 / protocol 2225，真实登录并出生，录得 StartGame 高空占位坐标，随后两个 SERVER_SEARCHING，最终 `Respawn SERVER_READY(position=(0.5,-58.37999,0.5), runtimeEntityId=0)`。原始 StartGame 与最终 READY body 保存为原生 batch 测试资源；来源、转换及 SHA256 见 `src/test/resources/nethernet-bds-2225-spawn-provenance.json`。这不是完整 gameplay 录制；floor 与生命周期组合由测试准备，输入字段来自用户实际静止帧，不能据此声称所有连续行走或 BDS 物理都已验证。

仅独立 NetherNet 适配显式启用 BDS Respawn 位置语义：StartGame/SEARCHING 开始等待，READY 的移动坐标换算为脚部坐标，绑定其真实 closing ACK 后建立模型、AABB 和有效回拉基准，并清理先前预测历史。等待时输入仍经过包检查和 tick 处理，但不运行碰撞预测、不把占位坐标回写后发给 BDS。多个出生包以修订号隔离旧 ACK；另外保留 READY 之后收到的服务端传送和速度包：仅删除 READY 到达前已存在的传送，外部速度在 READY 到达时作废早期序列，不能等 ACK 时一并消费后发击退。非本地非零 runtime ID 不改变本地出生位置。

RakNet/Nukkit 保持原重生握手语义：MOT SERVER_READY 本身不恢复生命或定位，仍依赖 MovePlayer 与正生命值确认。NetherNet 后续重生同样保留独立健康等待；坐标 ACK 不能替代正生命值 ACK。用户修改的 BadPacketsD 完整保留，阈值、碰撞规则和 GroundSpoof/Phase 判罚没有调宽。

验证：修复前两个真实 native batch 边界回归失败，分别揭示错误回写和 ACK 后仍处于占位位置。新增场景覆盖 READY/初始化前后顺序、位置与生命值分别确认、旧 ACK 隔离、其他玩家包隔离及后发传送保留。静止恢复后检查 rawOffset；正常检查阶段故意向草方块内提交位置，真实 Phase 仍触发，避免用永久免检获得通过。实际客户端修复后重新登录、正常移动与死亡重生仍需实测。最终测试和部署结果另见 `tools/nethernet-spawn-validation.json`。

本轮最终验证：24 项专项、477 项全量（110 个类）全部通过，无失败/错误/跳过；installDist 成功。已核对分发 JAR 与测试 class 字节一致。修复版独立 NetherNet 19137 已启动，部署后 WebRTC 与 NetworkSettingsPacket 实际链路验证通过，私有 BDS 与指定账号 OP 重连补授进程运行。当前原 LAN 地址已失效，运行时按当前网卡配置；同手机使用 `127.0.0.1:19137`。修复后客户端正常移动、死亡重生尚待重连实测。


## BDS 原生疾跑属性与源 tick 同步（2026-11-15）

真实会话 `8d49d2d9-7ee6-4df6-bd74-631f738e4815` 共 14 次 Horizonal。出生位置已确认 Y=-60。首个错误为输入 tick199 的 STOP_SPRINTING：此前源 tick144 的 movement=0.13 已于输入146确认，代理随后删除本地疾跑修饰器；STOP 无修饰器可移除而保持 0.13，真实客户端已减速。旧实现回放 tick181..199 在199精确复现 rawOffset=0.029400367。

源码核对纠正了早期归因：本地 Navro `PlayerData.setAttribute` 本身就 clearModifiers/defaultBase/wireValue，START/STOP 修改 UUID D208FC00-42AA-4AAD-9276-D5446530DE43，元数据更新 serverSprinting，下一输入快照使用 serverSprinting。现有代理在这些行为上与本地 Navro 一致；本轮是 BDS 原生路径适配，不能写成遗漏原版公式或所有后端通用替换。原 SprintAttributeReferenceTest/MovementStateReferenceTest 留存原始固定参考数据并继续验证默认 Nukkit 路径。

NetherNet 适配显式启用 nativeSprintSynchronization。BDS 属性包携带其处理输入的 tick：以已接受的疾跑动作历史确定源 tick 的疾跑成分，从服务端实际总速度中剥离该成分，再保留当前有效疾跑状态。停止疾跑可撤销自身加速，延迟开始/停止响应不能错误重复加速或冲掉较新的动作；服务端自定义总速度仍参与计算，不固定恢复成0.1。实体元数据保留源 tick，晚确认的旧疾跑字段不覆盖更晚接受的动作，其余姿态字段仍按ACK更新。当前有效的新服务端状态仍照常接入。动作历史有界，并随 WireTickSnapshot 一同保存/恢复；待确认元数据的源 tick 也随快照保存。

验证范围：真实停止疾跑连续输入片段来自 rawInput 字段，起点位置/速度来自该片段前一条真实客户端输入，地形按日志里的草方块条件准备；没有把旧预测值用作预期。验证 SessionState.observeClient 的真实输入、碰撞、预测、判罚及属性ACK，定时验证器和网络传输在此边界之外。旧实现回归失败，修复后 rawOffset 必须小于现有1e-4阈值；额外构造穿地输入必须触发真实Phase。源tick与较新动作隔离、自定义速度及带待确认元数据的快照恢复另用明确构造的协议条件验证。

日志中的旧 metadata 记录未含源 tick，不能把重建时序假称完整网络录制。后期 tick531 的0.0022629001残余偏差也缺少当时原始 BDS 纠正内容，不据此调宽阈值或声称全部消除。本轮追加 backend_prediction_correction（sourceTick/position/delta/onGround/type）、元数据 packetTick、属性 wire modifiers 诊断以供下一次完整实测。最终验证和部署另见 `tools/nethernet-sprint-validation.json`。

最终验证：69 项专项和 480 项全量（111 类）通过，0 失败/错误/跳过；installDist 成功，测试 class 与部署 JAR 字节一致。已部署 DETECT NetherNet 19137，部署后独立 WebRTC/NetworkSettings 握手通过，后端 19139 与 OP 重连授权守护进程运行；本机入口 `127.0.0.1:19137`，当前局域网地址 `192.168.1.76:19137`。RakNet 独立未改。下一次完整客户端登录需确认移动表现；旧 tick531 的小残余偏差未宣称修复，新增 sourceTick/原生纠正诊断。


## NetherNet 独立世界缓存隔离（2026-11-15）

完整会话 `7c23581f-7c2b-4938-9394-fb2a4546585b` 中发生持续回拉：约79秒包含888个因 `hard_teleport` 未转发的输入、406个 `collision_wait_terrain` 和144个后端 `CorrectPlayerMovePredictionPacket`。玩家脚仍回到初始 `(0.5,-60,0.5)`。BDS校正坐标已推进至约 `(-0.504,-58.38,4.641)`，而出生段 `[0,-4,0]` 仍 unknown。它的 SubChunk 返回 SUCCESS + blob `-8109899664625916206`、无 inline data；同批附近 `[1,-4,0]` 另一个 blob 最终成功解析，出生段没有收到对应 miss data。该 NetherNet 客户端与代理新建的后端连接拥有不同/独立的 blob cache；透明转发客户端 `ClientCacheStatus.supported=true` 会让 BDS 对代理尚未缓存的块只发引用，碰撞世界永久缺段。

修复只在 `NetherNetBatchSession` 上游包边界把客户端 `ClientCacheStatus.supported` 改成 false，迫使 BDS 在独立连接发送完整地形；不改客户端到代理的认证、RakNet/Nukkit 行为或检查阈值。专项回归真实编码/解码并确认客户端请求 true 到后端发 false；区块生命周期29项、NetherNet套件和既有疾跑/移动参考也在同一轮执行：50项、8类，0失败/错误/跳过。installDist 成功，分发JAR类与测试构建一致。已部署 DETECT 19137，独立WebRTC/NetworkSettings探针握手成功；尚未有真实客户端重登验证 `section [0,-4,0]` 成为 known，因此不宣称已实证停止回拉。下一次连接需看 `subchunk_applied known=true`、terrain wait 和 hard teleport 是否停止增长。

## 液体出口碰撞查询对齐（2026-11-15）

对照当前原版 `NavroPlayer.doesNotCollide/containsFluid`、`CompensatedWorldImpl.noCollision/getEntityCollisions/collectColliders` 与 `LivingTicker.travelInFluid`，发现代理出口探测错误使用 `collectMovement(exitBox, exitBox)`。这会把出口附近但未相交的实体直接视为阻挡，也会在出口盒包含于已提交移动查询时复用整份移动碰撞列表，将出口之外的墙/地面当成阻挡。

代理 `CompensatedWorldImpl` 补入原版 `noCollision` 边界：附近实体提供器仍以当前真实玩家 AABB 查询，然后按出口盒 expand(1e-7F) 筛选相交实体；方块走普通 `blockEntries` 查询，保留所属输入的精确查询缓存，不借用移动列表。`PredictionContext.doesNotCollide` 调用该边界后继续检查液体。出水上抬 0.3F、流体旅行/阻力/重力、原始碰撞规则及判罚阈值不变。

`FluidExitCollisionTest` 使用明确构造的协议实体、地形与速度条件，执行生产出口探测及完整 `PredictionRunner`。独立依据为以上原版调用链和明确几何关系，夹具不实现出口决策。旧实现分别在附近非相交实体、无关移动缓存以及完整预测的 0.3F 出口速度断言失败；完整预测旧结果为 -0.005F。真正相交的实体、方块、水与岩浆仍须阻止出口，防止用无条件放行获得通过。首轮夹具遗漏实体尺寸且水位未达到收缩采样区域，已修正；该轮结果不作为生产缺陷证据。

本轮只运行流体、碰撞历史和输入预测专项，不运行全量回归。源码修改前快照、已修正夹具的旧实现失败证据及验证产物保存在 `/data/data/com.termux/files/home/navro-fluid-exit-d8rsczcg`。最终结果与来源哈希见 `tools/fluid-exit-alignment-validation.json`。本轮不宣称真实客户端连续游泳或此前 NetherNet 回拉问题已获实测验证。


## 通用重叠解算替换固定 0.05 与客户端浮点审计（2026-11-15）

按用户明确要求，位置接受后的固定竖直脱离额度已删除，替换为一次性的几何重叠解算请求。真实移动和历史试算共用最近分离面/实际重叠深度计算；输入开始/结束快照保留请求归属，真实移动才消费。没有按箱子、顶头、坐标或特定深度增加规则。请求有效时可处理其他方向及超过旧 0.05 的实际重叠，因此这是代理策略调整，不宣称与原生固定额度行为完全等价。

客户端浮点三角函数166230组、完整65536项查表、188组原始碰撞参考及478帧连续边界参考无差异。旧箱子12帧及水面61帧进入持久几何回归；较新历史会话18次顶头wire offset全部为零。旧151帧顶头片段从已有偏高箱开始，仍有22帧微小残差；其前序缺少5帧，未以强制重建箱高或放宽阈值宣称修复。

142项相关专项（27类）通过，jar成功，没有全量回归，未部署。详细实现、行为变化、原生参考范围、重建箱对照及剩余证据缺口见 FLOAT_COLLISION_ALIGNMENT.md；文件哈希及产物摘要见 tools/geometric-overlap-validation.json。用户BadPacketsD修改保留。


后续命令迁移已补齐 `NavroCommand` 的 alert/reload/debug/debug2，替代当时仅包含 alert 的 `NavroAlertCommand`。当前行为和验证范围见 `NAVRO_COMMAND_ALIGNMENT.md`；上文记录保留当时的迁移边界。
