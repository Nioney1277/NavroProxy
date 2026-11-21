# 原版 Navro 传送与纠正对齐

本轮基准是 `../Navro/src/main/java/ac/navro/anticheat/` 当前源码。原始队列和纠正包选择逻辑另由 `NavroCorrectionReference.java` 直接执行；16 组结果保存在测试资源 `navro-correction-mode-reference.json`，不是在测试内复制另一套状态机。

## 原版行为

- 普通 `MovePlayerPacket` 等 closing ACK。ACK 到达后按队列顺序处理输入：模式 RESET 可接受目标附近的输入；TELEPORT 使用目标与 HANDLE_TELEPORT。最后一个确认不匹配时重新发送目标。
- 原版反作弊硬纠正是特殊的 `CorrectionResetData`：必须是 source tick 之后、匹配目标并带 HANDLE_TELEPORT 的输入，才能取出。NSL ACK 单独到达不会结束等待。
- 原版包检查先于 AuthInput 处理。Timer 或 BadPacketsC 拒绝的输入不进入传送确认。原版自身因此也存在确认帧被拒后持续等待的风险；不能把“跳过检查接收确认”或“超时解除等待”声称为原版实现。本轮未加入这些额外策略。
- `smooth-setback: false` 是原版默认值。开启时，玩家纠正发 `CorrectPlayerMovePredictionPacket` 并走软校正队列，而非 MovePlayer 硬纠正。
- 原版在硬传送确认后仍执行 post-prediction，缓存已确认的物理帧、维护 last-known-valid，并进行输入收尾；这一帧不会重复执行 travel。

## 已补齐的代理差异

1. 新增与原版同义的 `--smooth-setback`，默认 false。RakNet 启动器与 NetherNet Java 适配器均可选择；实际软纠正发送走各自真实编码、传输与 closing ACK。
2. 硬传送确认后接回 post-prediction 完成阶段。原代理直接跳过 `AuthInputPackets.run`，也跳过了这一阶段，导致确认帧没有进入规范纠正历史。
3. 自己生成的软纠正与后端权威纠正区分来源。自生成帧不启用 BDS 后端的外部 motion 截止策略；未包含在规范帧中的待确认击退仍遵循原版单次消费。
4. 恢复原版 pendingTeleportInput、teleportQueued、teleportLatencyAccepted 等队列诊断，另记录在检查阶段被拒输入的原始位置、HANDLE_TELEPORT 和拒绝项。此前 PostAuthInput 日志已写回预测位置，不能还原原确认包。

## 仍未解决的问题

前一轮真实 BDS 记录的疾跑/持续跳跃重放差异尚未消除。原版同样依赖保存的疾跑状态和 START_JUMPING，没有发现可以直接搬入并证明修好这两段 BDS 输入的通用动作重建实现。本轮没有删除真实 START_SPRINTING、人工补写历史 START_JUMPING 或扩大阈值。

严格硬纠正确认的等待策略仍与原版一致。本轮补齐的软纠正模式提供原版已有的可选路径，不应将它描述为硬传送确认丢失问题已根治。

## 验证和部署

原版执行结果、实际传输专项、失效敏感性、构建与部署状态见 `tools/navro-teleport-alignment-validation.json`。用户修改的 BadPacketsD 保留；不跑全量回归。


本轮结果：127 项相关专项通过（22 个测试类），无失败、错误或跳过；4 项可编译的失效变体均被目标断言捕获。JAR 和 installDist 内容一致，SHA-256 为 `2d2af7d92fd8dc0e6acdef1a513d6d7cc544d40aaa8d388fdc04869f3aaa3ffb`。未跑全量回归；未部署，本轮没有启用线上软纠正或改变测试入口的默认模式。
