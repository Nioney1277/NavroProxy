# Bedrock 原版方块物理属性

协议 2225 的摩擦、基础硬度和载具跳跃属性使用 BDS 1.26.63.1 原版运行时导出数据。导出包含 1,480 种方块、23,960 个状态，与本项目 `runtime_block_states_2225.dat` 的名称、完整状态和 NBT 值类型逐项匹配。数据来源、游戏二进制与调色板校验值保存在 `src/main/resources/navro/bedrock-properties-2225.source.json`。

## 查询与优先级

- `NetworkBlockStateRegistry` 按协议、方块名称和具体状态查表，将不可变属性附到 `NetworkBlockState`。数值 ID、哈希 ID、服务端重排的 palette 都经过同一身份匹配。运行时 ID 不作为跨注册表的物理属性主键。
- `NetworkBlockState.bedrockProperties()` 返回 `null` 表示没有该版本/状态的原版属性；不把未知状态伪装为“属性为零”。
- `NavroBlockState.getFriction()` 优先采用服务端声明的摩擦，然后使用原版表，最后沿用已有 fallback。
- 带服务端组件定义的方块保持连接独立的组件解析，不继承被覆盖的原版属性。自定义方块未声明的行为不从名称猜测。
- `VehicleEnvironment` 的跳跃阻止读取 `0x40000000`，脚下继续检查读取 `0x80`。其他协议维持原先的蜂蜜判断，不跨版本使用 2225 数据。

材质 API 暴露 `blocksMotion()`、`solid()`、`liquid()` 和原始材质编号/标志。材质实心与完整立方体碰撞、某一面的支撑能力是不同概念；现有动态碰撞、选择框、硬度、交互和放置数据均有独立用途，继续保留。

## 数据更新

从已验证导出 JSON 生成项目资源：

```bash
python tools/import-bedrock-block-properties.py /path/to/navro-protocol-2225-block-properties.json src/main/resources/navro/bedrock-properties-2225.bin.gz
```

转换器保留 NBT 数值类型、64 位掩码和原始 float 摩擦，验证状态唯一性、数量与材质标志后输出确定性的压缩二进制。当前导出 JSON 的 SHA-256 为 `d6970d2cfae98c5a0f69c2c8ce91b2559e3b17210759bbfa2d4cc1116a644f0d`。游戏升级后需要导出、核验新版本并显式增加加载支持，不能只改协议号复用旧表。

测试覆盖整张数值/哈希调色板、带类型的状态匹配、重排注册表、组件覆盖、跨协议隔离、摩擦与材质样例，以及蜂蜜上地毯的脚下检查。状态数据覆盖完整不代表已证明 BDS 与每个平台客户端所有行为逐帧一致；自定义方块也不包含在原版数据内。


## 基础硬度与挖掘校验

`NPH2` 在原版物理记录中增加 `hardness`，读取的是当前 BDS `BlockComponentDirectData.mDestroySpeed` 缓存。23,960 个状态的该值均与对应 `BlockDestructibleByMiningComponent.mSecondsToDestroy` 一致，本次原版注册表没有工具专用时间覆盖项。这是基础参数，不能单独当成拿工具或受药水影响后的实际挖掘耗时。

`BlockBreakState.hardness()` 对有原版属性的协议 2225 状态返回 BDS 值，对其他协议、未匹配状态和有服务端组件声明的方块继续使用原有后端数据。破坏校验从此查询读取硬度；空气和液体仍被排除，`-1` 仅保留现有精确 CREATIVE 模式例外，不更改其他模式语义。

`NativeBlockProperties` 的 MOT 表继续保留原生 ID、门和展示框等信息以及未覆盖状态的 fallback，不将 BDS 硬度混写为 MOT 原生值。新方块即使没有旧 ID 映射，也不会在已确认原版身份时仅因默认 ID 为零而被视为空气。

按原版 float 精度与旧表比较：19,640 个状态有原生映射，其中 2,730 个硬度不同；另有 4,320 个状态缺少旧 ID 映射。导出记录中保留完整逐状态比较。
