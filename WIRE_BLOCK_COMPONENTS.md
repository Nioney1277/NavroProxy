# 标准协议中的自定义方块组件

本轮读取 StartGame 的 BlockPropertyData，不需要后端插件或服务端核心 API。
输入来自后端实际发送的 NBT；每个连接独立保存，StartGame 替换定义时重建缓存。

## 已接入的数据

- `minecraft:collision_box` 的旧式 `origin` / `size` 和新版 `boxes` 多盒格式。
  保留每段实际碰撞盒，按协议单位转换，不合并成完整立方体。`enabled=false` 表示已知无碰撞；
  缺失、格式错误或越过所选协议合法边界的定义保持未知。
- `permutations` 按发送顺序应用。只求值能由当前方块 state 唯一确定的条件：
  q/query 的 block_state、block_property，数字和字符串相等比较、数字大小比较、布尔组合、括号、四则运算及一元正负号。
  不支持的表达式、转义语义不明确或大小写结果有歧义的字符串比较不会被当作 false。
  按组件追踪未知条件的影响；后续条件确定成立并完整覆盖该组件时，采用最终声明值。
  最终仍受未知条件影响的组件继续报告未知。
  已明确禁用碰撞或所有盒体积均为零时，可直接确定无碰撞，变换条件不影响该结论。
- 读取定义的 `molangVersion`，按协议 2207 前后的枚举编号选择逻辑运算优先级和负数除法规则。
  旧版本负数除法暂报未知；声明缺少版本时，仅采用新旧规则能共同确定的结果。
- `minecraft:transformation` 的 RX/RY/RZ 按四分之一圈解释，SX/SY/SZ、TX/TY/TZ 按实际 float 值读取。
  支持 RXP/RYP/RZP 旋转枢轴和 SXP/SYP/SZP 缩放枢轴，保留原生矩阵的四元数转换舍入。
  网络路径的负步数沿用原生函数跳过该轴旋转的行为，正步数按完整四分之一圈重复。
  每段碰撞盒使用原生中心/半径顺序变换，保留负缩放后的边界及零体积结果。
  普通碰撞查询按该连接实际声明的盒范围扩大来源格搜索，覆盖跨格位移和缩放。
- 摩擦组件的明确 `value` 及可静态决定的覆盖传入原有移动视图。
  使用客户端摩擦系数到移动阻力因子的 `1F - value` 转换，保持 float 运算。
- 哈希方块 ID 可由明确的 BYTE/INT/STRING 属性枚举展开，保留 NBT 类型并按现有 Hash.hashBlock
  编码计算。冲突哈希不会静默覆盖其他状态。状态扩展设置明确数量上限，不截断后冒充完整表。
- 已发送完整 runtime 调色板或已配置实际后端 runtime 表时，保持该表的 ID 顺序。
  标准状态的原生身份仅按完整名称和 state 属性精确匹配，不按当前 runtime ID 猜测。

碰撞组件进入现有 WireBlock、普通碰撞查询及单元格片段查询；块更新仍等待其原出站批次 ACK。
没有新建另一套碰撞求解器。Collider、玩家传送和 ACK 的既有专项实现保持不变。

## 明确边界

这不是完整的 Molang 执行器，也不代表所有自定义方块行为均已实现。
未知变换字段、非整数旋转步数和非有限数值保持未知。Molang 的三元表达式、函数调用、
语句及脚本状态仍有支持缺口；除零和非有限算术结果也保持未知。
依赖玩家、时间、随机数、任意脚本或其他未取得上下文的变体也保持未知。
本轮没有从资源包模型猜测碰撞形状，没有按名称、贴图或渲染模型补造物理参数。

数字 runtime ID 的实际顺序若没有传来或明确提供，自定义声明本身不足以确定它。
这种组合会明确要求实际后端的完整 runtime 表，不再把固定的 vanilla 顺序当作可用映射。
现有 `--block-palette` 接口可提供真实后端导出的表；导出格式见 nethernet/README.md。

碰撞形状和摩擦值不等于方块的全部原生交互规则。没有精确原生行为映射时，放置上下文仍报告
缺少事实，不把未知自定义方块误作 AIR。未发出的尺寸、交互逻辑和其他服务端私有状态仍不推断。

## 依据与验证

网络布局对照本机已有 Geyser 的 CustomBlockRegistryPopulator、GeyserCustomBlockComponents，
以及 MOT 的 CustomBlockDefinitionSerializer898、CustomBlockDefinition；来源文件哈希见
`tools/wire-block-components-source-sha256.json`。这些源码只用于核对实际发包字段，不进入代理类路径。

BlockTransformationTest 使用原生矩阵与 AABB 函数生成的 448 组结果，逐项精确比较矩阵和盒边界。
参考来自 BDS 1.21.3.01 Linux 的独立函数执行，版本、SHA-256、函数地址和输入见
`tools/wire-block-components-source-sha256.json`。这些数值用于测试，生产实现直接计算变换。

NetworkBlockComponentsTest 覆盖网络盒格式、变体顺序、条件范围、未知状态、各协议高度边界、
属性枚举、哈希、连接隔离、runtime 顺序和精确原生状态匹配。
NetworkBlockComponentsLifecycleTest 经过真实 SessionState、ACK 和完整 PredictionRunner，
验证多段世界空间盒、确认前后的方块更新、跨格变换，以及连续自定义冰面和既有冰面预测的一致性。
这些是构造的协议场景，不宣称已经完成真实客户端的自定义内容实测。

本轮回归覆盖自定义组件、调色板、碰撞、放置、区块、输入、传送及 NetherNet 批次，
共 26 个测试类、170 项，失败、错误和跳过均为零。
原生参考覆盖 448 组矩阵与碰撞盒结果，包括组合枢轴、非均匀缩放、负缩放、零缩放、
小数缩放、负旋转编码和超过一圈的正旋转编码。
条件测试同时覆盖运算顺序、版本编号变更及 StartGame 注册表的实际变体选择路径。

`jar installDist --offline` 成功，五个生产 class 与 JAR 内字节一致，构建 JAR 与安装目录 JAR 一致。
TeleportUtil、TeleportHandler、PostAuthInputPackets、BadPacketsD 和 Collider
在本轮修改前后的 SHA-256 一致。

构建 JAR SHA-256：`1c3a4ff91bb44fa89be7b0ab4164ac51cf2c4753fc5426d31fc518e340360ca4`。
