# Boar Prediction 纠正与传送

参考 `opencollab-incubator/Boar` 提交 `b5e60eec8c18ca10f0ea148a2721e0599c8420f2`。

- 自生成软纠正由 `TeleportUtil.correct` 发送，`MovementCorrectionAck` 接独立 closing ACK。等待时继续模拟并转发通过检查的输入，但不采用客户端上报的位移和速度，不重复发送纠正；ACK 后再保留一个模拟帧的冷却。
- 软纠正前同步已有的旗标、移动属性，以及至少 200 tick 老的已确认区块中的后端方块快照。`ServerRewindPackets` 归一化普通相关封包的 tick，纠正同步包保留校正帧 tick。
- 普通 `MovePlayerPacket` 用 `TeleportAcceptAck`，不再强制改成 `TELEPORT`。等待 ACK 时，非载具输入转发目标坐标；已 ACK 的移动由 `TeleportHandler` 在输入阶段应用。已加载区块中的 `NORMAL` 走三步插值。
- `smooth-setback: false` 保留原模式及默认值。硬纠正依然发 `TELEPORT`，保留附带速度、严格的目标、tick 和 `HANDLE_TELEPORT` 校验。匹配的确认即使被入站检查拒绝也能解除挂起；被拒移动本身不转发、不参加回放。完全没有匹配确认时仍等待，没有放宽确认或超时放行。
- 后端发来的权威校正仍走单独的历史回放；BDS 的 ACK 批次调度和击退截止保持原样。日志、传输和物理模型继续使用代理自身的实现，不依赖后端插件。
